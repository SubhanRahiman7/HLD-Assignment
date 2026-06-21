# Search Typeahead — Spring Boot (Java)

Same project as the parent `/project` directory, but the backend is Java 21 + Spring Boot 3.4 instead of Node + Express. The frontend (HTML/CSS/JS) is identical and is served from `src/main/resources/static/`.

## Run

```bash
cd java
mvn spring-boot:run
# open http://localhost:3000
```

Or build a fat jar and run it directly:

```bash
mvn -DskipTests package
java -jar target/search-typeahead-1.0.0.jar
```

The server binds to port 3000 (set in `src/main/resources/application.properties`).

## Layout

```
java/
├── pom.xml
└── src/main/
 ├── java/com/typeahead/
 │ ├── TypeaheadApplication.java # @SpringBootApplication main
 │ ├── TypeaheadController.java # REST endpoints
 │ ├── Store.java # Primary store + write buffer + flush
 │ ├── BatchScheduler.java # @Scheduled flush every 2s
 │ ├── ConsistentHashCache.java # 4-node FNV-1a cache shim
 │ ├── Ranker.java # popularity / recency ranking
 │ ├── Recent.java # user-recent (cap 6)
 │ ├── QueryCount.java # store row
 │ └── Suggestion.java # DTO for /suggest
 └── resources/
 ├── application.properties
 └── static/ # same frontend as Node version
 ├── index.html
 ├── styles.css
 └── app.js
```

## Endpoints

Identical contract to the Node version:

| Method | Path | Notes |
| --- | --- | --- |
| `GET` | `/suggest?q=&mode=popularity\|recency` | Returns suggestions + cache node + hit flag |
| `POST` | `/search` body `{"q":"..."}` | Increments count, pre-flushes, invalidates cache |
| `GET` | `/trending?mode=...` | Top-6 |
| `GET` | `/recent` | Last 6 user searches |
| `POST` | `/recent/clear` | |
| `GET` | `/cache/debug?prefix=...` | |
| `POST` | `/cache/flush` | |
| `GET` | `/telemetry` | |
| `POST` | `/benchmark/batch?writes=N` | |

## Design Notes Specific to the Java Build

- `@EnableScheduling` + `@Scheduled(fixedDelay=2000L)` runs the batch flush every 2 s, identical timing to the Node `setInterval`.
- The write buffer is a `ConcurrentHashMap<String, Long>` with `merge(q, 1, sum)` for safe concurrent increments.
- `Store.flush()` walks the buffer once, applies each delta to the matching `QueryCount` (or inserts a new one), and updates the count-store map for `/search` responses.
- `ConsistentHashCache` uses `List.of("cache-a", ..., "cache-d")` and `fnv1a(prefix) % NODES.size()`. Adding `@Component` makes it injectable.
- All endpoints are `@RestController` methods; Jackson serializes the `Map.of(...)` payloads.

## Why both builds?

The Node version is what the assignment originally asked for and stays as the canonical reference. The Spring Boot version is a translation that demonstrates the same architecture in a JVM/statically-typed stack — useful for evaluating whether the HLD choices hold up when types are stricter and the framework is heavier.
