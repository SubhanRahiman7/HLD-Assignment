// Node ranker: matches Java Ranker — popularity / recency with bucket weights.
const RECENCY_WEIGHT = 30_000;
const W5 = 5, W1 = 1, W24 = 0.1;

function rank(DATA, prefix, mode, recencyMap) {
 const p = (prefix ?? '').toLowerCase();
 const matches = [];
 for (const e of DATA) {
 if (!p || e.q.toLowerCase().startsWith(p)) matches.push(e);
 }
 matches.sort((a, b) => {
 const sa = score(a, mode, recencyMap);
 const sb = score(b, mode, recencyMap);
 return sb - sa || a.q.localeCompare(b.q);
 });
 return matches;
}

function score(e, mode, recencyMap) {
 if (mode === 'recency') {
 const b = recencyMap.get(e.q.toLowerCase());
 if (!b) return e.c;
 const r = b[0] * W5 + b[1] * W1 + b[2] * W24;
 return e.c + r * RECENCY_WEIGHT;
 }
 return e.c;
}

module.exports = { rank };