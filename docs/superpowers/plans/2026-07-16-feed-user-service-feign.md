# Feed-to-User OpenFeign Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove feed-service `RestClient` communication and replace it with a tested OpenFeign client wrapped by `UserServiceAdapter`.

**Architecture:** `UserServiceClient` declares the user-status HTTP endpoint, while `UserServiceAdapter` validates the response envelope and maps Feign failures to feed-service exceptions. `FeedService` depends only on the adapter, preserving its parallel post-validation workflow without transport-specific code.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Spring Cloud 2025.1.2, Spring Cloud OpenFeign, JUnit 5, Mockito, Maven

## Global Constraints

- Keep `/internal/users/{userId}/status` and `ApiResponse<UserStatusResponse>` unchanged.
- Preserve `BusinessException("Author not found")` for HTTP 404 and invalid envelopes.
- Preserve `DownstreamServiceException("Failed to call User Service", cause)` for other Feign failures.
- Keep `FeedService` author/content `thenCombine` behavior, controller responses, persistence, and events unchanged.
- Remove `RestClient`, `RestClientException`, `RestClientConfig`, and `MockRestServiceServer` from feed-service.
- Configure `user-service` URL, 5,000 ms connect timeout, and 5,000 ms read timeout through OpenFeign properties.
- Do not implement API Gateway, service discovery, retries, circuit breakers, or user-service changes.
- Preserve unrelated workspace changes.

---

## File Structure

- Modify `pom.xml`: import the Spring Cloud 2025.1.2 BOM.
- Modify `feed-service/pom.xml`: add `spring-cloud-starter-openfeign`.
- Create `feed-service/src/main/java/com/devconnect/feed/client/UserServiceClient.java`: declarative HTTP contract.
- Create `feed-service/src/main/java/com/devconnect/feed/client/UserServiceAdapter.java`: response validation and exception translation.
- Delete `feed-service/src/main/java/com/devconnect/feed/config/RestClientConfig.java`: obsolete transport configuration after `FeedService` migrates.
- Modify `feed-service/src/main/java/com/devconnect/feed/FeedServiceApplication.java`: enable the specific Feign client.
- Modify `feed-service/src/main/resources/application.yaml`: named Feign URL and timeout configuration.
- Create `feed-service/src/test/java/com/devconnect/feed/client/UserServiceAdapterTests.java`: adapter behavior coverage.
- Modify `feed-service/src/test/java/com/devconnect/feed/FeedServiceApplicationTests.java`: generated Feign bean wiring coverage.
- Modify `feed-service/src/main/java/com/devconnect/feed/service/FeedService.java`: inject adapter and remove RestClient code.
- Modify two feed-service service tests: mock the adapter instead of a mock HTTP server.

### Task 1: Add and verify the Feign communication boundary

**Files:**
- Modify: `pom.xml`
- Modify: `feed-service/pom.xml`
- Create: `feed-service/src/main/java/com/devconnect/feed/client/UserServiceClient.java`
- Create: `feed-service/src/main/java/com/devconnect/feed/client/UserServiceAdapter.java`
- Modify: `feed-service/src/main/java/com/devconnect/feed/FeedServiceApplication.java`
- Modify: `feed-service/src/main/resources/application.yaml`
- Create: `feed-service/src/test/java/com/devconnect/feed/client/UserServiceAdapterTests.java`
- Modify: `feed-service/src/test/java/com/devconnect/feed/FeedServiceApplicationTests.java`

**Interfaces:**
- Produces: `ApiResponse<UserStatusResponse> UserServiceClient.getUserStatus(String userId)`
- Produces: `UserStatusResponse UserServiceAdapter.getUserStatus(String userId)`

- [ ] **Step 1: Write failing adapter and context tests**

Create `UserServiceAdapterTests` with a mocked `UserServiceClient`. Cover these four
behaviors:

```java
@Test
void returnsUserStatusFromSuccessfulEnvelope() {
    UserStatusResponse expected = new UserStatusResponse("u001", "ACTIVE");
    when(client.getUserStatus("u001"))
            .thenReturn(ApiResponse.success("User status found", expected));

    assertEquals(expected, adapter.getUserStatus("u001"));
}

@Test
void rejectsInvalidResponseEnvelopeAsMissingAuthor() {
    when(client.getUserStatus("u404"))
            .thenReturn(ApiResponse.error("User not found"));

    BusinessException exception = assertThrows(
            BusinessException.class,
            () -> adapter.getUserStatus("u404")
    );
    assertEquals("Author not found", exception.getMessage());
}

@Test
void mapsFeignNotFoundToMissingAuthor() {
    when(client.getUserStatus("u404")).thenThrow(feignException(404));

    BusinessException exception = assertThrows(
            BusinessException.class,
            () -> adapter.getUserStatus("u404")
    );
    assertEquals("Author not found", exception.getMessage());
}

@Test
void mapsOtherFeignFailuresToDownstreamFailure() {
    when(client.getUserStatus("u001")).thenThrow(feignException(503));

    DownstreamServiceException exception = assertThrows(
            DownstreamServiceException.class,
            () -> adapter.getUserStatus("u001")
    );
    assertEquals("Failed to call User Service", exception.getMessage());
}
```

Use this helper to construct real Feign exceptions:

```java
private FeignException feignException(int status) {
    Request request = Request.create(
            Request.HttpMethod.GET,
            "/internal/users/u001/status",
            Map.of(),
            null,
            StandardCharsets.UTF_8,
            null
    );
    Response response = Response.builder()
            .status(status)
            .reason("test")
            .request(request)
            .headers(Map.of())
            .build();
    return FeignException.errorStatus("getUserStatus", response);
}
```

In `FeedServiceApplicationTests`, autowire `UserServiceClient` and assert it is not
null in `contextLoads`:

```java
@Autowired
private UserServiceClient userServiceClient;

@Test
void contextLoads() {
    assertNotNull(userServiceClient);
}
```

- [ ] **Step 2: Run tests and verify RED**

```powershell
mvn -pl feed-service '-Dtest=UserServiceAdapterTests,FeedServiceApplicationTests' test
```

Expected: test compilation fails because OpenFeign, `UserServiceClient`, and
`UserServiceAdapter` do not exist.

- [ ] **Step 3: Add Spring Cloud dependency management and starter**

Add to root `pom.xml` properties:

```xml
<spring-cloud.version>2025.1.2</spring-cloud.version>
```

Add root dependency management after properties:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-dependencies</artifactId>
            <version>${spring-cloud.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

Add to `feed-service/pom.xml` dependencies:

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-openfeign</artifactId>
</dependency>
```

- [ ] **Step 4: Implement UserServiceClient and UserServiceAdapter**

Create `UserServiceClient.java`:

```java
package com.devconnect.feed.client;

import com.devconnect.feed.dto.ApiResponse;
import com.devconnect.feed.dto.UserStatusResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "user-service")
public interface UserServiceClient {

    @GetMapping("/internal/users/{userId}/status")
    ApiResponse<UserStatusResponse> getUserStatus(
            @PathVariable("userId") String userId
    );
}
```

Create `UserServiceAdapter.java`:

```java
package com.devconnect.feed.client;

import com.devconnect.feed.dto.ApiResponse;
import com.devconnect.feed.dto.UserStatusResponse;
import com.devconnect.feed.exception.BusinessException;
import com.devconnect.feed.exception.DownstreamServiceException;
import feign.FeignException;
import org.springframework.stereotype.Component;

@Component
public class UserServiceAdapter {

    private final UserServiceClient userServiceClient;

    public UserServiceAdapter(UserServiceClient userServiceClient) {
        this.userServiceClient = userServiceClient;
    }

    public UserStatusResponse getUserStatus(String userId) {
        try {
            ApiResponse<UserStatusResponse> response =
                    userServiceClient.getUserStatus(userId);
            if (response == null || !response.isSuccess() || response.getData() == null) {
                throw new BusinessException("Author not found");
            }
            return response.getData();
        } catch (FeignException.NotFound exception) {
            throw new BusinessException("Author not found");
        } catch (FeignException exception) {
            throw new DownstreamServiceException(
                    "Failed to call User Service",
                    exception
            );
        }
    }
}
```

- [ ] **Step 5: Enable and configure Feign alongside the current client**

Annotate `FeedServiceApplication`:

```java
@EnableFeignClients(clients = UserServiceClient.class)
@SpringBootApplication
public class FeedServiceApplication {
```

Add the named Feign configuration in `application.yaml` while retaining the old
`services.user-service.base-url` property until Task 2:

```yaml
spring:
  cloud:
    openfeign:
      client:
        config:
          user-service:
            url: ${USER_SERVICE_BASE_URL:http://localhost:8081}
            connectTimeout: 5000
            readTimeout: 5000
```

- [ ] **Step 6: Run boundary tests and verify GREEN**

```powershell
mvn -pl feed-service '-Dtest=UserServiceAdapterTests,FeedServiceApplicationTests' test
```

Expected: PASS; adapter mapping and generated Feign bean wiring are verified.

- [ ] **Step 7: Commit Task 1 only**

Stage and commit only Task 1 paths:

```powershell
git add -- pom.xml feed-service/pom.xml feed-service/src/main/java/com/devconnect/feed/client/UserServiceClient.java feed-service/src/main/java/com/devconnect/feed/client/UserServiceAdapter.java feed-service/src/main/java/com/devconnect/feed/FeedServiceApplication.java feed-service/src/main/resources/application.yaml feed-service/src/test/java/com/devconnect/feed/client/UserServiceAdapterTests.java feed-service/src/test/java/com/devconnect/feed/FeedServiceApplicationTests.java
git commit --only -m "feat: add user service feign adapter" -- pom.xml feed-service/pom.xml feed-service/src/main/java/com/devconnect/feed/client/UserServiceClient.java feed-service/src/main/java/com/devconnect/feed/client/UserServiceAdapter.java feed-service/src/main/java/com/devconnect/feed/FeedServiceApplication.java feed-service/src/main/resources/application.yaml feed-service/src/test/java/com/devconnect/feed/client/UserServiceAdapterTests.java feed-service/src/test/java/com/devconnect/feed/FeedServiceApplicationTests.java
```

### Task 2: Replace FeedService RestClient dependency

**Files:**
- Modify: `feed-service/src/main/java/com/devconnect/feed/service/FeedService.java`
- Delete: `feed-service/src/main/java/com/devconnect/feed/config/RestClientConfig.java`
- Modify: `feed-service/src/main/resources/application.yaml`
- Modify: `feed-service/src/test/java/com/devconnect/feed/service/FeedServiceParallelValidationTests.java`
- Modify: `feed-service/src/test/java/com/devconnect/feed/service/FeedServiceUserContractTests.java`

**Interfaces:**
- Consumes: `UserStatusResponse UserServiceAdapter.getUserStatus(String userId)`
- Preserves: synchronous `PostResponse FeedService.createPost(CreatePostRequest request)` with parallel validation

- [ ] **Step 1: Convert service tests to the adapter contract**

Remove `RestClient`, `MockRestServiceServer`, media type, request matcher, and mock
response imports/setup from both tests. Add a mocked `UserServiceAdapter`, pass it to
the `FeedService` constructor, and stub active/inactive responses:

```java
userServiceAdapter = mock(UserServiceAdapter.class);
when(userServiceAdapter.getUserStatus("u001"))
        .thenReturn(new UserStatusResponse("u001", "ACTIVE"));
```

For inactive behavior:

```java
when(userServiceAdapter.getUserStatus("u004"))
        .thenReturn(new UserStatusResponse("u004", "INACTIVE"));
```

Replace `server.verify()` with:

```java
verify(userServiceAdapter).getUserStatus(expectedUserId);
```

Keep concurrency, persistence, publication, and prohibited-content assertions.

- [ ] **Step 2: Run service tests and verify RED**

```powershell
mvn -pl feed-service '-Dtest=FeedServiceParallelValidationTests,FeedServiceUserContractTests' test
```

Expected: test compilation fails because `FeedService` still requires `RestClient`.

- [ ] **Step 3: Inject UserServiceAdapter and remove RestClient logic**

Replace the field and constructor parameter:

```java
private final UserServiceAdapter userServiceAdapter;

public FeedService(
        UserServiceAdapter userServiceAdapter,
        PostEventPublisher postEventPublisher,
        PostStore postStore,
        @Qualifier("postTaskExecutor") Executor postTaskExecutor
) {
    this.userServiceAdapter = userServiceAdapter;
    this.postEventPublisher = postEventPublisher;
    this.postStore = postStore;
    this.postTaskExecutor = postTaskExecutor;
}
```

Replace `getAuthorStatus` with:

```java
private UserStatusResponse getAuthorStatus(String authorId) {
    log.info("Getting author status on thread: {}", Thread.currentThread().getName());
    return userServiceAdapter.getUserStatus(authorId);
}
```

Remove imports for feed `ApiResponse`, `DownstreamServiceException`,
`ParameterizedTypeReference`, `HttpStatusCode`, `RestClient`, and
`RestClientException`.

Delete `RestClientConfig.java` and remove the old configuration block:

```yaml
services:
  user-service:
    base-url: ${USER_SERVICE_BASE_URL:http://localhost:8081}
```

- [ ] **Step 4: Run service tests and verify GREEN**

```powershell
mvn -pl feed-service '-Dtest=FeedServiceParallelValidationTests,FeedServiceUserContractTests' test
```

Expected: PASS; parallel validation and author business rules are unchanged.

- [ ] **Step 5: Run full feed-service regression**

```powershell
mvn -pl feed-service test
```

Expected: BUILD SUCCESS with all feed-service tests passing.

- [ ] **Step 6: Commit Task 2 only**

Stage and commit only Task 2 paths:

```powershell
git add -- feed-service/src/main/java/com/devconnect/feed/service/FeedService.java feed-service/src/main/java/com/devconnect/feed/config/RestClientConfig.java feed-service/src/main/resources/application.yaml feed-service/src/test/java/com/devconnect/feed/service/FeedServiceParallelValidationTests.java feed-service/src/test/java/com/devconnect/feed/service/FeedServiceUserContractTests.java
git commit --only -m "refactor: use feign adapter in feed service" -- feed-service/src/main/java/com/devconnect/feed/service/FeedService.java feed-service/src/main/java/com/devconnect/feed/config/RestClientConfig.java feed-service/src/main/resources/application.yaml feed-service/src/test/java/com/devconnect/feed/service/FeedServiceParallelValidationTests.java feed-service/src/test/java/com/devconnect/feed/service/FeedServiceUserContractTests.java
```

### Task 3: Final migration verification

**Files:**
- Verify only; no planned source changes.

**Interfaces:**
- Consumes: completed Feign boundary and FeedService migration
- Produces: verified absence of RestClient and working feed-service build

- [ ] **Step 1: Prove RestClient removal**

```powershell
rg -n "RestClient|RestClientException|RestClientConfig|MockRestServiceServer" feed-service
```

Expected: no matches.

- [ ] **Step 2: Prove Feign dependency and code structure**

```powershell
rg -n "spring-cloud-starter-openfeign|EnableFeignClients|FeignClient|UserServiceAdapter" pom.xml feed-service
mvn -pl feed-service dependency:tree '-Dincludes=org.springframework.cloud:spring-cloud-starter-openfeign'
```

Expected: BOM/starter, bootstrap annotation, client, and adapter matches; dependency tree contains `spring-cloud-starter-openfeign`.

- [ ] **Step 3: Run final checks**

```powershell
git diff --check
mvn -pl feed-service test
git status --short
```

Expected: no whitespace errors, all feed-service tests pass, and unrelated pre-existing documentation changes remain untouched.
