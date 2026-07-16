# Feed Create Post Controller Response Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Return a concrete `ApiResponse<PostResponse>` from the create-post controller while running the create workflow through `postTaskExecutor` inside `FeedService`.

**Architecture:** `FeedController` delegates directly to `FeedService` and returns a synchronous MVC value. `FeedService.createPost` submits a private synchronous workflow with `CompletableFuture.supplyAsync`, joins it, and unwraps runtime failures so existing exception handlers keep working.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring MVC, `CompletableFuture`, JUnit 5, Mockito, MockMvc, Maven

## Global Constraints

- Keep `POST /api/feed/posts`, `CreatePostRequest`, `PostResponse`, and the success message `Post created successfully` unchanged.
- Keep author validation, Cassandra persistence, and `POST_CREATED` publication semantics unchanged.
- Keep the existing `postTaskExecutor` configuration.
- The request waits for author validation and Cassandra persistence; moving the workflow to another thread does not reduce response latency.
- Preserve existing user edits outside the files listed in this plan.

---

## File Structure

- Modify `feed-service/src/main/java/com/devconnect/feed/controller/FeedController.java`: synchronous HTTP adapter depending only on `FeedService`.
- Modify `feed-service/src/main/java/com/devconnect/feed/service/FeedService.java`: executor submission, join, exception unwrapping, and existing create workflow.
- Delete `feed-service/src/main/java/com/devconnect/feed/service/AsyncPostService.java`: redundant service boundary.
- Replace `feed-service/src/test/java/com/devconnect/feed/controller/FeedControllerAsyncTests.java` with `FeedControllerTests.java`: synchronous MVC contract coverage.
- Replace `feed-service/src/test/java/com/devconnect/feed/service/AsyncPostServiceTests.java` with `FeedServiceAsyncTests.java`: executor and exception-unwrapping coverage.
- Modify `feed-service/src/test/java/com/devconnect/feed/service/FeedServiceUserContractTests.java`: supply a direct executor to the expanded constructor.

### Task 1: Make the controller response synchronous

**Files:**
- Create: `feed-service/src/test/java/com/devconnect/feed/controller/FeedControllerTests.java`
- Delete: `feed-service/src/test/java/com/devconnect/feed/controller/FeedControllerAsyncTests.java`
- Modify: `feed-service/src/main/java/com/devconnect/feed/controller/FeedController.java`

**Interfaces:**
- Consumes: `PostResponse FeedService.createPost(CreatePostRequest request)`
- Produces: `ApiResponse<PostResponse> FeedController.createPost(CreatePostRequest request)`

- [ ] **Step 1: Replace async controller tests with failing synchronous tests**

Delete `FeedControllerAsyncTests.java` and create `FeedControllerTests.java`:

```java
package com.devconnect.feed.controller;

import com.devconnect.feed.dto.CreatePostRequest;
import com.devconnect.feed.dto.PostResponse;
import com.devconnect.feed.exception.BusinessException;
import com.devconnect.feed.service.FeedService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FeedController.class)
class FeedControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FeedService feedService;

    @Test
    void createPostReturnsSynchronousSuccessResponse() throws Exception {
        PostResponse response = new PostResponse(
                "post-1", "u001", "Async Java", LocalDateTime.now()
        );
        when(feedService.createPost(any(CreatePostRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/feed/posts")
                        .contentType("application/json")
                        .content("""
                                {
                                  "authorId": "u001",
                                  "content": "Async Java"
                                }
                                """))
                .andExpect(request().asyncNotStarted())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Post created successfully"))
                .andExpect(jsonPath("$.data.postId").value("post-1"));

        verify(feedService).createPost(any(CreatePostRequest.class));
    }

    @Test
    void createPostPassesBusinessExceptionToExistingHandler() throws Exception {
        when(feedService.createPost(any(CreatePostRequest.class)))
                .thenThrow(new BusinessException("Author is not active"));

        mockMvc.perform(post("/api/feed/posts")
                        .contentType("application/json")
                        .content("""
                                {
                                  "authorId": "u003",
                                  "content": "Not allowed"
                                }
                                """))
                .andExpect(request().asyncNotStarted())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Author is not active"));
    }
}
```

- [ ] **Step 2: Run the controller test and verify RED**

Run:

```powershell
mvn -pl feed-service '-Dtest=FeedControllerTests' test
```

Expected: FAIL because the current controller still requires `AsyncPostService` and starts MVC async processing.

- [ ] **Step 3: Implement the minimal synchronous controller**

Remove the `AsyncPostService` import, field, constructor parameter, and `CompletableFuture` import. Use:

```java
public FeedController(FeedService feedService) {
    this.feedService = feedService;
}

@PostMapping
public ApiResponse<PostResponse> createPost(
        @Valid @RequestBody CreatePostRequest request
) {
    PostResponse response = feedService.createPost(request);
    return ApiResponse.success("Post created successfully", response);
}
```

- [ ] **Step 4: Run the controller test and verify GREEN**

Run:

```powershell
mvn -pl feed-service '-Dtest=FeedControllerTests' test
```

Expected: PASS; both tests complete without async dispatch.

- [ ] **Step 5: Commit the controller contract change**

```powershell
git add -- feed-service/src/main/java/com/devconnect/feed/controller/FeedController.java feed-service/src/test/java/com/devconnect/feed/controller/FeedControllerAsyncTests.java feed-service/src/test/java/com/devconnect/feed/controller/FeedControllerTests.java
git commit -m "refactor: return concrete create post response"
```

### Task 2: Move executor orchestration into FeedService

**Files:**
- Create: `feed-service/src/test/java/com/devconnect/feed/service/FeedServiceAsyncTests.java`
- Delete: `feed-service/src/test/java/com/devconnect/feed/service/AsyncPostServiceTests.java`
- Modify: `feed-service/src/test/java/com/devconnect/feed/service/FeedServiceUserContractTests.java`
- Modify: `feed-service/src/main/java/com/devconnect/feed/service/FeedService.java`
- Delete: `feed-service/src/main/java/com/devconnect/feed/service/AsyncPostService.java`

**Interfaces:**
- Consumes: Spring bean `@Qualifier("postTaskExecutor") Executor postTaskExecutor`
- Produces: `PostResponse FeedService.createPost(CreatePostRequest request)` with unwrapped runtime failures

- [ ] **Step 1: Write failing FeedService executor tests**

Delete `AsyncPostServiceTests.java` and create `FeedServiceAsyncTests.java`:

```java
package com.devconnect.feed.service;

import com.devconnect.feed.dto.CreatePostRequest;
import com.devconnect.feed.dto.PostResponse;
import com.devconnect.feed.event.PostEventPublisher;
import com.devconnect.feed.exception.BusinessException;
import com.devconnect.feed.persistence.PostStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class FeedServiceAsyncTests {

    private MockRestServiceServer server;
    private PostEventPublisher publisher;
    private PostStore postStore;
    private FeedService feedService;
    private ThreadPoolTaskExecutor executor;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        publisher = mock(PostEventPublisher.class);
        postStore = mock(PostStore.class);
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("post-async-test-");
        executor.initialize();
        feedService = new FeedService(
                builder.baseUrl("http://user-service").build(),
                publisher,
                postStore,
                executor
        );
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Test
    void createPostRunsWorkflowOnPostExecutor() {
        server.expect(requestTo("http://user-service/internal/users/u001/status"))
                .andRespond(withSuccess("""
                        {"success":true,"message":"User status found","data":{"userId":"u001","status":"ACTIVE"}}
                        """, MediaType.APPLICATION_JSON));
        String callerThread = Thread.currentThread().getName();
        AtomicReference<String> workerThread = new AtomicReference<>();
        doAnswer(invocation -> {
            workerThread.set(Thread.currentThread().getName());
            return null;
        }).when(postStore).save(any());

        PostResponse actual = feedService.createPost(
                new CreatePostRequest("u001", "Async Java")
        );

        assertEquals("u001", actual.authorId());
        assertNotEquals(callerThread, workerThread.get());
        assertTrue(workerThread.get().startsWith("post-async-test-"));
        verify(publisher).publishPostCreated(any());
        server.verify();
    }

    @Test
    void createPostRethrowsBusinessExceptionWithoutCompletionWrapper() {
        server.expect(requestTo("http://user-service/internal/users/u003/status"))
                .andRespond(withSuccess("""
                        {"success":true,"message":"User status found","data":{"userId":"u003","status":"INACTIVE"}}
                        """, MediaType.APPLICATION_JSON));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> feedService.createPost(new CreatePostRequest("u003", "Not allowed"))
        );

        assertEquals("Author is not active", exception.getMessage());
        verify(postStore, never()).save(any());
        verify(publisher, never()).publishPostCreated(any());
        server.verify();
    }
}
```

Shut down the executor in `@AfterEach`.

- [ ] **Step 2: Update constructor calls and verify RED**

Add `Runnable::run` as the fourth constructor argument in `FeedServiceUserContractTests`:

```java
feedService = new FeedService(
        builder.baseUrl("http://user-service").build(),
        publisher,
        postStore,
        Runnable::run
);
```

Run:

```powershell
mvn -pl feed-service '-Dtest=FeedServiceAsyncTests,FeedServiceUserContractTests' test
```

Expected: FAIL to compile because `FeedService` does not yet accept or use an executor.

- [ ] **Step 3: Implement executor orchestration and exception unwrapping**

Add the executor dependency:

```java
private final Executor postTaskExecutor;

public FeedService(
        RestClient userServiceRestClient,
        PostEventPublisher postEventPublisher,
        PostStore postStore,
        @Qualifier("postTaskExecutor") Executor postTaskExecutor
) {
    this.userServiceRestClient = userServiceRestClient;
    this.postEventPublisher = postEventPublisher;
    this.postStore = postStore;
    this.postTaskExecutor = postTaskExecutor;
}
```

Make the public method submit and join, then move the current body unchanged into `createPostSynchronously`:

```java
public PostResponse createPost(CreatePostRequest request) {
    try {
        return CompletableFuture
                .supplyAsync(() -> createPostSynchronously(request), postTaskExecutor)
                .join();
    } catch (CompletionException exception) {
        if (exception.getCause() instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        throw new IllegalStateException("Post creation failed", exception.getCause());
    }
}

private PostResponse createPostSynchronously(CreatePostRequest request) {
    UserStatusResponse authorStatus = getAuthorStatus(request.authorId());
    if (!"ACTIVE".equals(authorStatus.status())) {
        throw new BusinessException("Author is not active");
    }

    LocalDateTime createdAt = LocalDateTime.ofInstant(
            Instant.now().truncatedTo(MILLIS),
            UTC
    );
    PostResponse post = new PostResponse(
            UUID.randomUUID().toString(),
            request.authorId(),
            request.content(),
            createdAt
    );

    postStore.save(post);

    PostCreatedEvent event = new PostCreatedEvent(
            UUID.randomUUID().toString(),
            "POST_CREATED",
            post.postId(),
            post.authorId(),
            post.content(),
            createdAt
    );
    postEventPublisher.publishPostCreated(event);

    return post;
}
```

- [ ] **Step 4: Remove the redundant async service and verify GREEN**

Delete `AsyncPostService.java` and `AsyncPostServiceTests.java`. Run:

```powershell
mvn -pl feed-service '-Dtest=FeedServiceAsyncTests,FeedServiceUserContractTests' test
```

Expected: PASS; the workflow runs on `post-async-test-*`, and `BusinessException` is not wrapped.

- [ ] **Step 5: Run the full feed-service regression suite**

Run:

```powershell
mvn -pl feed-service test
```

Expected: BUILD SUCCESS with no test failures or compilation errors.

- [ ] **Step 6: Commit service orchestration**

```powershell
git add -- feed-service/src/main/java/com/devconnect/feed/service/FeedService.java feed-service/src/main/java/com/devconnect/feed/service/AsyncPostService.java feed-service/src/test/java/com/devconnect/feed/service/AsyncPostServiceTests.java feed-service/src/test/java/com/devconnect/feed/service/FeedServiceAsyncTests.java feed-service/src/test/java/com/devconnect/feed/service/FeedServiceUserContractTests.java
git commit -m "refactor: run post creation through feed executor"
```

### Task 3: Final verification

**Files:**
- Verify only; no planned production changes.

**Interfaces:**
- Consumes: completed Tasks 1 and 2
- Produces: verified feed-service build and clean diff for the scoped files

- [ ] **Step 1: Confirm AsyncPostService has no remaining references**

```powershell
rg -n "AsyncPostService|CompletableFuture<ApiResponse<PostResponse>>|asyncDispatch" feed-service
```

Expected: no matches.

- [ ] **Step 2: Check diff quality and rerun tests**

```powershell
git diff --check
mvn -pl feed-service test
```

Expected: no whitespace errors and BUILD SUCCESS.

- [ ] **Step 3: Review scoped status**

```powershell
git status --short
```

Expected: unrelated pre-existing user changes remain untouched; all requested source and test changes are visible or committed.
