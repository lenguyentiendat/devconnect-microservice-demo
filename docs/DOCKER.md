# Docker Compose Operations

## Topology

`docker-compose.yml` starts 14 services:

1. PostgreSQL
2. pgAdmin
3. Cassandra
4. Cassandra Web
5. Cassandra initialization job
6. Redis
7. Kafka
8. Kafka UI
9. Discovery Server
10. User Service
11. Feed Service
12. Search Service
13. Notification Service
14. API Gateway

All services join the external Docker network `devconnect-network`. Create it once if it is absent:

```bash
docker network create devconnect-network
```

If the network already exists, Docker reports that fact and no action is needed.

## Published and internal ports

| Component | Host port | Notes |
| --- | ---: | --- |
| PostgreSQL | 5432 | Local database inspection |
| pgAdmin | 5050 | PostgreSQL browser administration UI |
| Cassandra | 9042 | Local CQL inspection |
| Cassandra Web | 3001 | Cassandra browser administration UI |
| Kafka | 9092 | Local Kafka clients |
| Kafka UI | 8085 | Browser management UI |
| Discovery Server | 8761 | Eureka dashboard |
| API Gateway | 8090 | Only public application API/Swagger port |
| Redis | none | Internal-only Feed cache; no host port is published |
| User, Feed, Search, Notification | none | Exposed only inside Compose on 8081–8084 |

The service `expose` settings make business ports available on the Docker network but not published to the host. This prevents accidental browser requests that bypass Gateway CORS and routing.

## Redis cache operations

Redis is an internal-only, disposable cache for Feed posts and cursor-paged feed results. The local container uses `allkeys-lfu`, has no persistence, and deliberately publishes no host port. Inspect it from inside Compose:

```bash
docker compose exec redis redis-cli
docker compose exec redis redis-cli --scan --pattern 'devconnect:local:feed:v1:page:global:*'
```

Feed application code never scans or deletes keys by prefix. After a post write it advances the feed revision; page keys for older revisions become unreachable and expire naturally. A Redis outage does not make Feed reads unavailable: the cache layer records the failure, bypasses the cache, and falls back to Cassandra. Cache and invalidation degradation is visible through Feed actuator metrics, including `feed.cache.redis.errors` and the invalidation counters. Feed exposes `health`, `info`, and `metrics` at `/actuator`.

Local cache configuration is supplied to Feed Service through these environment variables:

| Variable | Purpose | Local default |
| --- | --- | --- |
| `CACHE_ENABLED` | Enables the two-level cache | `true` |
| `CACHE_ENVIRONMENT` | Namespaces Redis keys | `local` |
| `CACHE_PAGE_TOKEN_SECRET` | Signs opaque feed page tokens | required; Compose sets a local-only value |
| `REDIS_CONNECT_TIMEOUT` | Redis connection timeout | `500ms` |
| `REDIS_COMMAND_TIMEOUT` | Redis command timeout | `500ms` |

For production, use a managed Redis Cluster or Sentinel deployment with TLS and ACLs; keep Redis off the public network. Store ACL credentials and `CACHE_PAGE_TOKEN_SECRET` in a secret manager, set bounded connection/command timeouts, and alert on memory/capacity, evictions, connection errors, latency, and cache-error metrics. Do not reuse the local Compose secret or treat the local Redis container as durable storage.

## Database UI

Start the database UIs with their database dependencies:

```bash
docker compose up -d pgadmin cassandra-web
```

Open [pgAdmin](http://localhost:5050) and sign in with the local defaults:

```text
Email:    admin@devconnect.com
Password: devconnect
```

When registering the PostgreSQL server in pgAdmin, use the Compose service name rather than `localhost`:

```text
Host:     postgres
Port:     5432
Database: devconnect_users
Username: devconnect
Password: devconnect
```

Open [Cassandra Web](http://localhost:3001) and connect to:

```text
Host:     cassandra
Port:     9042
Keyspace: devconnect_feed
```

The pgAdmin data is persisted in the `pgadmin-data` named volume. The UI credentials can be overridden with `PGADMIN_DEFAULT_EMAIL` and `PGADMIN_DEFAULT_PASSWORD` before starting Compose.

Use direct database edits only for local development. For example, safe inspection queries include:

```sql
SELECT user_id, status, email FROM users;
```

```sql
USE devconnect_feed;
SELECT * FROM posts_by_id LIMIT 20;
```

If you intentionally edit local data, examples are:

```sql
UPDATE users SET status = 'INACTIVE' WHERE user_id = 'u001';
```

```sql
USE devconnect_feed;
DELETE FROM posts_by_id WHERE post_id = <post UUID>;
```

Direct edits bypass application validation, Kafka events, and service-to-service rules. They can make Search and Notification projections inconsistent with User or Feed data until the projections are recreated or the relevant events are replayed.

## Start, inspect, and stop

```bash
docker compose up -d --build
docker compose ps
docker compose logs -f api-gateway
docker compose down
```

Use a specific service name with `docker compose logs -f`, for example `feed-service` or `search-service`, when diagnosing startup or Kafka processing.

## Health and discovery checks

Compose waits on health checks for PostgreSQL, Cassandra, Kafka, Discovery Server, and application TCP ports. After startup:

```bash
curl -I http://localhost:8090/actuator/health
curl -I http://localhost:8761
```

Open Eureka and confirm `USER-SERVICE`, `FEED-SERVICE`, `SEARCH-SERVICE`, and `NOTIFICATION-SERVICE` are `UP`. Eureka displays application names in uppercase even though configuration uses lowercase names.

## Data lifecycle

PostgreSQL and Cassandra use named volumes. `docker compose down` preserves them. `docker compose down -v` removes them and all demo database data.

Search and Notification projections are in memory, so restarting either application clears its data regardless of Docker volume state.

## Port 8761 conflict

The documented Compose mapping is `8761:8761`. If another local process already uses 8761, stop that process or use a temporary local Compose override that maps a different host port to container port 8761. Keep the services' internal Eureka URL unchanged (`http://discovery-server:8761/eureka/`).

## Image build notes

Each application Dockerfile builds from the root Maven reactor and packages its own module. A first build may take longer while Maven dependencies are downloaded. Rebuild only an affected service when appropriate:

```bash
docker compose build api-gateway
docker compose up -d api-gateway
```

Do not replace `OPENAPI_SERVER_URL` with a container hostname: it is consumed by a browser through Swagger UI and must resolve from the browser's network.
