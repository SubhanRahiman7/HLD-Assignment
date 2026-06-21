// Consistent-hash cache: real ring with FNV-1a and virtual nodes.
const NODES = ['cache-a', 'cache-b', 'cache-c', 'cache-d'];
const VIRTUAL_NODES = 200;

const ring = new Map(); // Long hash -> node
const perNode = Object.fromEntries(NODES.map(n => [n, new Map()]));

function fnv1a(s) {
 let h = 0x811c9dc5;
 for (let i = 0; i < s.length; ++i) {
 h ^= s.charCodeAt(i);
 h += (h << 1) + (h << 4) + (h << 7) + (h << 8) + (h << 24);
 h &= 0xffffffff;
 }
 return h >>> 0;
}

// Populate ring with virtual nodes.
for (const n of NODES) {
 for (let i = 0; i < VIRTUAL_NODES; ++i) {
 ring.set(fnv1a(`${n}#${i}`), n);
 }
}

// Convert ring to sorted entries for binary search.
const sortedRing = Array.from(ring.entries()).sort((a, b) => a[0] - b[0]);

function nodeForPrefix(prefix) {
 const p = prefix ?? '';
 if (!p) return NODES[0];
 const h = fnv1a(p);
 // Find first entry >= h, else wrap to first.
 let lo = 0, hi = sortedRing.length;
 while (lo < hi) {
 const mid = Math.floor((lo + hi) / 2);
 if (sortedRing[mid][0] < h) lo = mid + 1;
 else hi = mid;
 }
 const entry = sortedRing[lo] ?? sortedRing[0];
 return entry[1];
}

function getAllNodes() { return NODES; }

function invalidateAll() {
 for (const n of NODES) perNode[n].clear();
}

function debugHas(prefix) { return perNode[nodeForPrefix(prefix)].has(prefix); }

module.exports = {
 nodeForPrefix,
 getAllNodes,
 ALL_NODES: NODES,
 perNode,
 invalidateAll,
 debugHas,
};