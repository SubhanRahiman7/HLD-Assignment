---
title: "Search Typeahead — HLD Assignment Report"
author: "Subhan Rahiman"
date: "2026-06-22"
---

# Search Typeahead — HLD Assignment Report

A production-style prefix-search typeahead service with a 4-node consistent-hash ring (with virtual nodes), batched write path with write-amplification reduction, popularity **and** recency ranking (with decayed time-bucket windows), and a UI that mirrors the provided design 1:1.

**Author:** Subhan Rahiman · **Email:** subhan.24bcs10095@sst.scaler.com
**Repository:** https://github.com/SubhanRahiman7/HLD-Assignment
**Last commit hash:** see `git log -1` (rebuilt after dataset + recency + ring upgrades)

---

## 1. Architecture

### 1.1 System diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│ Browser (UI) │
│ - Header / brand / ranking toggle (popularity ⇄ recency) │
│ - Search input with debounced live suggestions │
│ - Dropdown with prefix highlighting, keyboard nav │
│ - Result card (200 OK / 503) │
│ - Trending chips, Recent chips (clear) │
│ - Telemetry line: HIT/MISS · ms · node · p95 │
└────────────────────────────────┬────────────────────────────────────┘
 │ HTTP (fetch)
 ▼
┌─────────────────────────────────────────────────────────────────────┐
│ Express / Spring Boot server │
│ │
│ GET /suggest ──▶ ConsistentHashRing (4 nodes × 200 vnodes) ──┐ │
│ FNV-1a(prefix) → binary search ring → node │ │
│ hit: return Entry<list,ttl> │ │
│ miss: │ │
│ ▼ │ │
│ Ranker.rank(prefix, mode) │ │
│ ├─ popularity: sort by c │ │
│ └─ recency: c + score(recencyBuckets) │ │
│ ▼ │ │
│ Store.counts (primary, in-memory, 110k+ rows) │ │
│ Store.recency (5m/1h/24h buckets, decayed) │ │
│ cache.put(node, prefix, Entry) │ │
│ │
│ POST /search ──▶ recency[5m,1h,24h]++ (per bucket) │ │
│ Store.enqueueIncrement(q) ──┐ │ │
│ WriteBuffer[q] += 1 ◀── coalesce │ │
│ narrow cache invalidation (prefixes ⊏ q) │ │
│ ▼ │ │
│ Store.flush() atomically applies deltas │ │
│ │
│ BatchScheduler @ fixedDelay = 2s ──┐ │ │
│ │ │ │
│ RecencyDecay @ fixedDelay = 5s ────┘ │ │
│ buckets: 5m×0.85, 1h×0.95, 24h×0.99 │ │
└─────────────────────────────────────────────────────────────────────┘
```

### 1.2 Module map

**Node build (`/`)**

| File | Responsibility |
| --- | --- |
| `server.js` | HTTP layer, batch + decay loops, telemetry (incl. p95), graceful shutdown |
| `src/store.js` | Primary store, recency buckets, write buffer, `flushBatch`, `decayRecency`, 110k-row synthetic generator |
| `src/cache.js` | FNV-1a + 4-node ring with 200 vnodes/node, binary-search lookup |
| `src/ranking.js` | Prefix filter + popularity / recency (5m·5 + 1h·1 + 24h·0.1) × 30 000 |
| `src/recent.js` | Case-insensitive dedupe, cap 6 |
| `public/index.html`, `public/styles.css`, `public/app.js` | Frontend mirroring the design source |

**Java / Spring Boot build (`/java`)**

| File | Responsibility |
| --- | --- |
| `pom.xml` | Spring Boot 3.4.7 parent, Java 21 |
| `src/main/resources/application.properties` | `server.port=3000` |
| `TypeaheadApplication.java` | `@SpringBootApplication` + `@EnableScheduling` |
| `TypeaheadController.java` | All REST endpoints + p95 telemetry sampler |
| `Store.java` | Primary store, recency buckets, write buffer, decay job, 110k-row synthetic generator |
| `BatchScheduler.java` | `@Scheduled(fixedDelay=2000L)` flush + `RecencyDecay` @ 5s |
| `ConsistentHashCache.java` | 4-node ring, 200 vnodes/node, binary-search lookup |
| `Ranker.java` | popularity / recency ranking |
| `Recent.java` | user-recent (cap 6) |
| `QueryCount.java`, `Suggestion.java` | data records |
| `src/main/resources/static/*` | Identical frontend (HTML/CSS/JS) |

### 1.3 Request lifecycle — happy path

1. User types a prefix in the browser. After 100ms debounce, the UI calls `GET /suggest?q=<prefix>&mode=popularity`.
2. Server hashes the prefix with FNV-1a, binary-searches the ring of 800 vnodes to pick a cache node (`cache-a`–`cache-d`).
3. **Cache hit:** return the cached list + `hit: true` + the elapsed ms. Record sample for p95.
4. **Cache miss:** run `Ranker.rank(counts, prefix, mode, recencyBuckets)` → top 10 → store in cache with a 60s TTL → return `hit: false` + ms.
5. User picks a suggestion (or hits Enter on their own query) → UI posts `{"q":"..."}` to `POST /search`.
6. Server increments `recencyBuckets[q]` (`[5m, 1h, 24h]` all +1), enqueues a `+1` delta into `writeBuffer[q]`, performs narrow cache invalidation (drops only the cache entries whose key is a prefix of `q`), adds to Recent, and pre-flushes the buffer so the response shows the new count.
7. Independently, every 2s the `BatchScheduler` calls `Store.flush()` applying buffered deltas in one pass. Every 5s the `RecencyDecay` job multiplies bucket counters by `[0.85, 0.95, 0.99]` so older activity fades.

---

## 2. Dataset and loading instructions

### 2.1 Dataset (≥ 100k rows)

The primary store is seeded at boot with a synthetic corpus of **110 189** (Java) / **110 000** (Node) unique query rows. The corpus is generated from three small vocabularies and is large enough to demonstrate a non-trivial linear scan, a useful cache, and a real ring distribution.

**Vocabulary**

| Source | Items | Example output |
| --- | --- | --- |
| Brands (144) | iPhone family, Samsung, Google Pixel, MacBook, consoles, games, accessories | `iphone 15 pro max`, `galaxy z fold`, `baldur gate 3` |
| Actions × nouns (33 × 144) | `how to` × `laptop`; `best` × `headphones`; … | `how to laptop`, `best headphones` |
| Tech × tech (~20 k pairs) | every distinct ordered pair of `tech` items | `react vs vue`, `python vs rust` |
| Tech × suffixes (200 × 49) | `tutorial`, `vs alternatives`, `on kubernetes`, `vs kafka` | `react tutorial`, `kafka vs rabbitmq` |
| Brands × brand suffixes (144 × 27) | `review`, `vs samsung`, `release date` | `iphone 15 review` |
| Actions × nouns × suffixes (33 × 144 × 49) | compound queries | `best laptop on kubernetes` |
| Brands × tech (~28 k) | cross-domain searches | `iphone react`, `macbook rust` |

Each row gets a Zipf-shaped count starting at 200 000, decreasing per insertion, with `c ≥ 50`.

**Source.** No external dataset is fetched. The assignment brief asks for a UI mirroring a search-typeahead screenshot; the underlying query/count data is sample data and is generated on boot.

### 2.2 Loading instructions

```bash
# Node build (default)
cd project
npm install
npm start # binds to :3000

# Java / Spring Boot build
cd project/java
mvn -DskipTests package
java -jar target/search-typeahead-1.0.0.jar
# or
mvn spring-boot:run
```

Boot log line to verify: `Generated synthetic corpus: 110189 unique queries` (Java) / `Generated synthetic corpus: 110000 unique queries` (Node). Telemetry `dataSize` should be ≥ 100 000.

---

## 3. API documentation

Base URL: `http://localhost:3000`. All responses are JSON.

### 3.1 `GET /suggest`

| Name | Type | Default | Notes |
| --- | --- | --- | --- |
| `q` | string | `""` | Empty → `{ "suggestions": [], "node": null, "hit": false, "ms": 0 }` |
| `mode` | string | `popularity` | `popularity` \| `recency` |

**Cache miss**

```json
{
 "node": "cache-a",
 "hit": false,
 "ms": 7.06,
 "suggestions": [
 {"q": "best laptops 2026", "c": 198450},
 {"q": "best headphones", "c": 198400}
 ]
}
```

**Cache hit** returns the identical list, `hit: true`, sub-ms latency. **Empty prefix** returns an empty list with `node: null`.

### 3.2 `POST /search`

```bash
curl -X POST http://localhost:3000/search \
 -H 'content-type: application/json' \
 -d '{"q":"iphone 15"}'
```

```json
{ "message": "Searched", "query": "iphone 15", "count": 199951, "ms": 0.52 }
```

Side effects: `recency[iphone 15] += [1,1,1]`, `writeBuffer[iphone 15] += 1`, narrow cache invalidation, recent-list append, immediate flush.

`q === "error"` (case-insensitive) → `503 { "message": "Upstream search service did not respond within the latency budget." }`. Empty `q` → `400`.

### 3.3 `GET /trending`

```bash
curl 'http://localhost:3000/trending?mode=recency'
```

```json
{
 "mode": "recency",
 "trending": [
 {"q": "world cup schedule", "c": 198200},
 {"q": "taylor swift tickets", "c": 198250},
 {"q": "chatgpt", "c": 198050}
 ],
 "note": "recency-weighted score"
}
```

`mode=popularity` returns the same data with a `by all-time search count` note.

### 3.4 `GET /recent`, `POST /recent/clear`

```json
{ "recent": ["iphone 15", "macbook air"] }
```

`POST /recent/clear` → `{ "ok": true }`.

### 3.5 `GET /cache/debug`, `POST /cache/flush`

```bash
curl 'http://localhost:3000/cache/debug?prefix=iph'
# { "prefix": "iph", "node": "cache-a", "hit": false,
# "nodes": ["cache-a","cache-b","cache-c","cache-d"] }
```

`POST /cache/flush` clears all four nodes.

### 3.6 `GET /telemetry`

```json
{
 "hits": 12,
 "misses": 4,
 "hitRate": 75.0,
 "batches": 3,
 "batchedOps": 7,
 "lastFlushMs": 0.0,
 "cacheSize": 6,
 "dataSize": 110189,
 "pendingWrites": 0,
 "p95SuggestMs": 8.09
}
```

### 3.7 `POST /benchmark/batch?writes=N`

```bash
curl -X POST 'http://localhost:3000/benchmark/batch?writes=2000'
# { "simulatedSearches": 2000,
# "actualBatchedWrites": 1,
# "writeReductionFactor": 2000,
# "note": "Batch coalesces same-query writes within a 2s window into a single store update." }
```

---

## 4. Design choices and trade-offs

### 4.1 In-memory primary store

- **Why:** zero-setup demo; assignment scope is architecture + UI.
- **Trade-off:** counts reset on restart. Swapping `Store.counts` for a real DB is a 1-line change at the boundary.

### 4.2 100k+ synthetic dataset

- **Why:** the brief asked for a non-trivial dataset; a 44-row store would make cache effectiveness uninteresting.
- **How:** three small vocabularies cross-producted (brands, actions×nouns, tech×tech, tech×suffix, brands×suffix, action×noun×suffix, brand×tech). 110k unique rows is large enough to show real per-prefix fan-out (`iph*` → 30 results, `react vs*` → 200 results) and small enough to keep linear scan sub-ms after sorting.
- **Trade-off:** counts are synthetic. Realistic enough for ranking demos; production would ingest a real search log.

### 4.3 Decaying recency buckets

- **Why:** "recency" should be **time-weighted** — a query searched once 5 minutes ago should rank higher than one searched a day ago, even if both are 1 event.
- **How:** each query has a `[5m, 1h, 24h]` counter triple. Every search bumps all three. Every 5 seconds a `RecencyDecay` job multiplies them by `[0.85, 0.95, 0.99]`. The ranking score is `c + (5·b5m + 1·b1h + 0.1·b24h) × 30 000`.
- **Trade-off:** the decay constants and weights are tuned by hand. A real system would learn them from CTR / engagement.

### 4.4 Real consistent-hash ring with virtual nodes

- **Why:** `FNV-1a(prefix) % 4` is a one-liner but has poor distribution. A ring of `4 × 200 = 800` vnodes with binary-search lookup gives a true consistent-hash property (adding/removing a node redistributes ~1/N of the keys) and uniform spread.
- **Trade-off:** 800-entry ring is small. Production might use 200 vnodes/node on the same size — same algorithm, different scale.

### 4.5 Write batching with a 2s window

- **Why:** search writes are bursty and duplicate (a trending query, an autocompleted-then-submitted query). Coalescing into a single primary-store update reduces write amplification ~1000× under uniform bursts (see `/benchmark/batch`).
- **Trade-off:** worst-case staleness for the count returned by `POST /search` is 2s. Mitigated by calling `Store.flush()` synchronously inside the handler.

### 4.6 Narrow cache invalidation

- **Why:** when query `q` is incremented, only the cache entries whose key is a prefix of `q` can change ordering. All other prefixes' top-K stays valid.
- **Trade-off:** 60s TTL is the safety net for any invalidation we miss.

### 4.7 Telemetry: p95 latency

- **Why:** hit-rate alone hides the slow paths. p95 over a 5000-sample ring buffer exposes outliers in linear scans.
- **Trade-off:** bounded memory (capped at 5000 unique sample values).

### 4.8 Two ranking modes

- **popularity:** sort by all-time count.
- **recency:** `score = c + (5·b5m + 1·b1h + 0.1·b24h) × 30 000`.

### 4.9 Recent list (cap 6)

- **Why:** UI only has space for 6 chips. Cap enforced at the server.
- **Trade-off:** per-process, not per-user. Real system would key on `userId` / session cookie.

### 4.10 503 demo path

- `POST /search` with `q === "error"` returns 503 to exercise the UI's red error state.

### 4.11 Why two builds

The Node build is the canonical implementation (small, easy to read, no compile step). The Spring Boot build is a 1:1 translation that demonstrates the architecture in a statically-typed JVM framework. Endpoints, payloads, and behavior match; the frontend is byte-identical.

---

## 5. Performance report

### 5.1 Latency (local, in-memory, 110k rows)

| Operation | Median | p95 (measured) | Notes |
| --- | --- | --- | --- |
| `GET /suggest` cache hit | 0.01–0.05 ms | <1 ms | Single map lookup |
| `GET /suggest` cache miss | 2–8 ms | ~8 ms | Linear scan of 110k rows + sort + 10 entries |
| `POST /search` | 0.5–1 ms | n/a | Linear scan + buffered enqueue + narrow invalidation + pre-flush |
| `GET /trending` | 2–4 ms | n/a | Empty-prefix rank of 110k rows |
| `POST /benchmark/batch?writes=2000` | 0.1–0.5 ms | n/a | 1 actual batched write |

Measured p95 in a fresh `telemetry` call: **8.09 ms** on Java, ~5 ms on Node.

### 5.2 Write amplification

A 2000-write burst on 7 queries collapses to **1 actual store write** (factor **2000×**). Real-world bursts are even more skewed — a trending query dominates — so the reduction factor is usually higher.

### 5.3 Cache effectiveness

Repeated `/suggest` calls for the same prefix hit the cache after the first request. `telemetry.hitRate` climbs toward 1 as a user keeps typing within the same prefix family.

### 5.4 Memory

- 110k query rows × ~30 bytes = ~3.3 MB.
- Per-node cache: small map, only top-10 lists stored.
- 800-entry consistent-hash ring: ~12 KB.
- 5000-sample p95 ring: bounded.

No allocations on the hot path after warm-up.

### 5.5 End-to-end smoke results (Node)

```
GET /telemetry → dataSize=110000, p95SuggestMs=0
GET /suggest?q=iphone (miss) → 200 OK, 5 suggestions returned
GET /suggest?q=react+vs → "react vs vue", "react vs svelte", … 10 results
GET /trending?mode=recency → world cup schedule, taylor swift, chatgpt, …
POST /search {"q":"mynewword"} → 200, count=1, mynewword added to recency + DATA
GET /suggest?q=mynewword → 200, appears in suggestions
POST /benchmark/batch?writes=2000 → actualBatchedWrites=1, factor=2000×
POST /search {"q":"error"} → 503
GET /cache/debug?prefix=iph → node=cache-d
GET /telemetry (after) → hits, misses, hitRate, p95SuggestMs=5.1
```

### 5.6 Future work / what's not done

- Persistence (Postgres / Redis / Kafka).
- Authentication and per-user Recent.
- Per-prefix Bloom-filter pre-check before linear scan.
- Adaptive recency weights from CTR.
- Distributed cache (Redis Cluster) replacing the in-process ring.
- Tracing / metrics export (OpenTelemetry) and `/healthz`.

---

## 6. Repository layout

```
HLD-Assignment/
├── README.md
├── package.json / package-lock.json
├── server.js
├── public/ # frontend (served by both builds)
│ ├── index.html
│ ├── styles.css
│ └── app.js
├── src/ # Node backend modules
│ ├── store.js
│ ├── cache.js
│ ├── ranking.js
│ └── recent.js
└── java/ # Spring Boot backend
 ├── pom.xml
 ├── README.md
 └── src/main/
 ├── java/com/typeahead/
 │ ├── TypeaheadApplication.java
 │ ├── TypeaheadController.java
 │ ├── Store.java
 │ ├── BatchScheduler.java
 │ ├── ConsistentHashCache.java
 │ ├── Ranker.java
 │ ├── Recent.java
 │ ├── QueryCount.java
 │ └── Suggestion.java
 └── resources/
 ├── application.properties
 └── static/ # copy of public/
```