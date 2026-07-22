# DevConnect Microservice Demo

DevConnect is a local Spring Boot microservices demonstration. It shows service discovery with Eureka, browser-facing routing through Spring Cloud Gateway, synchronous user validation with OpenFeign, and asynchronous post projections with Kafka.

## What runs where

```text
Browser / Swagger UI
        |
        v
API Gateway :8090  ----> Eureka :8761
   |        |        |        |
   v        v        v        v
User      Feed     Search  Notification
 :8081     :8082     :8083     :8084
   |        |          ^         ^
PostgreSQL Cassandra  \------- Kafka post-events -------/
             |
           Redis
```

Only the Gateway is a public API boundary. Browser clients and Swagger UI must call `http://localhost:8090`; service ports `8081`–`8084` are for internal Compose traffic and optional local debugging.

| Component | Eureka name | Default port | Responsibility |
| --- | --- | ---: | --- |
| API Gateway | `api-gateway` | 8090 | Public routes, CORS, aggregated Swagger UI |
| Discovery Server | `discovery-server` | 8761 | Eureka registry |
| User Service | `user-service` | 8081 | Users and user status |
| Feed Service | `feed-service` | 8082 | Posts and feed read models |
| Search Service | `search-service` | 8083 | In-memory post search projection |
| Notification Service | `notification-service` | 8084 | In-memory post notifications |

Feed Service uses Redis as a private, disposable two-level cache for posts and cursor-paged feed results. If Redis is unavailable, Feed reads fall back to Cassandra and cache degradation is exposed through actuator metrics.

## Quick start

Prerequisites: Docker Engine with Docker Compose. For a host-only run, use JDK 21 and Maven 3.9+.

```bash
docker compose up -d --build
docker compose ps
```

Wait until the application containers are healthy, then open:

| URL | Purpose |
| --- | --- |
| [Gateway Swagger UI](http://localhost:8090/swagger-ui.html) | Execute all public APIs through the Gateway |
| [Eureka dashboard](http://localhost:8761) | Confirm the four business services are registered as `UP` |
| [Kafka UI](http://localhost:8085) | Inspect the `post-events` topic |
| [pgAdmin](http://localhost:5050) | Browse and manage the local PostgreSQL database |
| [Cassandra Web](http://localhost:3001) | Inspect the local Cassandra keyspace and tables |

For database UI, Redis cache inspection, and connection details, see [Docker operations](docs/DOCKER.md). Public request and cursor-paging examples are in the [API reference](docs/API.md).

Create a user and post through the public boundary:

```bash
curl -i -X POST http://localhost:8090/api/users \
  -H 'Content-Type: application/json' \
  -d '{"userId":"u001","status":"ACTIVE","email":"u001@example.com"}'

curl -i -X POST http://localhost:8090/api/feed/posts \
  -H 'Content-Type: application/json' \
  -d '{"authorId":"u001","content":"Hello from DevConnect"}'
```

Search and notifications are eventually consistent because they consume Kafka events. Retry briefly after creating a post:

```bash
curl 'http://localhost:8090/api/search/posts?keyword=DevConnect'
curl 'http://localhost:8090/api/notifications/users/u001'
```

## Public API routes

| Service | Gateway route |
| --- | --- |
| User | `/api/users` and `/api/users/**` |
| Feed | `/api/feed/posts` and `/api/feed/posts/**` |
| Search | `/api/search` and `/api/search/**` |
| Notification | `/api/notifications` and `/api/notifications/**` |

The Gateway uses Eureka-backed `lb://` routes. The User Service status path `/internal/users/{userId}/status` is routed unchanged for service-to-service and documented testing; it is not a frontend-facing business API.

## CORS and OpenAPI

The Gateway owns browser CORS and serves one aggregated OpenAPI document at `/v3/api-docs/aggregate`. Its local allowed origin defaults to `http://localhost:8090` and is configurable with `GATEWAY_ALLOWED_ORIGIN`. The Gateway fetches each service's `/v3/api-docs` through Eureka, namespaces component schemas and operation IDs, and publishes the merged document to the embedded Swagger UI. The aggregate server URL is `http://localhost:8090`, so Swagger's **Execute** button calls the Gateway instead of service ports directly.

See [API reference](docs/API.md) and [OpenAPI and CORS](docs/OPENAPI.md) for requests, documents, and preflight verification.

## Build and test

Build all Maven modules from the repository root:

```bash
./mvnw clean verify
```

The current focused verification suite passes when excluding the legacy MVC-async expectation test:

```bash
./mvnw -Dtest='!FeedControllerAsyncTests' clean verify
```

`FeedControllerAsyncTests` currently expects an asynchronous MVC controller, while the checked-in Feed controller returns a synchronous `201 Created` response. This is a known test/code mismatch, not a Gateway or CORS failure.

## Documentation

Start with the [documentation index](docs/README.md).

- [Architecture](docs/ARCHITECTURE.md)
- [API reference](docs/API.md)
- [OpenAPI and CORS](docs/OPENAPI.md)
- [Local development](docs/DEVELOPMENT.md)
- [Docker operations](docs/DOCKER.md)
- [Database ownership](docs/DATABASE.md)
- [Event contract](docs/EVENTS.md)
- [Java asynchronous processing](ASYNC-JAVA.md)

## Demo limitations

This repository is a learning/demo system. Search and notification data are in memory and are lost on restart; Kafka consumers do not persist a deduplication record; and no application-level authentication/authorization filter chain is currently configured. Production deployment needs durable projections, idempotency, observability, secrets management, and an explicit security design.
