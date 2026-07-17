# Swagger/OpenAPI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add live Swagger UI and OpenAPI JSON documentation for all HTTP endpoints in user, feed, search, and notification services.

**Architecture:** Add the Springdoc Web MVC UI starter to each independent service. Put service-level OpenAPI metadata on each application class and operation-level descriptions/tags on existing controllers; keep all business code, routes, DTOs, envelopes, and error handling unchanged.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Spring MVC, springdoc-openapi-starter-webmvc-ui 3.0.3, OpenAPI 3, JUnit 5, MockMvc, Maven.

## Global Constraints

- Preserve every existing endpoint path, HTTP method, status code, request field, response shape, and error envelope.
- Use springdoc 3.0.3, the Spring Boot 4-compatible release line.
- Add the dependency to all four service POMs.
- Do not add authentication/security schemes because the project has no authentication.
- Include the internal user-status endpoint but do not add email to its status DTO.
- Document 9 HTTP operations: 3 User, 3 Feed, 1 Search, and 1 Notification.
- Add only documentation metadata and focused OpenAPI tests; do not alter business logic.

---

### Task 1: Add Springdoc dependency and service metadata

**Files:**
- Modify: `user-service/pom.xml`
- Modify: `feed-service/pom.xml`
- Modify: `search-service/pom.xml`
- Modify: `notification-service/pom.xml`
- Modify: each service application class
- Modify: each service `src/main/resources/application.yaml`
- Test: existing application-context test classes

**Interfaces:**
- Consumes: existing Spring Boot MVC applications and local ports.
- Produces: `/swagger-ui.html` and `/v3/api-docs` for each service.

- [ ] **Step 1: Add the dependency to each POM**

Add this compile dependency to all four service POMs:

~~~xml
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    <version>3.0.3</version>
</dependency>
~~~

- [ ] **Step 2: Add service-level OpenAPI metadata**

Annotate each `@SpringBootApplication` class with `@OpenAPIDefinition`, `@Info`, and `@Server`:

~~~java
@OpenAPIDefinition(
        info = @Info(
                title = "DevConnect User Service API",
                version = "1.0.0",
                description = "User creation, update, and internal status APIs."
        ),
        servers = @Server(url = "http://localhost:8081")
)
~~~

Use titles/descriptions and ports appropriate to User (8081), Feed (8082), Search (8083), and Notification (8084). Keep the Compose host mapping (User is reachable at localhost:3000) in docs, not in the generated server URL.

- [ ] **Step 3: Set stable documentation paths**

Add to each service YAML:

~~~yaml
springdoc:
  api-docs:
    path: /v3/api-docs
  swagger-ui:
    path: /swagger-ui.html
~~~

- [ ] **Step 4: Run context tests to verify dependency wiring**

Run:

~~~powershell
mvn -pl user-service,feed-service,search-service,notification-service test
~~~

Expected: existing tests compile and Spring contexts start; no endpoint behavior changes.

---

### Task 2: Document User and Feed APIs

**Files:**
- Modify: `user-service/src/main/java/com/devconnect/user/controller/UserInternalController.java`
- Modify: User request/response DTO records with `@Schema` where examples/constraints improve generated docs.
- Modify: `feed-service/src/main/java/com/devconnect/feed/controller/FeedController.java`
- Modify: Feed request/response DTO records with `@Schema` where examples/constraints improve generated docs.
- Test: `user-service/src/test/java/com/devconnect/user/controller/UserOpenApiTests.java`
- Test: `feed-service/src/test/java/com/devconnect/feed/controller/FeedOpenApiTests.java`

**Interfaces:**
- Consumes: existing User/Feed controllers and DTOs.
- Produces: tagged, described OpenAPI operations for six endpoints with documented error statuses.

- [ ] **Step 1: Write failing OpenAPI endpoint tests**

Add MVC tests that request `/v3/api-docs`, assert HTTP 200, and assert the generated JSON contains the expected paths:

~~~java
@WebMvcTest(UserInternalController.class)
class UserOpenApiTests {
    @Autowired
    MockMvc mockMvc;

    @Test
    void publishesUserPathsInOpenApiDocument() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/users']").exists())
                .andExpect(jsonPath("$.paths['/api/users/{userId}']").exists())
                .andExpect(jsonPath("$.paths['/internal/users/{userId}/status']").exists());
    }
}
~~~

Use the equivalent Feed test for `/api/feed/posts` and `/api/feed/posts/{postId}`.

- [ ] **Step 2: Run the focused tests to verify RED**

Run:

~~~powershell
mvn -pl user-service,feed-service -Dtest=UserOpenApiTests,FeedOpenApiTests test
~~~

Expected: compilation fails because the new test classes do not exist yet.

- [ ] **Step 3: Annotate User operations**

Add `@Tag(name = "Users")` to the controller. Add `@Operation` and `@ApiResponses`:

- POST `/api/users`: 201 created, 400 validation, 409 duplicate, 500 unexpected.
- PUT `/api/users/{userId}`: 200 updated, 400 validation, 404 missing, 409 duplicate, 500 unexpected.
- GET `/internal/users/{userId}/status`: 200 found, 404 missing; describe it as service-to-service.

Annotate path parameters with descriptions/examples. Add `@Schema` examples and descriptions to `CreateUserRequest`, `UpdateUserRequest`, `UserResponse`, and `UserStatusResponse`; preserve all validation annotations and fields.

- [ ] **Step 4: Annotate Feed operations**

Add `@Tag(name = "Feed Posts")`. Document:

- POST `/api/feed/posts`: direct `PostResponse` 201, 400 business/validation, 503 User Service failure, 500 unexpected.
- GET `/api/feed/posts`: `ApiResponse<List<PostResponse>>` 200.
- GET `/api/feed/posts/{postId}`: `ApiResponse<PostResponse>` 200, 400/500 according to existing handler behavior.

Add examples/descriptions to `CreatePostRequest` and `PostResponse`; do not alter their JSON fields.

- [ ] **Step 5: Run User/Feed OpenAPI tests and all module tests**

Run:

~~~powershell
mvn -pl user-service,feed-service test
~~~

Expected: OpenAPI path tests and all existing tests pass.

---

### Task 3: Document Search and Notification APIs

**Files:**
- Modify: `search-service/src/main/java/com/devconnect/search/SearchServiceApplication.java`
- Modify: `search-service/src/main/java/com/devconnect/search/controller/SearchController.java`
- Modify: `search-service/src/main/java/com/devconnect/search/dto/SearchPostResponse.java`
- Modify: `notification-service/src/main/java/com/devconnect/notification/NotificationServiceApplication.java`
- Modify: `notification-service/src/main/java/com/devconnect/notification/controller/NotificationController.java`
- Modify: `notification-service/src/main/java/com/devconnect/notification/dto/NotificationResponse.java`
- Test: `search-service/src/test/java/com/devconnect/search/controller/SearchOpenApiTests.java`
- Test: `notification-service/src/test/java/com/devconnect/notification/controller/NotificationOpenApiTests.java`

**Interfaces:**
- Consumes: existing search and notification controller methods.
- Produces: tagged OpenAPI paths for search and notifications with query/path parameter descriptions.

- [ ] **Step 1: Write failing OpenAPI tests**

Assert `/v3/api-docs` returns 200 and contains `/api/search/posts` in Search and `/api/notifications/users/{userId}` in Notification.

- [ ] **Step 2: Run RED**

Run:

~~~powershell
mvn -pl search-service,notification-service -Dtest=SearchOpenApiTests,NotificationOpenApiTests test
~~~

Expected: compilation fails because the new test classes do not exist.

- [ ] **Step 3: Annotate Search**

Add `@Tag(name = "Search")` and an operation description for `GET /api/search/posts`. Document required `keyword` query parameter and 200 response `ApiResponse<List<SearchPostResponse>>`. Add schema descriptions for the response record fields without changing serialization.

- [ ] **Step 4: Annotate Notification**

Add `@Tag(name = "Notifications")` and an operation description for `GET /api/notifications/users/{userId}`. Document the path parameter and 200 response `ApiResponse<List<NotificationResponse>>`. Add schema descriptions/examples for notification fields.

- [ ] **Step 5: Run all Search/Notification tests**

Run:

~~~powershell
mvn -pl search-service,notification-service test
~~~

Expected: all tests pass.

---

### Task 4: OpenAPI usage documentation

**Files:**
- Create: `docs/OPENAPI.md`
- Modify: `docs/README.md`
- Modify: `README.md`

**Interfaces:**
- Consumes: generated service endpoints from Tasks 1-3.
- Produces: copy-pasteable Swagger UI and JSON URLs.

- [ ] **Step 1: Create the service URL table**

Document:

| Service | Swagger UI | JSON |
|---|---|---|
| User | `http://localhost:3000/swagger-ui.html` | `http://localhost:3000/v3/api-docs` |
| Feed | `http://localhost:8082/swagger-ui.html` | `http://localhost:8082/v3/api-docs` |
| Search | `http://localhost:8083/swagger-ui.html` | `http://localhost:8083/v3/api-docs` |
| Notification | `http://localhost:8084/swagger-ui.html` | `http://localhost:8084/v3/api-docs` |

Explain that User's container port is 8081 but Compose maps it to host port 3000.

- [ ] **Step 2: Add local startup and troubleshooting instructions**

Document `mvn clean verify`, `docker compose up --build` when Docker is available, no authentication requirement, and that each service exposes an independent document.

- [ ] **Step 3: Link the guide from project docs**

Add an OpenAPI/Swagger link to the documentation index and root README without changing existing API examples.

- [ ] **Step 4: Search for stale documentation**

Run:

~~~powershell
rg -n "swagger|openapi|v3/api-docs|springdoc" README.md docs
~~~

Expected: URLs and dependency/version notes are consistent.

---

### Task 5: Full verification

**Files:** Verify only; modify scoped files only if a failing test exposes a documentation defect.

- [ ] **Step 1: Run every module test**

~~~powershell
mvn clean verify
~~~

Expected: reactor reports BUILD SUCCESS and zero test failures/errors.

- [ ] **Step 2: Verify generated JSON paths**

Start services if the environment supports it, then request each `/v3/api-docs` endpoint and check all nine paths. If Docker is unavailable, retain MockMvc coverage and report the environment limitation.

- [ ] **Step 3: Validate diff**

~~~powershell
git diff --check
git status --short
~~~

Expected: no whitespace errors; only Swagger dependency, metadata, annotations, focused tests, and documentation changes are attributable to this task.

