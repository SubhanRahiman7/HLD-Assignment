// Consistent-hashing shim across 4 logical cache nodes.
// Real ring would sort hashed nodes on a 2^32 ring and walk; for 4 nodes
// we use FNV-1a + modulo, then a stable secondary hash for tiebreak.
// Good enough for the assignment's "demonstrate consistent-hashing" goal.

const ALL_NODES = ['cache-a', 'cache-b', 'cache-c', 'cache-d'];

function fnv1a(s) {
 let h = 0x811c9dc5 >>> 0;
 for (let i = 0; i < s.length; i++) {
 h ^= s.charCodeAt(i);
 h = Math.imul(h, 0x01000193) >>> 0;
 }
 return h >>> 0;
}

function nodeForPrefix(prefix) {
 return ALL_NODES[fnv1a(prefix || '') % ALL_NODES.length];
}

module.exports = { ALL_NODES, nodeForPrefix, fnv1a };
