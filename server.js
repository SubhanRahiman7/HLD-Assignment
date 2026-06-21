// Search Typeahead backend (Node)
// - In-memory primary store (query -> count) with decayed recency buckets
// - 4-node consistent-hash cache with virtual nodes (200 vnodes/node)
// - Batched async writes via 2s window
// - Telemetry includes p95 suggest latency

const express = require('express');
const path = require('path');
const {
 DATA, index, recency,
 enqueueIncrement, applyCount, flushBatch, decayRecency,
 seed, pendingWrites, totalBatches, totalBatchedOps, lastFlushMs,
} = require('./src/store');
const { nodeForPrefix, getAllNodes, perNode, invalidateAll } = require('./src/cache');
const { rank } = require('./src/ranking');
const recentMod = require('./src/recent');

const app = express();
app.use(express.json());
app.use(express.static(path.join(__dirname, 'public')));

const CACHE_TTL_MS = 60_000;
const ALL_NODES = getAllNodes();

const telem = { hits: 0, misses: 0, batches: 0, batchedOps: 0, lastFlushMs: 0 };

// p95 sampling — store ms->count, capped at 5000 unique sample values
const suggestSamples = new Map(); // ms -> count
let sampleCount = 0;
function recordSample(ms) {
 if (suggestSamples.size < 5000) suggestSamples.set(ms, (suggestSamples.get(ms) || 0) + 1);
 sampleCount++;
}
function computeP95() {
 if (sampleCount === 0) return 0;
 const target = Math.ceil(sampleCount * 0.95);
 let acc = 0;
 for (const [ms, n] of [...suggestSamples.entries()].sort((a, b) => a[0] - b[0])) {
 acc += n;
 if (acc >= target) return Number(ms.toFixed(2));
 }
 return 0;
}

app.get('/suggest', (req, res) => {
 const raw = (req.query.q ?? '').toString();
 const prefix = raw.trim().toLowerCase();
 if (!prefix) return res.json({ suggestions: [], node: null, hit: false, ms: 0 });
 const started = process.hrtime.bigint();
 const node = nodeForPrefix(prefix);
 const cache = perNode[node];
 const cached = cache.get(prefix);
 const now = Date.now();
 if (cached && now - cached.at < CACHE_TTL_MS) {
 telem.hits++;
 const ms = Number(process.hrtime.bigint() - started) / 1e6;
 recordSample(ms);
 return res.json({ suggestions: cached.list, node, hit: true, ms: Number(ms.toFixed(2)) });
 }
 telem.misses++;
 const mode = (req.query.mode || 'popularity').toString();
 const list = rank(DATA, prefix, mode, recency).slice(0, 10).map((e) => ({ q: e.q, c: e.c }));
 cache.set(prefix, { list, at: now });
 const ms = Number(process.hrtime.bigint() - started) / 1e6;
 recordSample(ms);
 res.json({ suggestions: list, node, hit: false, ms: Number(ms.toFixed(2)) });
});

app.post('/search', (req, res) => {
 const q = (req.body?.q ?? '').toString().trim();
 if (!q) return res.status(400).json({ error: 'q required' });
 if (q.toLowerCase() === 'error') {
 return res.status(503).json({ message: 'Upstream search service did not respond within the latency budget.' });
 }
 const started = process.hrtime.bigint();
 const k = q.toLowerCase();
 let entry = index.get(k);
 if (entry) enqueueIncrement(entry.q);
 else {
 DATA.push({ q: k, c: 1 });
 index.set(k, DATA[DATA.length - 1]);
 entry = DATA[DATA.length - 1];
 recency.set(k, [1, 1, 1]);
 }
 flushBatch();
 const key = entry.q.toLowerCase();
 for (const n of ALL_NODES) {
 for (const k of Array.from(perNode[n].keys())) {
 if (key.startsWith(k)) perNode[n].delete(k);
 }
 }
 recentMod.addRecent(q);
 const ms = Number(process.hrtime.bigint() - started) / 1e6;
 res.json({ message: 'Searched', query: entry.q, count: entry.c, ms: Number(ms.toFixed(2)) });
});

app.get('/trending', (req, res) => {
 const mode = (req.query.mode || 'popularity').toString();
 const list = rank(DATA, '', mode, recency).slice(0, 6).map((e) => ({ q: e.q, c: e.c }));
 res.json({ trending: list, mode, note: mode === 'recency' ? 'recency-weighted score' : 'by all-time search count' });
});

app.get('/recent', (req, res) => res.json({ recent: recentMod.getRecent() }));
app.post('/recent/clear', (req, res) => { recentMod.setRecent([]); res.json({ ok: true }); });

app.get('/cache/debug', (req, res) => {
 const p = (req.query.prefix || '').toString().toLowerCase();
 const node = nodeForPrefix(p);
 res.json({ prefix: p, node, hit: p ? perNode[node].has(p) : false, nodes: ALL_NODES });
});
app.post('/cache/flush', (req, res) => { invalidateAll(); res.json({ ok: true }); });

app.post('/benchmark/batch', (req, res) => {
 const N = Math.max(1, Math.min(10_000, Number(req.query.writes) || 1000));
 const sample = ['iphone', 'iphone 15', 'airpods', 'chatgpt', 'react hooks', 'java tutorial', 'macbook air'];
 const before = totalBatches();
 for (let i = 0; i < N; i++) enqueueIncrement(sample[i % sample.length]);
 flushBatch();
 const after = totalBatches();
 const actual = after - before;
 return res.json({
 simulatedSearches: N,
 actualBatchedWrites: actual,
 writeReductionFactor: Number((N / Math.max(1, actual)).toFixed(2)),
 note: 'Batch coalesces same-query writes within a 2s window into a single store update.',
 });
});

app.get('/telemetry', (req, res) => {
 const total = telem.hits + telem.misses;
 const hitRate = total ? Number(((100 * telem.hits) / total).toFixed(2)) : 0;
 const cacheSize = ALL_NODES.reduce((a, n) => a + perNode[n].size, 0);
 res.json({
 ...telem, hitRate, cacheSize,
 dataSize: DATA.length,
 pendingWrites: pendingWrites(),
 p95SuggestMs: computeP95(),
 });
});

seed();
setInterval(flushBatch, 2000);
setInterval(decayRecency, 5000);

const PORT = process.env.PORT || 3000;
const server = app.listen(PORT, () => console.log(`Search Typeahead running on http://localhost:${PORT}`));

process.on('SIGTERM', () => { flushBatch(); server.close(() => process.exit(0)); });
process.on('SIGINT', () => { flushBatch(); process.exit(0); });