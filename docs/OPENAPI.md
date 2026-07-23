# OpenAPI, Swagger UI, and CORS

## One browser-facing entry point

Swagger UI is served by the API Gateway:

```text
http://localhost:8090/swagger-ui.html
```

Swagger UI loads one aggregate document:

```text
http://localhost:8090/v3/api-docs/aggregate
```

The API Gateway fetches the existing `/v3/api-docs` document from each service through Eureka, then merges the documents before returning them to Swagger UI:

```text
Gateway /v3/api-docs/aggregate
  ├── user-service       /v3/api-docs
  ├── feed-service       /v3/api-docs
  ├── search-service     /v3/api-docs
  └── notification-service /v3/api-docs
```

The individual same-origin proxy URLs remain available for inspection and compatibility:

| Service | Gateway document URL |
| --- | --- |
| User Service | `http://localhost:8090/openapi/user/v3/api-docs` |
| Feed Service | `http://localhost:8090/openapi/feed/v3/api-docs` |
| Search Service | `http://localhost:8090/openapi/search/v3/api-docs` |
| Notification Service | `http://localhost:8090/openapi/notification/v3/api-docs` |

The aggregate namespaces component names and operation IDs with the service name. This prevents shared names such as `ApiResponse` from overwriting one another while preserving the original API paths.

Each service defines one OpenAPI `Server` from `app.openapi.server-url`. The default is:

```yaml
app:
  openapi:
    server-url: ${OPENAPI_SERVER_URL:http://localhost:8090}
```

Set `OPENAPI_SERVER_URL` in a deployment environment when its public Gateway URL differs. Never set it to a Docker hostname or an individual service port for browser documentation.

## Gateway routes used by Swagger execution

| API family | Route target |
| --- | --- |
| `/api/users/**` | `lb://user-service` |
| `/api/feed/posts/**` | `lb://feed-service` |
| `/api/search/**` | `lb://search-service` |
| `/api/notifications/**` | `lb://notification-service` |

The Gateway is Spring Cloud Gateway WebFlux. The aggregate controller calls services with Eureka-backed load balancing. Individual Swagger document proxy routes rewrite only `/openapi/{service}/v3/api-docs` to `/v3/api-docs`; business API paths are not rewritten.

The merged document declares `http://localhost:8090` as its server URL. Consequently, Swagger **Try it out** calls remain on the Gateway and use the normal business routes, such as `/api/users`, `/api/feed/posts`, `/api/search/posts`, and `/api/notifications/users/{userId}`.

For `GET /api/feed/posts`, Swagger exposes `pageSize`, `lastCreatedAt`, and
`lastPostId` query parameters. Start without cursor fields; when `hasNext` is true,
send both returned next cursor fields with the next page request. The Feed
operation still executes through `http://localhost:8090` on the Gateway.

## CORS policy

CORS is configured once at the Gateway with WebFlux global CORS. The development allowed origin defaults to `http://localhost:8090` and can be overridden by `GATEWAY_ALLOWED_ORIGIN`.

Allowed methods are `GET`, `POST`, `PUT`, `PATCH`, `DELETE`, and `OPTIONS`. Required request headers include `Content-Type`, `Authorization`, `Accept`, and `Origin`. Credentials are allowed, so the configuration does not use wildcard origins. Service-level CORS filters were removed to avoid duplicate `Access-Control-Allow-Origin` headers.

Same-origin requests from the Swagger UI do not require CORS headers. Cross-origin frontend requests require an origin that matches `GATEWAY_ALLOWED_ORIGIN`; configure that exact development frontend origin instead of adding a broad wildcard.

## Verify preflight

Use a different host spelling for a cross-origin check while targeting the same Gateway process:

```bash
curl -i -X OPTIONS http://127.0.0.1:8090/api/users \
  -H 'Origin: http://localhost:8090' \
  -H 'Access-Control-Request-Method: POST' \
  -H 'Access-Control-Request-Headers: content-type,authorization'
```

Repeat with `/api/feed/posts`, `/api/search/posts`, and `/api/notifications/users/u001`. A valid response includes the matching `Access-Control-Allow-Origin`, allowed methods and headers, and credentials setting; it must not return `401`, `403`, `404`, or `5xx`.

## Troubleshooting

- If Swagger sends a request to `8081`, `8082`, `8083`, or `8084`, inspect the service's `OPENAPI_SERVER_URL` and restart it.
- If `/v3/api-docs/aggregate` returns `503` or `500`, confirm all four business services are `UP` in Eureka and inspect the Gateway log for the unavailable service.
- If a proxied document returns `503`, confirm the corresponding service is `UP` in Eureka.
- If a browser preflight fails, confirm the browser origin exactly matches `GATEWAY_ALLOWED_ORIGIN` and that the request is going to port 8090.
- The Gateway's standard `/v3/api-docs` and `/v3/api-docs/swagger-config` endpoints remain enabled for Springdoc UI support; the merged contract is served separately at `/v3/api-docs/aggregate`.
