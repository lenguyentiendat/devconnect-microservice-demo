# Swagger/OpenAPI Documentation Design

## Goal

Expose complete, live OpenAPI 3 documentation and Swagger UI for every HTTP endpoint in the four existing Spring MVC services without changing runtime API paths, payloads, status codes, or authentication behavior.

## Current Context

The project is a Maven multi-module Spring Boot 4.1.0 application with:

- user-service: 3 endpoints (2 public write endpoints and 1 internal status endpoint)
- feed-service: 3 endpoints (create/list/get post)
- search-service: 1 endpoint (search posts)
- notification-service: 1 endpoint (list notifications by user)
- Existing ApiResponse<T> envelopes in User, Feed, Search, and Notification services
- No authentication and no existing OpenAPI dependency
- Ports 8081, 8082, 8083, and 8084 inside the local services; User Service is exposed as host port 3000 by Compose

## Selected Approach

Add org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.3 to each service. This release line supports Spring Boot 4 and supplies both the generated JSON document and Swagger UI.

Each service will:

- expose /v3/api-docs for JSON OpenAPI
- expose /swagger-ui.html for interactive documentation
- have an @OpenAPIDefinition with service title, version, description, and local server URL
- annotate every controller with a meaningful tag
- annotate every operation with summary, description, path/query parameter intent, request/response semantics, and documented error status codes
- rely on Springdoc's return-type inspection for existing DTO and generic envelope schemas
- expose no security scheme because the current project has no authentication

## API Coverage

| Service | Operations |
|---|---|
| User | POST /api/users, PUT /api/users/{userId}, GET /internal/users/{userId}/status |
| Feed | POST /api/feed/posts, GET /api/feed/posts, GET /api/feed/posts/{postId} |
| Search | GET /api/search/posts?keyword=... |
| Notification | GET /api/notifications/users/{userId} |

The internal user-status operation is included and explicitly marked as service-to-service in its description. Email remains only in the existing public user profile DTOs; Swagger must not invent an email field in the internal status schema.

## Schema and Error Documentation

Use @Schema on request/response record components where constraints or examples add information that Bean Validation alone does not convey. Keep Java validation annotations as the runtime source of truth.

Document the existing error envelope (success, message, data) and endpoint-specific statuses:

- User: 400, 404, 409, 500 as applicable
- Feed: 400, 503, 500
- Search: 200 and framework 400 for missing query input
- Notification: 200

Do not introduce new exception handlers, response wrappers, endpoint paths, or status codes solely for Swagger.

## Runtime and Testing

Add a focused Spring MVC test per service where feasible, asserting that /v3/api-docs responds with HTTP 200 and contains the expected endpoint path. Existing controller tests remain the behavioral source of truth. Run each module's tests and the full Maven reactor build.

## Documentation

Add docs/OPENAPI.md containing:

- service-by-service Swagger UI and JSON URLs
- the four service ports and Compose host mapping
- a note that schemas are generated from live controllers/DTOs
- local startup instructions and the absence of authentication

## Non-Goals

- No central API gateway or merged multi-service OpenAPI document
- No authentication/security scheme
- No API version/path migration
- No changes to business logic, persistence, events, or Feign contracts

