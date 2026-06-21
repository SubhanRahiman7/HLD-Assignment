package com.typeahead;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * Primary store.
 *
 * Two maps:
 * - counts: query -> all-time count (loaded from real Kaggle-derived files in data/).
 * - recency: query -> bucket counts [last5m, last1h, last24h], decayed by RecencyDecay job.
 *
 * Sources merged into counts (frequency aggregation, case-insensitive):
 * - data/aol_queries.tsv : AOL search log (id<TAB>query)
 * - data/product_queries.csv : Amazon ESCI product search queries
 * - data/trends.csv : Google Trends top queries (rank-based recency seed)
 *
 * Writes:
 * - /search calls enqueueIncrement, increments writeBuffer.
 * - flush() (every 2s + on /search) drains writeBuffer into counts.
 * - recency window is bumped every /search and re-weighted by decay job.
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

 @PostConstruct
 public void seed() {
 loadDataset();
 }

 /**
 * Loads query counts from the three real datasets in data/ by aggregating
 * frequency of each unique (lowercased) query text. AOL TSV (id<TAB>query)
 * and Amazon ESCI CSV (query,product_id,...) contribute raw occurrence
 * counts; Google Trends CSV (location,year,category,rank,query) seeds the
 * recency buckets instead because its "count" is a rank, not a frequency.
 */
 private void loadDataset() {
 String dataDir = System.getenv().getOrDefault("DATA_DIR", "data");
 Map<String, Long> aol = aggregateOccurrences(dataDir + "/aol_queries.tsv", true, 1);
 Map<String, Long> products = aggregateTsvCounts(dataDir + "/product_queries_freq.tsv");
 System.out.println("Loaded aol=" + aol.size() + " products=" + products.size());
 // Union: counts = freq(aol) + freq(products). Cap at 200k most frequent.
 Map<String, Long> merged = new HashMap<>(aol);
 for (var e : products.entrySet()) merged.merge(e.getKey(), e.getValue(), Long::sum);
 merged.entrySet().stream()
 .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
 .limit(200_000)
 .forEach(e -> counts.put(e.getKey(), e.getValue()));
 System.out.println("Loaded dataset: " + counts.size() + " unique queries");
 seedRecencyFromTrends(dataDir + "/trends.csv");
 }

 private Map<String, Long> aggregateTsvCounts(String path) {
 // Format: query<TAB>count. Counts are absolute frequencies (pre-aggregated).
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

 /** Called on every /search. Coalesces same-query writes inside the 2s window. */
 public void enqueueIncrement(String q) {
 writeBuffer.merge(q, 1L, Long::sum);
 pendingWrites.incrementAndGet();
 bumpRecency(q);
 }

 private void bumpRecency(String q) {
 recency.compute(q, (k, v) -> {
 if (v == null) return new long[]{1, 1, 1};
 v[BUCKET_5M]++;
 v[BUCKET_1H]++;
 v[BUCKET_24H]++;
 return v;
 });
 }

 /** Apply buffered deltas to the primary store. Called by BatchScheduler and on /search. */
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
 }

 /** Decay oldest bucket, shift 1h -> 24h, shift 5m -> 1h, drop oldest. */
 public void decayRecency() {
 for (var e : recency.entrySet()) {
 long[] b = e.getValue();
 b[BUCKET_5M] = (long) (b[BUCKET_5M] * 0.85);
 b[BUCKET_1H] = (long) (b[BUCKET_1H] * 0.95);
 b[BUCKET_24H] = (long) (b[BUCKET_24H] * 0.99);
 if (b[BUCKET_5M] + b[BUCKET_1H] + b[BUCKET_24H] < 1) recency.remove(e.getKey());
 }
 }

 public long pendingWrites() { return pendingWrites.get(); }
 public long totalBatches() { return totalBatches.get(); }
 public long totalBatchedOps() { return totalBatchedOps.get(); }
 public long lastFlushMs() { return lastFlushMs; }
}