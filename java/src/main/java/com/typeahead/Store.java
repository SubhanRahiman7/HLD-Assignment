package com.typeahead;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/** Primary store + batched write buffer. */
@Component
public class Store {

 public final List<QueryCount> data = Collections.synchronizedList(new ArrayList<>());
 public final Map<String, Long> countStore = new ConcurrentHashMap<>();
 public final Map<String, Long> recentActivity = new ConcurrentHashMap<>();
 public final List<String> recent = Collections.synchronizedList(new ArrayList<>());

 private final Map<String, Long> writeBuffer = new ConcurrentHashMap<>();
 private final AtomicLong pendingWrites = new AtomicLong(0);

 public final AtomicLong totalBatches = new AtomicLong(0);
 public final AtomicLong totalBatchedOps = new AtomicLong(0);
 public volatile long lastFlushMs = 0;

 @PostConstruct
 public void seed() {
 List<String[]> raw = List.of(
 new String[]{"iphone", "100000"}, new String[]{"iphone 15", "85000"}, new String[]{"iphone 15 pro", "52000"},
 new String[]{"iphone charger", "60000"}, new String[]{"iphone case", "45000"}, new String[]{"ipad", "70000"},
 new String[]{"ipad pro", "38000"}, new String[]{"airpods", "66000"}, new String[]{"airpods pro", "41000"},
 new String[]{"samsung galaxy s24", "58000"}, new String[]{"samsung tv", "33000"}, new String[]{"macbook air", "49000"},
 new String[]{"macbook pro", "47000"}, new String[]{"nintendo switch 2", "44000"}, new String[]{"sony headphones", "30000"},
 new String[]{"java tutorial", "40000"}, new String[]{"java jobs", "22000"}, new String[]{"javascript", "75000"},
 new String[]{"javascript array methods", "18000"}, new String[]{"python tutorial", "62000"}, new String[]{"python pandas", "29000"},
 new String[]{"react hooks", "36000"}, new String[]{"react router", "24000"}, new String[]{"react native", "31000"},
 new String[]{"redis caching", "15000"}, new String[]{"system design interview", "27000"},
 new String[]{"how to tie a tie", "21000"}, new String[]{"how to screenshot on mac", "33000"},
 new String[]{"how to boil eggs", "19000"}, new String[]{"how to invest in stocks", "26000"},
 new String[]{"best laptops 2026", "30000"}, new String[]{"best headphones", "28000"},
 new String[]{"best coffee maker", "17000"}, new String[]{"best running shoes", "23000"},
 new String[]{"taylor swift tickets", "54000"}, new String[]{"world cup schedule", "47000"},
 new String[]{"weather today", "88000"}, new String[]{"amazon prime day", "51000"}, new String[]{"chatgpt", "73000"},
 new String[]{"flight tickets", "35000"}, new String[]{"nike air force 1", "39000"}, new String[]{"adidas samba", "25000"},
 new String[]{"coffee near me", "34000"}, new String[]{"concert tickets", "28000"}
 );
 for (String[] row : raw) {
 String q = row[0];
 long c = Long.parseLong(row[1]);
 data.add(new QueryCount(q, c));
 countStore.put(q, c);
 }
 Map<String, Long> seed = Map.of(
 "world cup schedule", 9L, "chatgpt", 6L, "taylor swift tickets", 7L,
 "iphone 15", 4L, "best laptops 2026", 5L, "react hooks", 3L
 );
 seed.forEach(recentActivity::put);
 }

 /** Enqueue a count increment for the given query. */
 public void enqueueIncrement(String q) {
 synchronized (writeBuffer) {
 writeBuffer.merge(q, 1L, Long::sum);
 pendingWrites.incrementAndGet();
 }
 }

 /** Apply the buffer to the primary store. */
 public void flush() {
 Map<String, Long> snapshot;
 synchronized (writeBuffer) {
 if (writeBuffer.isEmpty()) return;
 snapshot = new HashMap<>(writeBuffer);
 writeBuffer.clear();
 pendingWrites.set(0);
 }
 long t0 = System.currentTimeMillis();
 int ops = 0;
 for (var e : snapshot.entrySet()) {
 String q = e.getKey();
 long delta = e.getValue();
 QueryCount found = null;
 synchronized (data) {
 for (QueryCount d : data) if (d.q().equals(q)) { found = d; break; }
 if (found != null) {
 found.increment(delta);
 countStore.put(q, found.c());
 } else {
 data.add(new QueryCount(q, delta));
 countStore.put(q, delta);
 }
 }
 ops++;
 }
 totalBatches.incrementAndGet();
 totalBatchedOps.addAndGet(ops);
 lastFlushMs = System.currentTimeMillis() - t0;
 }

 public long pendingWrites() { return pendingWrites.get(); }
}
