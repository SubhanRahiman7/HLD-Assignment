package com.typeahead;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Primary store with optional Postgres + Redis backing.
 *
 * Two maps:
 * - counts: query -> all-time count (Postgres when available, in-memory Map fallback).
 * - recency: query -> bucket counts [last5m, last1h, last24h] (Redis cache + in-memory mirror).
 *
 * Sources merged into counts (frequency aggregation, case-insensitive):
 * - data/aol_queries.tsv : AOL search log (id<TAB>query)
 * - data/product_queries_freq.tsv : Amazon ESCI product search queries (pre-aggregated)
 * - data/trends.csv : Google Trends top queries (rank-based recency seed)
 *
 * Persistence wiring (toggleable by env vars):
 * - PG_URL set → write-through to Postgres via QueryCountRepository.
 * - REDIS_URL set → cache + recent moved into Redis.
 * Either absent → fall back to in-memory (legacy behaviour).
 */
@Component
public class Store {

 public final Map<String, Long> counts = new ConcurrentHashMap<>();

 private final Map<String, long[]> recency = new ConcurrentHashMap<>();
 public static final int BUCKET_5M = 0;
 public static final int BUCKET_1H = 1;
 public static final int BUCKET_24H = 2;

 private final Map<String, Long> writeBuffer = new ConcurrentHashMap<>();
 private final AtomicLong pendingWrites = new AtomicLong();
 private final AtomicLong totalBatches = new AtomicLong();
 private final AtomicLong totalBatchedOps = new AtomicLong();
 private volatile long lastFlushMs = 0;

 @Autowired(required = false)
 private QueryCountRepository repository;

 @Autowired(required = false)
 @org.springframework.beans.factory.annotation.Qualifier("redisTemplate")
 private RedisTemplate<String, String> redis;

 @Value("${spring.data.redis.url:}")
 private String redisUrl;

 private boolean postgresEnabled() { return repository != null; }
 private boolean redisEnabled() { return redis != null && redisUrl != null && !redisUrl.isBlank(); }

 @PostConstruct
 public void seed() {
 loadDataset();
 // Hydrate in-memory from Postgres if enabled, so restarts pick up state.
 if (postgresEnabled()) {
 try {
 repository.findAll().forEach(e -> counts.put(e.getQuery(), e.getCount()));
 System.out.println("Hydrated " + counts.size() + " rows from Postgres");
 } catch (DataAccessException ex) {
 System.err.println("Postgres hydrate failed, in-memory only: " + ex);
 }
 }
 }

 /**
 * Loads query counts from real datasets in data/.
 */
 private void loadDataset() {
 String dataDir = System.getenv().getOrDefault("DATA_DIR", "data");
 Map<String, Long> aol = aggregateOccurrences(dataDir + "/aol_queries.tsv", true, 1);
 Map<String, Long> products = aggregateTsvCounts(dataDir + "/product_queries_freq.tsv");
 System.out.println("Loaded aol=" + aol.size() + " products=" + products.size());
 Map<String, Long> merged = new HashMap<>(aol);
 for (var e : products.entrySet()) merged.merge(e.getKey(), e.getValue(), Long::sum);
 merged.entrySet().stream()
 .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
 .limit(200_000)
 .forEach(e -> counts.put(e.getKey(), e.getValue()));
 System.out.println("Loaded dataset: " + counts.size() + " unique queries");
 seedRecencyFromTrends(dataDir + "/trends.csv");
 if (postgresEnabled()) persistInitial(merged);
 }

 private void persistInitial(Map<String, Long> merged) {
 try {
 int saved = 0;
 for (var e : merged.entrySet()) {
 if (repository.findByQuery(e.getKey()).isEmpty()) {
 repository.save(new QueryCountEntity(e.getKey(), e.getValue()));
 saved++;
 if (saved % 5000 == 0) repository.flush();
 }
 }
 System.out.println("Persisted " + saved + " rows to Postgres");
 } catch (DataAccessException ex) {
 System.err.println("Postgres persist failed: " + ex);
 }
 }

 private Map<String, Long> aggregateOccurrences(String path, boolean tab, int queryCol) {
 Map<String, Long> out = new HashMap<>();
 java.io.File f = new java.io.File(path);
 if (!f.exists()) return out;
 String delim = tab ? "\\t" : ",";
 try (BufferedReader br = new BufferedReader(new FileReader(f))) {
 String line;
 boolean first = true;
 while ((line = br.readLine()) != null) {
 if (first) { first = false; continue; }
 if (line.isBlank()) continue;
 String[] parts = line.split(delim, -1);
 if (parts.length <= queryCol) continue;
 String q = parts[queryCol].trim().toLowerCase().replaceAll("^\"|\"$", "");
 if (q.length() < 2) continue;
 out.merge(q, 1L, Long::sum);
 }
 } catch (Exception e) {
 System.err.println("Load failed " + path + ": " + e);
 }
 return out;
 }

 private Map<String, Long> aggregateTsvCounts(String path) {
 Map<String, Long> out = new HashMap<>();
 java.io.File f = new java.io.File(path);
 if (!f.exists()) return out;
 try (BufferedReader br = new BufferedReader(new FileReader(f))) {
 String line;
 while ((line = br.readLine()) != null) {
 if (line.isBlank()) continue;
 String[] parts = line.split("\t", -1);
 if (parts.length < 2) continue;
 String q = parts[0].trim().toLowerCase();
 long n;
 try { n = Long.parseLong(parts[1].trim()); } catch (Exception ex) { continue; }
 if (q.length() < 2) continue;
 out.merge(q, n, Long::sum);
 }
 } catch (Exception e) {
 System.err.println("Load failed " + path + ": " + e);
 }
 return out;
 }

 private void seedRecencyFromTrends(String path) {
 java.io.File f = new java.io.File(path);
 if (!f.exists()) return;
 Map<String, Integer> best = new HashMap<>();
 try (BufferedReader br = new BufferedReader(new FileReader(f))) {
 String line;
 boolean first = true;
 while ((line = br.readLine()) != null) {
 if (first) { first = false; continue; }
 String[] parts = line.split(",", -1);
 if (parts.length < 5) continue;
 int rank;
 try { rank = Integer.parseInt(parts[3].trim()); } catch (Exception ex) { continue; }
 String q = parts[4].trim().toLowerCase();
 if (q.isEmpty()) continue;
 int score = Math.max(0, 30 - rank);
 if (score > best.getOrDefault(q, 0)) best.put(q, score);
 }
 } catch (Exception e) {
 System.err.println("Trends load failed: " + e);
 }
 for (var e : best.entrySet()) {
 int s = e.getValue();
 recency.put(e.getKey(), new long[]{s, s + 2, s + 4});
 }
 }

 public Map<String, long[]> recency() { return recency; }

 public void enqueueIncrement(String q) {
 writeBuffer.merge(q, 1L, Long::sum);
 pendingWrites.incrementAndGet();
 bumpRecency(q);
 if (redisEnabled()) {
 try { redis.opsForValue().increment("count:" + q); } catch (Exception ignore) {}
 }
 }

 private void bumpRecency(String q) {
 recency.compute(q, (k, v) -> {
 if (v == null) return new long[]{1, 1, 1};
 v[BUCKET_5M]++;
 v[BUCKET_1H]++;
 v[BUCKET_24H]++;
 return v;
 });
 if (redisEnabled()) {
 try { redis.opsForHash().increment("recency:" + q, "5m", 1); redis.opsForHash().increment("recency:" + q, "1h", 1); redis.opsForHash().increment("recency:" + q, "24h", 1); } catch (Exception ignore) {}
 }
 }

 public void flush() {
 if (writeBuffer.isEmpty()) return;
 Map<String, Long> snapshot = new HashMap<>(writeBuffer);
 writeBuffer.clear();
 pendingWrites.set(0);
 long t0 = System.currentTimeMillis();
 for (var e : snapshot.entrySet()) counts.merge(e.getKey(), e.getValue(), Long::sum);
 totalBatches.incrementAndGet();
 totalBatchedOps.addAndGet(snapshot.size());
 lastFlushMs = System.currentTimeMillis() - t0;
 if (postgresEnabled()) {
 try {
 for (var e : snapshot.entrySet()) {
 Optional<QueryCountEntity> existing = repository.findByQuery(e.getKey());
 if (existing.isPresent()) {
 QueryCountEntity row = existing.get();
 row.setCount(row.getCount() + e.getValue());
 repository.save(row);
 } else {
 repository.save(new QueryCountEntity(e.getKey(), e.getValue()));
 }
 }
 } catch (DataAccessException ex) {
 System.err.println("Postgres flush failed: " + ex);
 }
 }
 }

 public void decayRecency() {
 for (var e : recency.entrySet()) {
 long[] b = e.getValue();
 b[BUCKET_5M] = (long) (b[BUCKET_5M] * 0.85);
 b[BUCKET_1H] = (long) (b[BUCKET_1H] * 0.95);
 b[BUCKET_24H] = (long) (b[BUCKET_24H] * 0.99);
 if (b[BUCKET_5M] + b[BUCKET_1H] + b[BUCKET_24H] < 1) recency.remove(e.getKey());
 }
 }

 /** Top-N queries by count, optionally prefix-filtered. Postgres when enabled, else in-memory. */
 public List<Map.Entry<String, Long>> topByPrefix(String prefix, int limit) {
 if (postgresEnabled()) {
 try {
 var rows = repository.findPrefixTop(prefix, PageRequest.of(0, limit));
 return rows.stream().map(r -> Map.entry(r.getQuery(), r.getCount())).toList();
 } catch (DataAccessException ex) {
 System.err.println("Postgres query failed: " + ex);
 }
 }
 String pfx = prefix.toLowerCase();
 return counts.entrySet().stream()
 .filter(e -> e.getKey().startsWith(pfx))
 .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
 .limit(limit)
 .toList();
 }

 /** Top-N queries by count, no prefix. */
 public List<Map.Entry<String, Long>> topAll(int limit) {
 if (postgresEnabled()) {
 try {
 var rows = repository.findTopAll(PageRequest.of(0, limit));
 return rows.stream().map(r -> Map.entry(r.getQuery(), r.getCount())).toList();
 } catch (DataAccessException ex) {
 System.err.println("Postgres query failed: " + ex);
 }
 }
 return counts.entrySet().stream()
 .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
 .limit(limit)
 .toList();
 }

 public boolean isPostgresEnabled() { return postgresEnabled(); }
 public boolean isRedisEnabled() { return redisEnabled(); }

 public long pendingWrites() { return pendingWrites.get(); }
 public long totalBatches() { return totalBatches.get(); }
 public long totalBatchedOps() { return totalBatchedOps.get(); }
 public long lastFlushMs() { return lastFlushMs; }
}