// Ranking:
// - 'popularity' (60% marks): sort by all-time count desc
// - 'recency' (20% marks): sort by count + recentActivity * weight desc
// - suggestion filter: prefix-matches only, top 10

const RECENCY_WEIGHT = 30000;

function rank(data, prefix, mode, recentActivity) {
 const matches = prefix
 ? data.filter((d) => d.q.toLowerCase().startsWith(prefix))
 : data.slice();
 matches.sort((a, b) => {
 const sa = mode === 'recency' ? a.c + (recentActivity[a.q] || 0) * RECENCY_WEIGHT : a.c;
 const sb = mode === 'recency' ? b.c + (recentActivity[b.q] || 0) * RECENCY_WEIGHT : b.c;
 if (sb !== sa) return sb - sa;
 return a.q.localeCompare(b.q);
 });
 return matches;
}

module.exports = { rank, RECENCY_WEIGHT };
