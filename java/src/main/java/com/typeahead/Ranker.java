package com.typeahead;

import java.util.*;

/**
 * Ranking:
 * - popularity: sort by all-time count desc
 * - recency: sort by count + weighted sum of recency buckets (5m > 1h > 24h)
 *
 * Recency buckets: [last5m, last1h, last24h], maintained by Store.decayRecency().
 *
 * Weights are tuned so 5-min activity dominates 1-h, which dominates 24-h.
 * Tiebreak: alphabetical.
 */
public class Ranker {

 /** Base weight per recent unit. One 5m event = RECENCY_WEIGHT boost. */
 public static final long RECENCY_WEIGHT = 30_000L;

 /** Bucket multipliers: 5m > 1h > 24h. */
 private static final long WEIGHT_5M = 5L;
 private static final long WEIGHT_1H = 1L;
 private static final long WEIGHT_24H = 1L / 10L;

 public static List<Map.Entry<String, Long>> rank(Map<String, Long> counts, String prefix, String mode, Map<String, long[]> recency) {
 String p = prefix == null ? "" : prefix.toLowerCase();
 List<Map.Entry<String, Long>> matches = new ArrayList<>();
 for (var e : counts.entrySet()) {
 if (p.isEmpty() || e.getKey().toLowerCase().startsWith(p)) matches.add(e);
 }
 matches.sort((a, b) -> {
 long sa = score(a.getValue(), a.getKey(), mode, recency);
 long sb = score(b.getValue(), b.getKey(), mode, recency);
 int cmp = Long.compare(sb, sa);
 return cmp != 0 ? cmp : a.getKey().compareTo(b.getKey());
 });
 return matches;
 }

 private static long score(long c, String q, String mode, Map<String, long[]> recency) {
 if ("recency".equals(mode)) {
 long[] b = recency.get(q);
 if (b == null) return c;
 // Weighted sum: 5m contributes most, 24h least.
 long recencyScore = b[Store.BUCKET_5M] * WEIGHT_5M
 + b[Store.BUCKET_1H] * WEIGHT_1H
 + b[Store.BUCKET_24H] * WEIGHT_24H;
 return c + recencyScore * RECENCY_WEIGHT;
 }
 return c;
 }
}