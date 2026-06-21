package com.typeahead;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

@RestController
public class TypeaheadController {

 private static final long CACHE_TTL_MS = 60_000L;

 private final Store store;
 private final Recent recent;
 private final ConsistentHashCache cache;
 private final BatchScheduler scheduler;

 private final AtomicLong hits = new AtomicLong();
 private final AtomicLong misses = new AtomicLong();
 private final java.util.concurrent.ConcurrentSkipListMap<Double, Long> suggestSamples = new java.util.concurrent.ConcurrentSkipListMap<>();
 private final java.util.concurrent.atomic.AtomicLong sampleCount = new java.util.concurrent.atomic.AtomicLong();

 public TypeaheadController(Store store, Recent recent, ConsistentHashCache cache, BatchScheduler scheduler) {
 this.store = store;
 this.recent = recent;
 this.cache = cache;
 this.scheduler = scheduler;
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
 double ms = elapsedMs(started);
 recordSample(ms);
 return Map.of(
 "suggestions", cached.list,
 "node", node,
 "hit", true,
 "ms", ms
 );
 }
 misses.incrementAndGet();
 List<Map.Entry<String, Long>> ranked = Ranker.rank(store.counts, prefix, mode, store.recency());
 List<Suggestion> top = new ArrayList<>();
 int limit = Math.min(10, ranked.size());
 for (int i = 0; i < limit; i++) {
 Map.Entry<String, Long> e = ranked.get(i);
 top.add(new Suggestion(e.getKey(), e.getValue()));
 }
 nodeMap.put(prefix, new ConsistentHashCache.Entry(now, top));
 double ms = elapsedMs(started);
 recordSample(ms);
 return Map.of(
 "suggestions", top,
 "node", node,
 "hit", false,
 "ms", ms
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

 store.enqueueIncrement(q);
 store.flush();

 String key = q.toLowerCase();
 for (String node : ConsistentHashCache.nodes()) {
 Map<String, ConsistentHashCache.Entry> m = cache.perNode().get(node);
 for (String k : new ArrayList<>(m.keySet())) {
 if (key.startsWith(k)) m.remove(k);
 }
 }

 recent.add(q);
 return ResponseEntity.ok(Map.of(
 "message", "Searched",
 "query", q,
 "count", store.counts.getOrDefault(q, 0L),
 "ms", elapsedMs(started)
 ));
 }

 @GetMapping("/trending")
 public Map<String, Object> trending(@RequestParam(name = "mode", defaultValue = "popularity") String mode) {
 List<Map.Entry<String, Long>> ranked = Ranker.rank(store.counts, "", mode, store.recency());
 List<Map<String, Object>> out = new ArrayList<>();
 for (int i = 0; i < Math.min(6, ranked.size()); i++) {
 Map.Entry<String, Long> e = ranked.get(i);
 out.add(Map.of("q", e.getKey(), "c", e.getValue()));
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
 long total = hits.get() + misses.get();
 double hitRate = total == 0 ? 0 : (100.0 * hits.get() / total);
 return Map.of(
 "hits", hits.get(),
 "misses", misses.get(),
 "hitRate", Math.round(hitRate * 100.0) / 100.0,
 "batches", store.totalBatches.get(),
 "batchedOps", store.totalBatchedOps.get(),
 "lastFlushMs", store.lastFlushMs,
 "cacheSize", cacheSize,
 "dataSize", store.counts.size(),
 "pendingWrites", store.pendingWrites(),
 "p95SuggestMs", computeP95()
 );
 }

 @PostMapping("/benchmark/batch")
 public Map<String, Object> benchmark(@RequestParam(name = "writes", defaultValue = "1000") int writesN) {
 int n = Math.max(1, Math.min(10_000, writesN));
 String[] sample = {"iphone", "iphone 15", "airpods", "chatgpt", "react hooks", "java tutorial", "macbook air"};
 long beforeBatches = store.totalBatches.get();
 for (int i = 0; i < n; i++) store.enqueueIncrement(sample[i % sample.length]);
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

 private double elapsedMs(long startNs) {
 return Math.round((System.nanoTime() - startNs) / 10_000.0) / 100.0;
 }

 private void recordSample(double ms) {
 if (suggestSamples.size() < 5000) {
 suggestSamples.put(ms, sampleCount.incrementAndGet());
 }
 }

 private double computeP95() {
 if (suggestSamples.isEmpty()) return 0.0;
 long total = sampleCount.get();
 long target = (long) Math.ceil(total * 0.95);
 long acc = 0;
 for (var e : suggestSamples.entrySet()) {
 acc += e.getValue();
 if (acc >= target) return e.getKey();
 }
 return suggestSamples.lastKey();
 }
}