package com.typeahead;

import java.util.*;

/** Popularity vs recency-weighted ranking. */
public class Ranker {
 public static final long RECENCY_WEIGHT = 30_000L;

 public static List<QueryCount> rank(List<QueryCount> data, String prefix, String mode, Map<String, Long> recentActivity) {
 String p = prefix == null ? "" : prefix.toLowerCase();
 List<QueryCount> matches = new ArrayList<>();
 for (QueryCount d : data) {
 if (p.isEmpty() || d.q().toLowerCase().startsWith(p)) matches.add(d);
 }
 matches.sort((a, b) -> {
 long sa = score(a, mode, recentActivity);
 long sb = score(b, mode, recentActivity);
 int cmp = Long.compare(sb, sa);
 return cmp != 0 ? cmp : a.q().compareTo(b.q());
 });
 return matches;
 }

 private static long score(QueryCount d, String mode, Map<String, Long> recent) {
 if ("recency".equals(mode)) {
 return d.c() + recent.getOrDefault(d.q(), 0L) * RECENCY_WEIGHT;
 }
 return d.c();
 }
}
