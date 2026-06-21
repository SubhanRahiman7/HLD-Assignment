// Search Typeahead backend
// - In-memory primary store (query -> count)
// - 4-node consistent-hash cache for suggestion results
// - Batched async writes via write buffer
// - Trending ranking: all-time count OR recency-weighted (per-recent enhancement)

const express = require('express');
const path = require('path');
const {
 DATA,
 recentActivity,
 countStore,
 flushBatch,
  startBatchLoop,
 stopBatchLoop,
 enqueueIncrement,
 applyCount,
} = require('./src/store');
const { nodeForPrefix, ALL_NODES } = require('./src/cache');
const { rank } = require('./src/ranking');
const recentMod = require('./src/recent');

const app = express();
app.use(express.json());
app.use(express.static(path.join(__dirname, 'public')));

// ---- Suggestion cache (per-node) ----
const nodeCache = Object.fromEntries(ALL_NODES.map((n) => [n, new Map()]));
const CACHE_TTL_MS = 60_000;

// ---- Telemetry ----
const telem = { hits: 0, misses: 0, writes: 0, batches: 0, batchedOps: 0, lastFlushMs: 0 };

// ---- GET /suggest?q=<prefix> ----
app.get('/suggest', (req, res) => {
 const raw = (req.query.q ?? '').toString();
 const prefix = raw.trim().toLowerCase();
 if (!prefix) return res.json({ suggestions: [], node: null, hit: false, ms: 0 });

 const started = process.hrtime.bigint();
 const node = nodeForPrefix(prefix);
 const cache = nodeCache[node];
 const cached = cache.get(prefix);
 const now = Date.now();

 if (cached && now - cached.at < CACHE_TTL_MS) {
 telem.hits += 1;
 const ms = Number(process.hrtime.bigint() - started) / 1e6;
 return res.json({
 suggestions: cached.list,
 node,
 hit: true,
 ms: Number(ms.toFixed(2)),
 });
 }

 telem.misses += 1;
 const mode = (req.query.mode || 'popularity').toString();
 const list = rank(DATA, prefix, mode, recentActivity).slice(0, 10).map((d) => ({
 q: d.q,
 c: d.c,
 }));
 cache.set(prefix, { list, at: now });
 const ms = Number(process.hrtime.bigint() - started) / 1e6;
 res.json({
 suggestions: list,
 node,
 hit: false,
 ms: Number(ms.toFixed(2)),
 });
});

// ---- POST /search ----
app.post('/search', (req, res) => {
 const q = (req.body?.q ?? '').toString().trim();
 if (!q) return res.status(400).json({ error: 'q required' });

 if (q.toLowerCase() === 'error') {
 return res.status(503).json({ message: 'Upstream search service did not respond within the latency budget.' });
 }

 const started = process.hrtime.bigint();
 const key = q.toLowerCase();
 let entry = DATA.find((d) => d.q.toLowerCase() === key);
 if (entry) {
 enqueueIncrement(entry.q);
 } else {
 DATA.push({ q, c: 1 });
 applyCount(q, 1);
 entry = { q, c: 1 };
 }
 recentActivity[q] = (recentActivity[q] || 0) + 1;
 // Pre-flush so the response reflects the new count, while still keeping the
 // batch mechanism for high-throughput paths (it auto-merges subsequent writes).
 flushBatch();

 // Invalidate suggestion cache for prefixes that this query affects.
 // We narrow it to prefixes that match the updated query (its own prefix family),
 // since counts only affect rankings of matching suggestions.
 for (const n of ALL_NODES) {
 for (const k of Array.from(nodeCache[n].keys())) {
 if (entry.q.toLowerCase().startsWith(k)) {
 nodeCache[n].delete(k);
 }
 }
 }

 // Update recent
 recentMod.addRecent(q);

 const ms = Number(process.hrtime.bigint() - started) / 1e6;
 res.json({
 message: 'Searched',
 query: entry.q,
 count: countStore[entry.q] ?? entry.c,
 ms: Number(ms.toFixed(2)),
 });
});

// ---- GET /trending ----
app.get('/trending', (req, res) => {
 const mode = (req.query.mode || 'popularity').toString();
 const list = rank(DATA, '', mode, recentActivity).slice(0, 6);
 res.json({ trending: list, mode, note: mode === 'recency' ? 'recency-weighted score' : 'by all-time search count' });
});

// ---- GET /recent ----
app.get('/recent', (req, res) => {
 res.json({ recent: recentMod.getRecent() });
});

// ---- POST /recent/clear ----
app.post('/recent/clear', (req, res) => {
 recentMod.setRecent([]);
 res.json({ ok: true });
});

// ---- GET /cache/debug?prefix=... ----
app.get('/cache/debug', (req, res) => {
 const p = (req.query.prefix || '').toString().toLowerCase();
 const node = nodeForPrefix(p);
 res.json({
 prefix: p,
 node,
 hit: p ? nodeCache[node].has(p) : false,
 nodes: ALL_NODES,
 });
});

// ---- POST /benchmark/batch?writes=N ----
// Simulates N search-count writes back-to-back and reports how many actual
// batched DB writes were performed vs the naive 1-write-per-search baseline.
app.post('/benchmark/batch', (req, res) => {
 const N = Math.max(1, Math.min(10000, Number(req.query.writes) || 1000));
 const sample = ['iphone', 'iphone 15', 'airpods', 'chatgpt', 'react hooks', 'java tutorial', 'macbook air'];
 for (let i = 0; i < N; i++) {
 const q = sample[i % sample.length];
 enqueueIncrement(q);
 }
 // Wait one batch interval to let it flush naturally
 setTimeout(() => {
 res.json({
 simulatedSearches: N,
 actualBatchedWrites: telem.batches,
 writeReductionFactor: Number((N / Math.max(1, telem.batches)).toFixed(2)),
 note: 'Batch coalesces same-query writes within a 2s window into a single store update.',
 });
 }, 2100);
});

// ---- GET /telemetry ----
app.get('/telemetry', (req, res) => {
 res.json({
 ...telem,
 cacheSize: ALL_NODES.reduce((a, n) => a + nodeCache[n].size, 0),
 dataSize: DATA.length,
 pendingWrites: require('./src/store').pendingWrites(),
 });
});

// ---- Flush cache ----
app.post('/cache/flush', (req, res) => {
 for (const n of ALL_NODES) nodeCache[n].clear();
 res.json({ ok: true });
});

startBatchLoop(nodeCache, telem, 2000);

const PORT = process.env.PORT || 3000;
const server = app.listen(PORT, () => {
 console.log(`Search Typeahead running on http://localhost:${PORT}`);
});

process.on('SIGTERM', () => {
 flushBatch(nodeCache, telem);
 stopBatchLoop();
 server.close(() => process.exit(0));
});
process.on('SIGINT', () => {
 flushBatch(nodeCache, telem);
 stopBatchLoop();
 process.exit(0);
});
