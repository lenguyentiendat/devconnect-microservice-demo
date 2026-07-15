# User Create and Update Endpoints Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add create-user and strict update-user endpoints to `user-service` while preserving the internal status contract consumed by `feed-service`.

**Architecture:** Add both public write methods to the existing `UserInternalController`, moving its existing class-level route to explicit method-level mappings. Keep persistence and business rules in `UserService`, use focused request/response records and domain exceptions, and translate failures through a centralized exception handler using the existing `ApiResponse` envelope.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Spring MVC, Jakarta Bean Validation, Spring Data JPA, Flyway, PostgreSQL, H2, JUnit 5, Mockito, Maven, Docker Compose.

## Global Constraints

- `POST /api/users` accepts `userId` and `status`; success returns `201 Created`.
- `PUT /api/users/{userId}` accepts only `status`; success returns `200 OK`.
- Valid statuses are exactly `ACTIVE` and `INACTIVE`.
- Duplicate create returns `409 Conflict`; update of an unknown user returns `404 Not Found` and never upserts.
- `GET /internal/users/{userId}/status` and its response contract must remain unchanged for `feed-service`.
- All responses use the current `ApiResponse` envelope.
- No database migration, authentication, delete/list API, profile fields, or user Kafka events are added.
- Preserve unrelated untracked files and existing user changes.

---

## File map

- Create `user-service/src/main/java/com/devconnect/user/dto/CreateUserRequest.java`: validated create payload.
- Create `user-service/src/main/java/com/devconnect/user/dto/UpdateUserRequest.java`: validated update payload.
- Create `user-service/src/main/java/com/devconnect/user/dto/UserResponse.java`: public write response.
- Create `user-service/src/main/java/com/devconnect/user/exception/UserAlreadyExistsException.java`: duplicate-create signal.
- Create `user-service/src/main/java/com/devconnect/user/exception/UserNotFoundException.java`: missing-update signal.
- Create `user-service/src/main/java/com/devconnect/user/exception/GlobalExceptionHandler.java`: standard HTTP error mapping.
- Modify `user-service/src/main/java/com/devconnect/user/persistence/UserEntity.java`: focused status mutation.
- Modify `user-service/src/main/java/com/devconnect/user/service/UserService.java`: create/update business rules.
- Modify `user-service/src/main/java/com/devconnect/user/controller/UserInternalController.java`: existing internal GET plus new public POST/PUT.
- Modify `user-service/pom.xml`: add Bean Validation starter.
- Create `user-service/src/test/java/com/devconnect/user/service/UserServiceTests.java`: isolated service rules.
- Create `user-service/src/test/java/com/devconnect/user/controller/UserInternalControllerTests.java`: MVC contract and validation.
- Modify `user-service/src/test/java/com/devconnect/user/UserServiceApplicationTests.java`: H2/Flyway persistence integration.
- Create `feed-service/src/test/java/com/devconnect/feed/service/FeedServiceUserContractTests.java`: consumer regression for the unchanged internal API.
- Modify `README.md`, `docs/API.md`, `docs/ARCHITECTURE.md`, and `docs/DATABASE.md`: API and architecture documentation.

---

### Task 1: User domain and service behavior

**Files:**
- Create: `user-service/src/main/java/com/devconnect/user/dto/UserResponse.java`
- Create: `user-service/src/main/java/com/devconnect/user/exception/UserAlreadyExistsException.java`
- Create: `user-service/src/main/java/com/devconnect/user/exception/UserNotFoundException.java`
- Modify: `user-service/src/main/java/com/devconnect/user/persistence/UserEntity.java`
- Modify: `user-service/src/main/java/com/devconnect/user/service/UserService.java`
- Test: `user-service/src/test/java/com/devconnect/user/service/UserServiceTests.java`

**Interfaces:**
- Consumes: `UserRepository.existsById(String)`, `findById(String)`, and `saveAndFlush(UserEntity)`.
- Produces: `UserService.createUser(String userId, String status): UserResponse`, `UserService.updateUser(String userId, String status): UserResponse`, and `UserEntity.updateStatus(String): void`.

- [ ] **Step 1: Write failing service tests**

Create `UserServiceTests` with Mockito-backed tests for successful creation, early duplicate detection, concurrent duplicate protection, successful update, and missing update target:

```java
package com.devconnect.user.service;

import com.devconnect.user.exception.UserAlreadyExistsException;
import com.devconnect.user.exception.UserNotFoundException;
import com.devconnect.user.persistence.UserEntity;
import com.devconnect.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceTests {

    private UserRepository userRepository;
    private UserService userService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        userService = new UserService(userRepository);
    }

    @Test
    void createsUser() {
        when(userRepository.existsById("u004")).thenReturn(false);
        when(userRepository.saveAndFlush(any(UserEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var response = userService.createUser("u004", "ACTIVE");

        assertEquals("u004", response.userId());
        assertEquals("ACTIVE", response.status());
        verify(userRepository).saveAndFlush(any(UserEntity.class));
    }

    @Test
    void rejectsExistingUserBeforeSave() {
        when(userRepository.existsById("u001")).thenReturn(true);

        var exception = assertThrows(
                UserAlreadyExistsException.class,
                () -> userService.createUser("u001", "ACTIVE")
        );

        assertEquals("User already exists", exception.getMessage());
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void translatesConcurrentDuplicateAtDatabaseBoundary() {
        when(userRepository.existsById("u004")).thenReturn(false);
        when(userRepository.saveAndFlush(any(UserEntity.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThrows(
                UserAlreadyExistsException.class,
                () -> userService.createUser("u004", "ACTIVE")
        );
    }

    @Test
    void updatesExistingUserStatus() {
        var entity = new UserEntity("u004", "ACTIVE");
        when(userRepository.findById("u004")).thenReturn(Optional.of(entity));
        when(userRepository.saveAndFlush(entity)).thenReturn(entity);

        var response = userService.updateUser("u004", "INACTIVE");

        assertEquals("u004", response.userId());
        assertEquals("INACTIVE", response.status());
        verify(userRepository).saveAndFlush(entity);
    }

    @Test
    void rejectsUpdateForUnknownUser() {
        when(userRepository.findById("missing")).thenReturn(Optional.empty());

        var exception = assertThrows(
                UserNotFoundException.class,
                () -> userService.updateUser("missing", "ACTIVE")
        );

        assertEquals("User not found", exception.getMessage());
        verify(userRepository, never()).saveAndFlush(any());
    }
}
```

- [ ] **Step 2: Run the service tests and verify RED**

Run:

```powershell
mvn -pl user-service -Dtest=UserServiceTests test
```

Expected: compilation fails because `UserResponse`, exceptions, `createUser`, `updateUser`, and `updateStatus` do not exist.

- [ ] **Step 3: Implement the minimal domain and service code**

Add the response record:

```java
package com.devconnect.user.dto;

public record UserResponse(String userId, String status) {
}
```

Add both exceptions using the same structure, with the shown messages supplied by `UserService`:

```java
package com.devconnect.user.exception;

public class UserAlreadyExistsException extends RuntimeException {
    public UserAlreadyExistsException(String message) {
        super(message);
    }
}
```

```java
package com.devconnect.user.exception;

public class UserNotFoundException extends RuntimeException {
    public UserNotFoundException(String message) {
        super(message);
    }
}
```

Add this method to `UserEntity`:

```java
public void updateStatus(String status) {
    this.status = status;
}
```

Extend `UserService` with:

```java
public UserResponse createUser(String userId, String status) {
    if (userRepository.existsById(userId)) {
        throw new UserAlreadyExistsException("User already exists");
    }

    try {
        UserEntity saved = userRepository.saveAndFlush(new UserEntity(userId, status));
        return toResponse(saved);
    } catch (DataIntegrityViolationException exception) {
        throw new UserAlreadyExistsException("User already exists");
    }
}

public UserResponse updateUser(String userId, String status) {
    UserEntity user = userRepository.findById(userId)
            .orElseThrow(() -> new UserNotFoundException("User not found"));
    user.updateStatus(status);
    return toResponse(userRepository.saveAndFlush(user));
}

private UserResponse toResponse(UserEntity user) {
    return new UserResponse(user.getUserId(), user.getStatus());
}
```

Import `UserResponse`, both exceptions, `UserEntity`, and `DataIntegrityViolationException`.

- [ ] **Step 4: Run the service tests and verify GREEN**

Run:

```powershell
mvn -pl user-service -Dtest=UserServiceTests test
```

Expected: 5 tests pass with zero failures and errors.

- [ ] **Step 5: Commit the service behavior**

```powershell
git add -- user-service/src/main/java/com/devconnect/user/dto/UserResponse.java user-service/src/main/java/com/devconnect/user/exception/UserAlreadyExistsException.java user-service/src/main/java/com/devconnect/user/exception/UserNotFoundException.java user-service/src/main/java/com/devconnect/user/persistence/UserEntity.java user-service/src/main/java/com/devconnect/user/service/UserService.java user-service/src/test/java/com/devconnect/user/service/UserServiceTests.java
git commit -m "feat: add user create and update service behavior"
```

---

### Task 2: HTTP endpoints, validation, and error envelopes

**Files:**
- Modify: `user-service/pom.xml`
- Create: `user-service/src/main/java/com/devconnect/user/dto/CreateUserRequest.java`
- Create: `user-service/src/main/java/com/devconnect/user/dto/UpdateUserRequest.java`
- Create: `user-service/src/main/java/com/devconnect/user/exception/GlobalExceptionHandler.java`
- Modify: `user-service/src/main/java/com/devconnect/user/controller/UserInternalController.java`
- Test: `user-service/src/test/java/com/devconnect/user/controller/UserInternalControllerTests.java`

**Interfaces:**
- Consumes: Task 1's `UserService.createUser` and `updateUser`, plus the existing `getUserStatus`.
- Produces: `POST /api/users`, `PUT /api/users/{userId}`, and the unchanged `GET /internal/users/{userId}/status`.

- [ ] **Step 1: Add the validation dependency and write failing MVC tests**

Add this dependency beside the web MVC dependency in `user-service/pom.xml`:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>
```

Create `UserInternalControllerTests`:

```java
package com.devconnect.user.controller;

import com.devconnect.user.dto.UserResponse;
import com.devconnect.user.dto.UserStatusResponse;
import com.devconnect.user.exception.UserAlreadyExistsException;
import com.devconnect.user.exception.UserNotFoundException;
import com.devconnect.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserInternalController.class)
class UserInternalControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @Test
    void createsUser() throws Exception {
        when(userService.createUser("u004", "ACTIVE"))
                .thenReturn(new UserResponse("u004", "ACTIVE"));

        mockMvc.perform(post("/api/users")
                        .contentType("application/json")
                        .content("""
                                {"userId":"u004","status":"ACTIVE"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("User created successfully"))
                .andExpect(jsonPath("$.data.userId").value("u004"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    @Test
    void rejectsInvalidCreateStatus() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType("application/json")
                        .content("""
                                {"userId":"u004","status":"SUSPENDED"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("status must be ACTIVE or INACTIVE"))
                .andExpect(jsonPath("$.data").doesNotExist());

        verifyNoInteractions(userService);
    }

    @Test
    void rejectsDuplicateCreate() throws Exception {
        when(userService.createUser("u001", "ACTIVE"))
                .thenThrow(new UserAlreadyExistsException("User already exists"));

        mockMvc.perform(post("/api/users")
                        .contentType("application/json")
                        .content("""
                                {"userId":"u001","status":"ACTIVE"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("User already exists"));
    }

    @Test
    void updatesUser() throws Exception {
        when(userService.updateUser("u004", "INACTIVE"))
                .thenReturn(new UserResponse("u004", "INACTIVE"));

        mockMvc.perform(put("/api/users/u004")
                        .contentType("application/json")
                        .content("""
                                {"status":"INACTIVE"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("User updated successfully"))
                .andExpect(jsonPath("$.data.userId").value("u004"))
                .andExpect(jsonPath("$.data.status").value("INACTIVE"));
    }

    @Test
    void rejectsUpdateForUnknownUser() throws Exception {
        when(userService.updateUser("missing", "ACTIVE"))
                .thenThrow(new UserNotFoundException("User not found"));

        mockMvc.perform(put("/api/users/missing")
                        .contentType("application/json")
                        .content("""
                                {"status":"ACTIVE"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("User not found"));
    }

    @Test
    void keepsInternalStatusEndpointCompatible() throws Exception {
        when(userService.getUserStatus("u001"))
                .thenReturn(Optional.of(new UserStatusResponse("u001", "ACTIVE")));

        mockMvc.perform(get("/internal/users/u001/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("User status found"))
                .andExpect(jsonPath("$.data.userId").value("u001"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    @Test
    void keepsInternalMissingUserResponseCompatible() throws Exception {
        when(userService.getUserStatus(anyString())).thenReturn(Optional.empty());

        mockMvc.perform(get("/internal/users/missing/status"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("User not found"));
    }
}
```

- [ ] **Step 2: Run the MVC tests and verify RED**

Run:

```powershell
mvn -pl user-service -Dtest=UserInternalControllerTests test
```

Expected: tests fail because the request records, new routes, validation, and exception handler do not exist.

- [ ] **Step 3: Add validated request DTOs**

Create `CreateUserRequest`:

```java
package com.devconnect.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
        @NotBlank(message = "userId is required")
        @Size(max = 64, message = "userId must not exceed 64 characters")
        String userId,
        @NotBlank(message = "status is required")
        @Pattern(regexp = "ACTIVE|INACTIVE", message = "status must be ACTIVE or INACTIVE")
        String status
) {
}
```

Create `UpdateUserRequest`:

```java
package com.devconnect.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record UpdateUserRequest(
        @NotBlank(message = "status is required")
        @Pattern(regexp = "ACTIVE|INACTIVE", message = "status must be ACTIVE or INACTIVE")
        String status
) {
}
```

- [ ] **Step 4: Implement centralized error mapping**

Create `GlobalExceptionHandler`:

```java
package com.devconnect.user.exception;

import com.devconnect.user.dto.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getDefaultMessage())
                .orElse("Invalid request");
        return ResponseEntity.badRequest().body(ApiResponse.error(message));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableBody() {
        return ResponseEntity.badRequest().body(ApiResponse.error("Malformed request body"));
    }

    @ExceptionHandler(UserAlreadyExistsException.class)
    public ResponseEntity<ApiResponse<Void>> handleAlreadyExists(UserAlreadyExistsException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(exception.getMessage()));
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(UserNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(exception.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected() {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("Internal server error"));
    }
}
```

- [ ] **Step 5: Add both write methods to the existing controller**

Remove `@RequestMapping("/internal/users")` from the class. Keep the current GET method body, changing its annotation to:

```java
@GetMapping("/internal/users/{userId}/status")
```

Add:

```java
@PostMapping("/api/users")
public ResponseEntity<ApiResponse<UserResponse>> createUser(
        @Valid @RequestBody CreateUserRequest request
) {
    UserResponse response = userService.createUser(request.userId(), request.status());
    return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success("User created successfully", response));
}

@PutMapping("/api/users/{userId}")
public ResponseEntity<ApiResponse<UserResponse>> updateUser(
        @PathVariable String userId,
        @Valid @RequestBody UpdateUserRequest request
) {
    UserResponse response = userService.updateUser(userId, request.status());
    return ResponseEntity.ok(ApiResponse.success("User updated successfully", response));
}
```

Import `CreateUserRequest`, `UpdateUserRequest`, `UserResponse`, and `jakarta.validation.Valid`.

- [ ] **Step 6: Run MVC and service tests and verify GREEN**

Run:

```powershell
mvn -pl user-service -Dtest=UserInternalControllerTests,UserServiceTests test
```

Expected: 12 tests pass with zero failures and errors.

- [ ] **Step 7: Commit the HTTP contract**

```powershell
git add -- user-service/pom.xml user-service/src/main/java/com/devconnect/user/dto/CreateUserRequest.java user-service/src/main/java/com/devconnect/user/dto/UpdateUserRequest.java user-service/src/main/java/com/devconnect/user/exception/GlobalExceptionHandler.java user-service/src/main/java/com/devconnect/user/controller/UserInternalController.java user-service/src/test/java/com/devconnect/user/controller/UserInternalControllerTests.java
git commit -m "feat: expose user create and update endpoints"
```

---

### Task 3: PostgreSQL-compatible persistence integration

**Files:**
- Modify: `user-service/src/test/java/com/devconnect/user/UserServiceApplicationTests.java`

**Interfaces:**
- Consumes: Task 1's service methods and the existing H2 PostgreSQL-mode/Flyway test configuration.
- Produces: proof that the unchanged Flyway schema persists creates and updates correctly.

- [ ] **Step 1: Add a failing integration test**

Add `@Transactional` to `UserServiceApplicationTests`, then add:

```java
@Test
void createsAndUpdatesUserAgainstFlywaySchema() {
    String userId = "integration-user";

    var created = userService.createUser(userId, "ACTIVE");
    var updated = userService.updateUser(userId, "INACTIVE");
    var persisted = userService.getUserStatus(userId).orElseThrow();

    assertEquals("ACTIVE", created.status());
    assertEquals("INACTIVE", updated.status());
    assertEquals("INACTIVE", persisted.status());
}
```

Import `org.springframework.transaction.annotation.Transactional`.

- [ ] **Step 2: Run the integration test**

Run:

```powershell
mvn -pl user-service -Dtest=UserServiceApplicationTests test
```

Expected before Tasks 1-2: compilation or behavior failure. Expected after Tasks 1-2: all tests in the class pass, proving no migration is required.

- [ ] **Step 3: Run every user-service test**

Run:

```powershell
mvn -pl user-service test
```

Expected: all user-service tests pass with zero failures and errors.

- [ ] **Step 4: Commit persistence coverage**

```powershell
git add -- user-service/src/test/java/com/devconnect/user/UserServiceApplicationTests.java
git commit -m "test: verify user writes against migrated schema"
```

---

### Task 4: Feed Service consumer regression

**Files:**
- Create: `feed-service/src/test/java/com/devconnect/feed/service/FeedServiceUserContractTests.java`

**Interfaces:**
- Consumes: unchanged `GET /internal/users/{userId}/status` envelope and `UserStatusResponse(userId, status)`.
- Produces: regression proof that `feed-service` still permits active authors and rejects inactive authors.

- [ ] **Step 1: Write contract regression tests**

Create:

```java
package com.devconnect.feed.service;

import com.devconnect.feed.dto.CreatePostRequest;
import com.devconnect.feed.event.PostEventPublisher;
import com.devconnect.feed.exception.BusinessException;
import com.devconnect.feed.persistence.PostStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class FeedServiceUserContractTests {

    private MockRestServiceServer server;
    private PostEventPublisher publisher;
    private PostStore postStore;
    private FeedService feedService;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        publisher = mock(PostEventPublisher.class);
        postStore = mock(PostStore.class);
        feedService = new FeedService(
                builder.baseUrl("http://user-service").build(),
                publisher,
                postStore
        );
    }

    @Test
    void activeUserContractStillAllowsPostCreation() {
        server.expect(requestTo("http://user-service/internal/users/u004/status"))
                .andRespond(withSuccess("""
                        {"success":true,"message":"User status found","data":{"userId":"u004","status":"ACTIVE"}}
                        """, MediaType.APPLICATION_JSON));

        var post = feedService.createPost(new CreatePostRequest("u004", "Still compatible"));

        assertEquals("u004", post.authorId());
        verify(postStore).save(post);
        verify(publisher).publishPostCreated(any());
        server.verify();
    }

    @Test
    void inactiveUserContractStillRejectsPostCreation() {
        server.expect(requestTo("http://user-service/internal/users/u004/status"))
                .andRespond(withSuccess("""
                        {"success":true,"message":"User status found","data":{"userId":"u004","status":"INACTIVE"}}
                        """, MediaType.APPLICATION_JSON));

        var exception = assertThrows(
                BusinessException.class,
                () -> feedService.createPost(new CreatePostRequest("u004", "Blocked"))
        );

        assertEquals("Author is not active", exception.getMessage());
        verify(postStore, never()).save(any());
        verify(publisher, never()).publishPostCreated(any());
        server.verify();
    }
}
```

- [ ] **Step 2: Run the regression tests**

Run:

```powershell
mvn -pl feed-service -Dtest=FeedServiceUserContractTests test
```

Expected: 2 tests pass with the production `FeedService` unchanged.

- [ ] **Step 3: Run every feed-service test**

Run:

```powershell
mvn -pl feed-service test
```

Expected: all feed-service tests pass with zero failures and errors.

- [ ] **Step 4: Commit consumer regression coverage**

```powershell
git add -- feed-service/src/test/java/com/devconnect/feed/service/FeedServiceUserContractTests.java
git commit -m "test: protect feed user status contract"
```

---

### Task 5: User API documentation

**Files:**
- Modify: `README.md`
- Modify: `docs/API.md`
- Modify: `docs/ARCHITECTURE.md`
- Modify: `docs/DATABASE.md`

**Interfaces:**
- Consumes: completed HTTP behavior from Tasks 1-3.
- Produces: copy-pasteable API examples and accurate service/database descriptions.

- [ ] **Step 1: Update the root quick start and endpoint summary**

Add both endpoint paths to the README architecture/endpoint material and add PowerShell examples equivalent to:

```powershell
$user = @{ userId = "u004"; status = "ACTIVE" } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "http://localhost:3000/api/users" -ContentType "application/json" -Body $user

$status = @{ status = "INACTIVE" } | ConvertTo-Json
Invoke-RestMethod -Method Put -Uri "http://localhost:3000/api/users/u004" -ContentType "application/json" -Body $status
```

Replace statements saying User Service only exposes status lookup or cannot mutate data via API.

- [ ] **Step 2: Document exact REST contracts and errors**

In `docs/API.md`, add endpoint table rows and sections for the exact bodies, `201/200` success envelopes, and `400/404/409` error cases. Keep `GET /internal/users/{userId}/status` marked internal and unchanged.

- [ ] **Step 3: Update architecture and database ownership text**

In `docs/ARCHITECTURE.md`, state that `UserInternalController` contains the internal lookup and both public writes under the selected compact approach. In `docs/DATABASE.md`, keep Flyway as schema owner and explain that API writes use the existing `users(user_id,status)` table without a new migration.

- [ ] **Step 4: Search for stale claims**

Run:

```powershell
rg -n -i "only.*status|chỉ cung cấp|chưa có API tạo|cần migration mới|direct.*database" README.md docs
```

Expected: no stale statement claims that user creation/update requires a migration or direct database access. Legitimate statements about the internal GET and Flyway remain.

- [ ] **Step 5: Commit documentation**

```powershell
git add -- README.md docs/API.md docs/ARCHITECTURE.md docs/DATABASE.md
git commit -m "docs: document user write APIs"
```

---

### Task 6: Full repository and runtime verification

**Files:**
- Verify only; modify production or test files only if a failure exposes a defect in the approved scope.

**Interfaces:**
- Consumes: all preceding tasks.
- Produces: evidence that every module builds, tests pass, Compose remains valid, and the User/Feed runtime flow works.

- [ ] **Step 1: Run the full clean Maven verification**

Run:

```powershell
mvn clean verify
```

Expected: reactor reports `BUILD SUCCESS` for `user-service`, `feed-service`, `search-service`, and `notification-service`, with zero test failures/errors.

- [ ] **Step 2: Validate Compose topology**

Run:

```powershell
docker compose config --quiet
docker compose config --services
```

Expected: config validation exits 0 and lists `postgres`, `cassandra`, `cassandra-init`, `kafka`, `kafka-ui`, `user-service`, `feed-service`, `search-service`, and `notification-service`.

- [ ] **Step 3: Build and start the stack when Docker is available**

First run `docker info`. If the daemon is available, run:

```powershell
docker compose up -d --build
docker compose ps -a
```

Expected: infrastructure and four application services become healthy; `cassandra-init` exits successfully. Do not use `docker compose down -v` and do not delete existing volumes.

- [ ] **Step 4: Smoke-test create, update, status lookup, and Feed impact**

Use a unique ID and execute:

```powershell
$userId = "smoke-" + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$createBody = @{ userId = $userId; status = "ACTIVE" } | ConvertTo-Json
$created = Invoke-RestMethod -Method Post -Uri "http://localhost:3000/api/users" -ContentType "application/json" -Body $createBody
$activeStatus = Invoke-RestMethod "http://localhost:3000/internal/users/$userId/status"
$postBody = @{ authorId = $userId; content = "User API smoke test" } | ConvertTo-Json
$post = Invoke-RestMethod -Method Post -Uri "http://localhost:8082/api/feed/posts" -ContentType "application/json" -Body $postBody
$updateBody = @{ status = "INACTIVE" } | ConvertTo-Json
$updated = Invoke-RestMethod -Method Put -Uri "http://localhost:3000/api/users/$userId" -ContentType "application/json" -Body $updateBody
$inactiveStatus = Invoke-RestMethod "http://localhost:3000/internal/users/$userId/status"
```

Assert in PowerShell that `$created.data.status` and `$activeStatus.data.status` equal `ACTIVE`, `$post.data.authorId` equals `$userId`, and `$updated.data.status` and `$inactiveStatus.data.status` equal `INACTIVE`. A second Feed create for the inactive user must return HTTP `400` with message `Author is not active`.

- [ ] **Step 5: Inspect all service logs for regression signals**

Run:

```powershell
docker compose logs --no-color --tail 200 user-service feed-service search-service notification-service
```

Expected: no startup exception, schema validation error, deserialization failure, or repeated crash/restart related to the change.

- [ ] **Step 6: Review the final diff and repository status**

Run:

```powershell
git diff --check
git status --short
git diff --stat
```

Expected: no whitespace errors; only scoped implementation/documentation files plus the user's pre-existing untracked files appear.
