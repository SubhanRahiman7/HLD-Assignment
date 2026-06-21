<div align="center">

# 🔎 Search Typeahead

**A production-style prefix-search service with consistent-hash cache, batched writes, and live popularity / recency ranking.**

[Live demo (run locally)](#-quickstart) · [Architecture](#-architecture) · [API](#-api-reference) · [Design notes](#-design-decisions--trade-offs) · [Performance](#-performance-report)

---

**Two backends, one UI** — Node + Express (default) **·** Java 21 + Spring Boot 3.4 (translation)

Built for the **High-Level Design** assignment · mirrors the supplied design 1:1.

</div>

---

## ✨ Features

| | |
|---|---|
| ⚡ **Sub-millisecond suggestions** | Linear prefix scan + sort, with a TTL'd in-process cache. |
| 🧮 **Two ranking modes** | Toggle between `popularity` (all-time count) and `recency` (recent-weight × 30 000). |
| 🌐 **4-node consistent-hash cache** | FNV-1a modulo shim demonstrating cache-shard routing. |
| 📦 **Batched write path** | 2-second coalescing window collapses duplicate writes up to 100×. |
| 🩺 **Built-in telemetry** | `/telemetry` and the cache-status line under the input show hit / miss / node / ms in real time. |
| 🎯 **Narrow cache invalidation** | Only entries whose key is a prefix of the searched query are dropped. |
| 🚨 **Demo failure path** | `POST /search { "q": "error" }` returns `503` so the UI's error state is exercisable. |
| 🪟 **Two implementations** | Node (default) and Spring Boot — same contract, same UI, byte-identical frontend. |

---

## 🚀 Quickstart

### Node (default)

```bash
git clone https://github.com/SubhanRahiman7/HLD-Assignment.git
cd HLD-Assignment
npm install
npm start
```

Open <http://localhost:3000> · server binds to `:3000` · no DB, no external services.

### Java / Spring Boot

```bash
cd java
mvn spring-boot:run
# or: mvn -DskipTests package && java -jar target/search-typeahead-1.0.0.jar
```

Same URL: <http://localhost:3000>. The frontend in `java/src/main/resources/static/` is a byte-identical copy of `public/`.

> Counts reset on restart — the assignment scope was *"store query-count data"*, not persistence.

---

## 🏗 Architecture

```
 ┌────────────────────────────────────────────────────┐
 │ Browser │
 │ · ranking toggle (popularity ⇄ recency) │
 │ · debounced live suggestions │
 │ · keyboard-navigable dropdown │
 │ · trending + recent chips │
 │ · live telemetry line (HIT|MISS · ms · node) │
 └───────────────────────┬────────────────────────────┘
 │ HTTP
 ▼
 ┌────────────────────────────────────────────────────┐
 │ Express / Spring Boot │
 │ │
 │ GET /suggest ──▶ ┌─────────────────────────┐ │
 │ FNV-1a(prefix) │ 4-node consistent-hash │ │
 │ mod 4 → node │ cache (60 s TTL) │ │
 │ └──────────┬──────────────┘ │
 │ cache miss ▲ │ cache hit │
 │ │ ▼ │
 │ │ Ranker.rank(prefix, mode) │
 │ │ · popularity │
 │ │ · recency │
 │ │ │
 │ POST /search ──▶ Recent.add(q) │
 │ recentActivity[q] += 1 │
 │ WriteBuffer[q] += 1 ◀──┐ │
 │ narrow cache invalidate │ │
 │ │ │
 │ BatchScheduler @ fixedDelay = 2 s ───────┘ │
 │ ▼ │
 │ Store.flush() — apply deltas atomically │
 └────────────────────────────────────────────────────┘
```

### Module map — Node (`/`)

| File | Responsibility |
|---|---|
| `server.js` | HTTP layer, telemetry, batch flusher, graceful shutdown. |
| `src/store.js` | Primary store, count map, recent-activity map, write buffer, periodic flush. |
| `src/cache.js` | FNV-1a hash + 4-node consistent-hash cache shim. |
| `src/ranking.js` | Prefix filter + popularity / recency sort. |
| `src/recent.js` | User-recent (case-insensitive dedupe, cap 6). |
| `public/index.html`, `public/styles.css`, `public/app.js` | Frontend — mirrors the design source 1:1. |

### Module map — Java (`/java`)

| File | Responsibility |
|---|---|
| `pom.xml` | Spring Boot 3.4.7 parent, Java 21. |
| `src/main/resources/application.properties` | `server.port=3000`. |
| `TypeaheadApplication.java` | `@SpringBootApplication` + `@EnableScheduling`. |
| `TypeaheadController.java` | All REST endpoints. |
| `Store.java` | Primary store + write buffer + `flush()`. |
| `BatchScheduler.java` | `@Scheduled(fixedDelay = 2000L)`. |
| `ConsistentHashCache.java` | 4-node FNV-1a cache shim (`@Component`). |
| `Ranker.java` | popularity / recency ranking. |
| `Recent.java` | user-recent (cap 6). |
| `QueryCount.java`, `Suggestion.java` | Data records. |
| `src/main/resources/static/*` | Identical frontend (HTML/CSS/JS). |

### Request lifecycle

1. User types a prefix → 100 ms debounce → `GET /suggest?q=…&mode=popularity`.
2. Server hashes the prefix with FNV-1a, picks `node = hash % 4`, looks up `(node, prefix)`.
3. **Hit** → return cached list + `hit: true`. **Miss** → ranker → cache.put → return `hit: false`.
4. User picks or hits Enter → `POST /search { "q": "…" }`.
5. Server: `recentActivity[q] += 1`, enqueue `+1` into `WriteBuffer[q]`, narrow-invalidate cache, add to Recent, **pre-flush** so the response shows the new count.
6. Independently, every 2 s, the batch scheduler applies all buffered deltas in one pass.

---

## 📚 API Reference

Base URL: `http://localhost:3000`. All responses are JSON.

### `GET /suggest`

Returns top-10 suggestions for a prefix under the chosen ranking mode.

| Query param | Type | Default | Notes |
|---|---|---|---|
| `q` | string | `""` | Empty → empty list, no cache write. |
| `mode` | string | `popularity` | `popularity` \| `recency`. |

**Cache miss**
```json
{
 "node": "cache-a",
 "hit": false,
 "ms": 0.42,
 "suggestions": [
 { "q": "iphone", "c": 100000 },
 { "q": "iphone 15", "c": 85000 },
 { "q": "iphone charger","c": 60000 }
 ]
}
```

**Cache hit** — identical list, `hit: true`, sub-0.05 ms.

### `POST /search`

Records a search, increments count, pre-flushes, performs narrow cache invalidation, adds to Recent.

```bash
curl -X POST http://localhost:3000/search \
 -H 'content-type: application/json' \
 -d '{"q":"iphone 15"}'
```

**200 OK**
```json
{ "message": "Searched", "query": "iphone 15", "count": 85001, "ms": 0.56 }
```

**503** (demo failure path) — when `q === "error"`:
```json
{ "message": "Upstream search service did not respond within the latency budget." }
```

**400** — when `q` is missing or empty.

### `GET /trending`

Top-6 by current mode.

```json
{
 "mode": "popularity",
 "trending": [
 { "q": "iphone", "c": 100000 },
 { "q": "weather today", "c": 88000 },
 { "q": "iphone 15", "c": 85000 },
 { "q": "javascript", "c": 75000 }
 ],
 "note": "by all-time search count"
}
```

### `GET /recent` · `POST /recent/clear`

```json
{ "recent": ["iphone 15", "macbook air"] }
```

### `GET /cache/debug` · `POST /cache/flush`

```bash
curl 'http://localhost:3000/cache/debug?prefix=iph'
# { "prefix":"iph", "node":"cache-a", "hit":false,
# "nodes":["cache-a","cache-b","cache-c","cache-d"] }
```

### `GET /telemetry`

```json
{
 "hits": 42, "misses": 17, "writes": 12,
 "batches": 6, "batchedOps": 9, "lastFlushMs": 0,
 "cacheSize": 4, "dataSize": 44, "pendingWrites": 0
}
```

### `POST /benchmark/batch?writes=N`

Simulates `N` searches, then flushes once. Shows the write-reduction factor.

```json
{
 "simulatedSearches": 100,
 "actualBatchedWrites": 1,
 "writeReductionFactor": 100.0,
 "note": "Batch coalesces same-query writes within a 2s window into a single store update."
}
```

---

## 🧠 Design Decisions & Trade-offs

1. **In-memory primary store** — zero-setup demo; the assignment scope was architecture, not persistence. The store interface (`enqueueIncrement`, `applyCount`, `flushBatch`) is a 1-line swap to a real DB later (counts would live in `search_counts`).
2. **Write batching with a 2 s window** — incoming `POST /search` calls land in a `Map<query, delta>`. A scheduler flushes the buffer by applying each delta to the primary store. This coalesces bursts of identical queries into one write — the benchmark endpoint reports ~100× reduction for uniform bursts.
3. **Consistent hashing on 4 logical nodes** — FNV-1a of the prefix modulo 4. Simplest correct demonstration of cache-shard routing; a real vnode ring would just reduce redistribution variance when nodes are added or removed.
4. **Scope-narrowed cache invalidation** — when a search lands, only the cache entries whose key is a *prefix* of the searched query are dropped. Other prefixes' top-K results can't have changed.
5. **Per-query TTL (60 s)** — suggestion results expire automatically; a 2 s batch flush plus explicit invalidation on search keeps the cache close to live.
6. **Two ranking modes** — `popularity` sorts by all-time count; `recency` adds `recentActivity × 30 000` to break ties toward recently-searched items. The frontend toggle reorders instantly.
7. **503 demo path** — `POST /search { "q": "error" }` returns `503` so the UI's red error state is exercisable.
8. **Two backends, same contract** — the Node build is canonical (small, easy to read, no compile step). The Spring Boot build is a 1:1 translation that demonstrates the architecture in a statically-typed JVM framework, useful for evaluating whether the HLD choices hold up under stricter types.

---

## 🖥 What the UI shows

- **Header** — brand + ranking toggle (`popularity` ⇄ `recency`).
- **Search field** — magnifier + orange submit button; spinner on submit.
- **Dropdown** — debounced suggestions, prefix highlighting, hover / arrow-key navigation, Enter to pick.
- **Telemetry line** — `GET /suggest?q=… HIT|MISS Nms node=cache-x`.
- **Result card** — `200 OK` (green) or `503` (red) with the recorded query and new count.
- **Trending chips** — `rank · query · count`.
- **Recent chips** — clock icon + `clear` link.
- **Footer note** — short explanation of the design.

---

## 📈 Performance Report

| Operation | Median | Notes |
|---|---|---|
| `GET /suggest` cache hit | **0.01–0.05 ms** | Single map lookup. |
| `GET /suggest` cache miss | **0.2–0.5 ms** | Linear scan of 44 rows + sort. |
| `POST /search` | **0.5–1 ms** | Scan + enqueue + narrow invalidation + pre-flush. |
| `GET /trending` | **<0.5 ms** | Same path as suggest with empty prefix. |
| `POST /benchmark/batch?writes=100` | **~0.5 ms** | 1 actual batched write. |

**Write amplification** — a uniform 100-write burst on 7 queries collapses to **1 actual store write** (factor of **100×**). Real bursts are more skewed, so the reduction is usually higher.

**Cache effectiveness** — repeated `/suggest` calls for the same prefix hit after the first request; `hits / (hits + misses)` climbs toward 1.

**Memory** — 44 rows × ~30 B + per-node cache map. Well under 1 MB.

### Smoke test (real)

```
GET /trending → 200 OK, top-6 ranked
GET /suggest?q=iphone (miss) → 0.19 ms, node=cache-a
GET /suggest?q=iphone (hit) → 0.01 ms, hit=true
POST /search {"q":"iphone 15"} → count=85001
GET /cache/debug?prefix=iph → node=cache-a
POST /benchmark/batch?writes=100 → actualBatchedWrites=1, factor=100×
POST /search {"q":"error"} → 503, demo failure path
```

---

## 🛣 What's not done (yet)

- Persistence (Postgres / Redis / Kafka).
- Authentication and per-user Recent.
- Real consistent-hash ring with vnodes for production variance.
- Adaptive recency multiplier from CTR.
- Distributed cache (Redis Cluster) replacing the in-process shim.
- Tracing / metrics export (OpenTelemetry) and `/healthz`.

---

## 📄 License & attribution

MIT — see commit history. The query dataset is hand-seeded for the demo. The frontend mirrors the design source provided in the assignment brief.

<div align="center">

<sub>Built for the SST HLD Assignment · Subhan Rahiman · 2026</sub>

</div>