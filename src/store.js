// Primary store + batched-write buffer.
// In-memory only; counts survive only as long as the process. That meets
// the "store query-count data" requirement without dragging in SQLite.
//
// Batched writes: incoming increments are coalesced in a Map<query,delta>.
// A timer flushes the buffer every BATCH_MS by applying deltas to DATA + countStore.
// This is what the spec calls "batch writes for search-count updates".

const DATA = [
 ['iphone', 100000], ['iphone 15', 85000], ['iphone 15 pro', 52000], ['iphone charger', 60000],
 ['iphone case', 45000], ['ipad', 70000], ['ipad pro', 38000], ['airpods', 66000], ['airpods pro', 41000],
 ['samsung galaxy s24', 58000], ['samsung tv', 33000], ['macbook air', 49000], ['macbook pro', 47000],
 ['nintendo switch 2', 44000], ['sony headphones', 30000],
 ['java tutorial', 40000], ['java jobs', 22000], ['javascript', 75000], ['javascript array methods', 18000],
 ['python tutorial', 62000], ['python pandas', 29000], ['react hooks', 36000], ['react router', 24000],
 ['react native', 31000], ['redis caching', 15000], ['system design interview', 27000],
 ['how to tie a tie', 21000], ['how to screenshot on mac', 33000], ['how to boil eggs', 19000],
 ['how to invest in stocks', 26000],
 ['best laptops 2026', 30000], ['best headphones', 28000], ['best coffee maker', 17000], ['best running shoes', 23000],
 ['taylor swift tickets', 54000], ['world cup schedule', 47000], ['weather today', 88000],
 ['amazon prime day', 51000], ['chatgpt', 73000], ['flight tickets', 35000],
 ['nike air force 1', 39000], ['adidas samba', 25000], ['coffee near me', 34000], ['concert tickets', 28000],
].map(([q, c]) => ({ q, c }));

const countStore = Object.fromEntries(DATA.map((d) => [d.q, d.c]));
const recentActivity = {
 'world cup schedule': 9, 'chatgpt': 6, 'taylor swift tickets': 7,
 'iphone 15': 4, 'best laptops 2026': 5, 'react hooks': 3,
};

const writeBuffer = new Map();
let batchTimer = null;
let pending = 0;

function enqueueIncrement(q) {
 writeBuffer.set(q, (writeBuffer.get(q) || 0) + 1);
 pending += 1;
}

function applyCount(q, c) {
 countStore[q] = c;
}

function flushBatch(_nodeCache, _telem) {
 if (writeBuffer.size === 0) return;
 const started = Date.now();
 const ops = writeBuffer.size;
 for (const [q, delta] of writeBuffer) {
 const entry = DATA.find((d) => d.q === q);
 if (entry) {
 entry.c += delta;
 countStore[q] = entry.c;
 } else {
 DATA.push({ q, c: delta });
 countStore[q] = delta;
 }
 }
 if (_telem) {
 _telem.batches += 1;
 _telem.batchedOps += ops;
 _telem.lastFlushMs = Date.now() - started;
 _telem.writes += 1; // single batched write to "primary store" for telemetry
 }
 writeBuffer.clear();
 pending = 0;
}

function startBatchLoop(nodeCache, telem, batchMs) {
 if (batchTimer) return;
 batchTimer = setInterval(() => flushBatch(nodeCache, telem), batchMs);
}

function stopBatchLoop() {
 if (batchTimer) { clearInterval(batchTimer); batchTimer = null; }
}

function pendingWrites() { return pending; }

module.exports = {
 DATA, countStore, recentActivity,
 enqueueIncrement, applyCount, flushBatch,
 startBatchLoop, stopBatchLoop, pendingWrites,
};
