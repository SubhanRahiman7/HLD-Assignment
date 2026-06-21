package com.typeahead;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class TypeaheadController {

 private final Store store;
 private final Recent recent;
 private final ConsistentHashCache cache;
 private static final long CACHE_TTL_MS = 60_000L;

 private final AtomicLong hits = new AtomicLong();
 private final AtomicLong misses = new AtomicLong();
 private final AtomicLong writes = new AtomicLong();

 public TypeaheadController(Store store, Recent recent, ConsistentHashCache cache) {
 this.store = store;
 this.recent = recent;
 this.cache = cache;
 }

 public static String fmt(long n) {
 if (n >= 1_000_000) {
 double v = n / 1_000_000.0;
 String s = String.format("%.1f", v);
 if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
 return s + "M";
 }
 if (n >= 1_000) return Math.round(n / 1_000.0) + "K";
 return String.valueOf(n);
 }

 private int rndInt(int a, int b) {
 return a + (int) (Math.random() * (b - a + 1));
 }

 private double elapsedMs(long startNs) {
 return Math.round((System.nanoTime() - startNs) / 10_000.0) / 100.0;
 }

 @GetMapping("/suggest")
 public Map<String, Object> suggest(@RequestParam(name = "q", required = false) String q,
 @RequestParam(name = "mode", defaultValue = "popularity") String mode) {
 String prefix = (q == null ? "" : q.trim().toLowerCase());
 long started = System.nanoTime();
 if (prefix.isEmpty()) {
 return Map.of("suggestions", List.of(), "node", "", "hit", false, "ms", 0.0);
 }
 String node = cache.nodeFor(prefix);
 Map<String, ConsistentHashCache.Entry> nodeMap = cache.perNode().get(node);
 ConsistentHashCache.Entry cached = nodeMap.get(prefix);
 long now = System.currentTimeMillis();
 if (cached != null && (now - cached.at) < CACHE_TTL_MS) {
 hits.incrementAndGet();
 return Map.of(
 "suggestions", cached.list,
 "node", node,
 "hit", true,
 "ms", elapsedMs(started)
 );
 }
 misses.incrementAndGet();
 List<QueryCount> ranked = Ranker.rank(store.data, prefix, mode, store.recentActivity);
 List<Suggestion> top = new ArrayList<>();
 int limit = Math.min(10, ranked.size());
 for (int i = 0; i < limit; i++) {
 QueryCount d = ranked.get(i);
 top.add(new Suggestion(d.q(), d.c()));
 }
 nodeMap.put(prefix, new ConsistentHashCache.Entry(now, top));
 return Map.of(
 "suggestions", top,
 "node", node,
 "hit", false,
 "ms", elapsedMs(started)
 );
 }

 @PostMapping("/search")
 public ResponseEntity<?> search(@RequestBody Map<String, String> body) {
 String q = body.getOrDefault("q", "").trim();
 if (q.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "q required"));
 if (q.equalsIgnoreCase("error")) {
 return ResponseEntity.status(503).body(Map.of(
 "message", "Upstream search service did not respond within the latency budget."
 ));
 }
 long started = System.nanoTime();
 String key = q.toLowerCase();
 QueryCount entry = null;
 synchronized (store.data) {
 for (QueryCount d : store.data) if (d.q().toLowerCase().equals(key)) { entry = d; break; }
 }
 if (entry != null) {
 store.enqueueIncrement(entry.q());
 } else {
 synchronized (store.data) { store.data.add(new QueryCount(q, 1)); }
 store.countStore.put(q, 1L);
 entry = new QueryCount(q, 1);
 }
 store.recentActivity.merge(q, 1L, Long::sum);
 // Pre-flush so the response shows the new count.
 store.flush();
 writes.incrementAndGet();
 // Narrow cache invalidation: only prefixes that this query extends.
 for (String node : ConsistentHashCache.nodes()) {
 Map<String, ConsistentHashCache.Entry> m = cache.perNode().get(node);
 for (String k : new ArrayList<>(m.keySet())) {
 if (entry.q().toLowerCase().startsWith(k)) m.remove(k);
 }
 }
 recent.add(q);
 return ResponseEntity.ok(Map.of(
 "message", "Searched",
 "query", entry.q(),
 "count", store.countStore.getOrDefault(entry.q(), entry.c()),
 "ms", elapsedMs(started)
 ));
 }

 @GetMapping("/trending")
 public Map<String, Object> trending(@RequestParam(name = "mode", defaultValue = "popularity") String mode) {
 List<QueryCount> ranked = Ranker.rank(store.data, "", mode, store.recentActivity);
 List<Map<String, Object>> out = new ArrayList<>();
 for (int i = 0; i < Math.min(6, ranked.size()); i++) {
 QueryCount d = ranked.get(i);
 out.add(Map.of("q", d.q(), "c", d.c()));
 }
 return Map.of(
 "trending", out,
 "mode", mode,
 "note", "recency".equals(mode) ? "recency-weighted score" : "by all-time search count"
 );
 }

 @GetMapping("/recent")
 public Map<String, Object> recentEndpoint() { return Map.of("recent", recent.get()); }

 @PostMapping("/recent/clear")
 public Map<String, Object> clearRecent() { recent.clear(); return Map.of("ok", true); }

 @GetMapping("/cache/debug")
 public Map<String, Object> cacheDebug(@RequestParam(name = "prefix", defaultValue = "") String prefix) {
 String p = prefix.toLowerCase();
 return Map.of(
 "prefix", p,
 "node", cache.nodeFor(p),
 "hit", prefix.isEmpty() ? false : cache.debugHas(p),
 "nodes", ConsistentHashCache.nodes()
 );
 }

 @PostMapping("/cache/flush")
 public Map<String, Object> flushCache() { cache.invalidateAll(); return Map.of("ok", true); }

 @GetMapping("/telemetry")
 public Map<String, Object> telemetry() {
 int cacheSize = cache.perNode().values().stream().mapToInt(Map::size).sum();
 return Map.of(
 "hits", hits.get(),
 "misses", misses.get(),
 "writes", writes.get(),
 "batches", store.totalBatches.get(),
 "batchedOps", store.totalBatchedOps.get(),
 "lastFlushMs", store.lastFlushMs,
 "cacheSize", cacheSize,
 "dataSize", store.data.size(),
 "pendingWrites", store.pendingWrites()
 );
 }

 @PostMapping("/benchmark/batch")
 public Map<String, Object> benchmark(@RequestParam(name = "writes", defaultValue = "1000") int writesN) {
 int n = Math.max(1, Math.min(10_000, writesN));
 String[] sample = {"iphone", "iphone 15", "airpods", "chatgpt", "react hooks", "java tutorial", "macbook air"};
 long beforeBatches = store.totalBatches.get();
 for (int i = 0; i < n; i++) store.enqueueIncrement(sample[i % sample.length]);
 // Force a flush right after for deterministic counts.
 store.flush();
 long afterBatches = store.totalBatches.get();
 long actualBatchedWrites = afterBatches - beforeBatches;
 double reduction = actualBatchedWrites == 0 ? 0 : Math.round(n * 100.0 / actualBatchedWrites) / 100.0;
 return Map.of(
 "simulatedSearches", n,
 "actualBatchedWrites", actualBatchedWrites,
 "writeReductionFactor", reduction,
 "note", "Batch coalesces same-query writes within a 2s window into a single store update."
 );
 }
}
