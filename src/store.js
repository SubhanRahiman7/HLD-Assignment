// Node primary store — mirrors Store.java.
// Keyed by query; buckets: [last5m, last1h, last24h] decayed by RecencyDecay job.

const fs = require('fs');
const path = require('path');

function makeBuckets() { return [0, 0, 0]; }

const DATA = []; // {q, c} — final source of truth for queries/counts
const index = new Map(); // query.lower -> DATA entry (fast lookup)
const recency = new Map(); // query.lower -> [5m, 1h, 24h]

const writeBuffer = new Map(); // query.lower -> delta
let pendingWrites = 0;
let totalBatches = 0;
let totalBatchedOps = 0;
let lastFlushMs = 0;

function applyCount(q, delta) {
 const e = index.get(q.toLowerCase());
 if (!e) return;
 e.c += delta;
}
function enqueueIncrement(q) {
 const k = q.toLowerCase();
 writeBuffer.set(k, (writeBuffer.get(k) || 0) + 1);
 pendingWrites++;
 bumpRecency(q);
}
function bumpRecency(q) {
 const k = q.toLowerCase();
 const v = recency.get(k);
 if (!v) recency.set(k, [1, 1, 1]);
 else { v[0]++; v[1]++; v[2]++; }
}
function flushBatch() {
 if (!writeBuffer.size) return;
 const t0 = process.hrtime.bigint();
 for (const [q, delta] of writeBuffer) {
 applyCount(q, delta);
 }
 writeBuffer.clear();
 pendingWrites = 0;
 lastFlushMs = Number(process.hrtime.bigint() - t0) / 1e6;
 totalBatches++;
 totalBatchedOps += writeBuffer.size;
}
function decayRecency() {
 for (const [k, v] of recency) {
 v[0] = Math.floor(v[0] * 0.85);
 v[1] = Math.floor(v[1] * 0.95);
 v[2] = Math.floor(v[2] * 0.99);
 if (v[0] + v[1] + v[2] < 1) recency.delete(k);
 }
}

// Data loaders. All three are real Kaggle-derived files in data/.
// - aol_queries.tsv : AOL search log. id<TAB>query
// - product_queries.csv : Amazon ESCI product search queries
// - trends.csv : Google Trends top queries by year/category
//
// Frequency is aggregated by raw query text (case-insensitive), then
// counts are merged across sources so a query that appears in all three
// gets the union of its frequencies.
function loadTsvQueries(file, queryCol) {
 const p = path.join(__dirname, '..', 'data', file);
 if (!fs.existsSync(p)) return new Map();
 const text = fs.readFileSync(p, 'utf8');
 const out = new Map();
 for (let line of text.split('\n')) {
 if (!line) continue;
 if (line.startsWith('Query') || line.startsWith('query')) continue; // header
 const parts = line.split('\t');
 if (parts.length <= queryCol) continue;
 const q = (parts[queryCol] || '').trim().toLowerCase();
 if (!q || q.length < 2) continue;
 out.set(q, (out.get(q) || 0) + 1);
 }
 return out;
}
function loadCsvQueries(file, queryCol) {
 const p = path.join(__dirname, '..', 'data', file);
 if (!fs.existsSync(p)) return new Map();
 const text = fs.readFileSync(p, 'utf8');
 const out = new Map();
 for (let line of text.split('\n')) {
 if (!line) continue;
 if (line.startsWith('Query') || line.startsWith('query')) continue; // header
 const parts = line.split(',');
 if (parts.length <= queryCol) continue;
 const q = (parts[queryCol] || '').trim().toLowerCase().replace(/^"|"$/g, '');
 if (!q || q.length < 2) continue;
 out.set(q, (out.get(q) || 0) + 1);
 }
 return out;
}
function mergeFreq(...maps) {
 const out = new Map();
 for (const m of maps) {
 for (const [k, v] of m) out.set(k, (out.get(k) || 0) + v);
 }
 return out;
}
function pickTrendingSeed() {
 // Google Trends: rank indicates popularity. Use low ranks (most popular)
 // to seed the recency buckets so /trending returns sensible defaults
 // before the user starts searching.
 const p = path.join(__dirname, '..', 'data', 'trends.csv');
 if (!fs.existsSync(p)) return;
 const text = fs.readFileSync(p, 'utf8');
 const lines = text.split('\n');
 const scored = new Map(); // query -> max score (lower rank == higher score)
 for (let i = 1; i < lines.length; i++) {
 const parts = lines[i].split(',');
 if (parts.length < 5) continue;
 const rank = parseInt(parts[3], 10);
 const q = (parts[4] || '').trim().toLowerCase();
 if (!q || !isFinite(rank)) continue;
 const score = Math.max(0, 30 - rank);
 if (score > (scored.get(q) || 0)) scored.set(q, score);
 }
 for (const [q, s] of scored) recency.set(q, [s, s + 2, s + 4]);
}
function seed() {
 const aol = loadTsvQueries('aol_queries.tsv', 1);
 const products = loadCsvQueries('product_queries.csv', 0);
 const merged = mergeFreq(aol, products);
 // Pull the top-N most frequent queries so the index stays bootable-fast.
 const rows = [...merged.entries()]
 .sort((a, b) => b[1] - a[1])
 .slice(0, 200_000);
 for (const [q, c] of rows) {
 DATA.push({ q, c });
 index.set(q, DATA[DATA.length - 1]);
 }
 pickTrendingSeed();
 console.log('Loaded dataset:', rows.length, 'unique queries from',
 'aol(' + aol.size + ') + products(' + products.size + ')');
}

module.exports = {
 DATA,
 index,
 recency,
 enqueueIncrement,
 applyCount,
 flushBatch,
 decayRecency,
 seed,
 pendingWrites: () => pendingWrites,
 totalBatches: () => totalBatches,
 totalBatchedOps: () => totalBatchedOps,
 lastFlushMs: () => lastFlushMs,
};
