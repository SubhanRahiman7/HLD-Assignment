# Persistence Setup (Redis + Postgres)

Java backend now supports optional Redis + Postgres backing. Default fallback is H2 in-memory.

## Modes

### 1. Fallback (default) — H2 in-memory
No env vars needed. App runs with H2 in-process database and in-memory Map for recency.

```bash
cd java && mvn package && java -jar target/search-typeahead-1.0.0.jar
```

### 2. Full persistence — Postgres + Redis
Requires Docker Compose:

```bash
# Start services
docker compose up -d

# Run Java with env vars pointing to Docker services
export PG_URL="jdbc:postgresql://localhost:5432/typeahead"
export PG_USER="typeahead"
export PG_PASSWORD="typeahead"
export REDIS_URL="redis://localhost:6379"

cd java && mvn package && java -jar target/search-typeahead-1.0.0.jar
```

Or pass env vars inline:

```bash
PG_URL="jdbc:postgresql://localhost:5432/typeahead" \
PG_USER="typeahead" \
PG_PASSWORD="typeahead" \
REDIS_URL="redis://localhost:6379" \
java -jar java/target/search-typeahead-1.0.0.jar
```

### 3. Postgres only (no Redis)

Set PG_URL but leave REDIS_URL unset → falls back to in-memory recency.

### 4. Redis only (no Postgres)

Set REDIS_URL but leave PG_URL unset → falls back to H2 for counts, uses Redis for recency cache.

## Implementation

- **Postgres**: JPA + Hibernate, `QueryCountEntity` table, `QueryCountRepository` for read/write.
- **Redis**: Spring Data Redis, `RedisTemplate<String, String>` for recency bucket increments.
- **Fallback**: H2 in-process DB when PG_URL unset; in-memory Map when REDIS_URL unset.

The fallback profile auto-activates when REDIS_URL is empty, disabling Lettuce auto-config to avoid connection failures.
