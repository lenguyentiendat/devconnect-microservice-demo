# Feed Parallel Post Validation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Return a raw `PostResponse` with HTTP 201 while author and content validations run concurrently through `thenCombine`.

**Architecture:** `FeedController` remains synchronous and wraps the concrete service result in `ResponseEntity`. `FeedService` submits author lookup and local content validation before joining their combined result, validates both outcomes, then creates, saves, and publishes the post.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring MVC, `CompletableFuture.thenCombine`, JUnit 5, Mockito, MockMvc, Maven

## Global Constraints

- Keep endpoint `/api/feed/posts` and `CreatePostRequest` unchanged.
- Return `ResponseEntity<PostResponse>` with HTTP `201 Created`; do not expose a future from the controller.
- Submit both independent validations before calling `thenCombine(...).join()`.
- Use the existing `postTaskExecutor`, default core pool size four, and `post-async-` thread prefix.
- Reject blank content, content longer than 5,000 characters, and case-insensitive `spam` or `scam` occurrences.
- Save and publish only after author and content validation both pass.
- Keep `AsyncPostService` removed and preserve unrelated workspace changes.

---

## File Structure

- Modify `feed-service/src/main/java/com/devconnect/feed/controller/FeedController.java`: HTTP 201 raw response contract.
- Modify `feed-service/src/test/java/com/devconnect/feed/controller/FeedControllerTests.java`: raw response and error coverage.
- Create `feed-service/src/main/java/com/devconnect/feed/dto/ContentValidationResponse.java`: content validation result.
- Modify `feed-service/src/main/java/com/devconnect/feed/service/FeedService.java`: parallel validation orchestration and separated save workflow.
- Replace `feed-service/src/test/java/com/devconnect/feed/service/FeedServiceAsyncTests.java` with `FeedServiceParallelValidationTests.java`: concurrency and prohibited-content coverage.

### Task 1: Change controller to HTTP 201 raw PostResponse

**Files:**
- Modify: `feed-service/src/test/java/com/devconnect/feed/controller/FeedControllerTests.java`
- Modify: `feed-service/src/main/java/com/devconnect/feed/controller/FeedController.java`

**Interfaces:**
- Consumes: `PostResponse FeedService.createPost(CreatePostRequest request)`
- Produces: `ResponseEntity<PostResponse> FeedController.createPost(CreatePostRequest request)`

- [ ] **Step 1: Write the failing raw-response test**

Change the success assertions in `FeedControllerTests` to:

```java
mockMvc.perform(post("/api/feed/posts")
                .contentType("application/json")
                .content("""
                        {
                          "authorId": "u001",
                          "content": "Async Java"
                        }
                        """))
        .andExpect(request().asyncNotStarted())
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.postId").value("post-1"))
        .andExpect(jsonPath("$.authorId").value("u001"))
        .andExpect(jsonPath("$.content").value("Async Java"))
        .andExpect(jsonPath("$.success").doesNotExist());
```

Keep the existing business-exception test; it verifies the global error envelope
remains HTTP 400.

- [ ] **Step 2: Run controller tests and verify RED**

```powershell
mvn -pl feed-service '-Dtest=FeedControllerTests' test
```

Expected: FAIL because the current endpoint returns HTTP 200 with an `ApiResponse` envelope.

- [ ] **Step 3: Implement the minimal controller contract**

Add `HttpStatus` and `ResponseEntity`, remove the unused `ApiResponse` import only
if no other controller method needs it, and replace create-post with:

```java
@PostMapping
public ResponseEntity<PostResponse> createPost(
        @Valid @RequestBody CreatePostRequest request
) {
    PostResponse response = feedService.createPost(request);
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
}
```

`ApiResponse` remains imported because both GET endpoints still use it.

- [ ] **Step 4: Run controller tests and verify GREEN**

```powershell
mvn -pl feed-service '-Dtest=FeedControllerTests' test
```

Expected: PASS; success is HTTP 201 raw `PostResponse`, business failure remains HTTP 400.

- [ ] **Step 5: Commit Task 1 only**

```powershell
git add -- feed-service/src/main/java/com/devconnect/feed/controller/FeedController.java feed-service/src/test/java/com/devconnect/feed/controller/FeedControllerTests.java
git commit --only -m "refactor: return created post response" -- feed-service/src/main/java/com/devconnect/feed/controller/FeedController.java feed-service/src/test/java/com/devconnect/feed/controller/FeedControllerTests.java
```

### Task 2: Run author and content validation concurrently

**Files:**
- Create: `feed-service/src/main/java/com/devconnect/feed/dto/ContentValidationResponse.java`
- Delete: `feed-service/src/test/java/com/devconnect/feed/service/FeedServiceAsyncTests.java`
- Create: `feed-service/src/test/java/com/devconnect/feed/service/FeedServiceParallelValidationTests.java`
- Modify: `feed-service/src/main/java/com/devconnect/feed/service/FeedService.java`

**Interfaces:**
- Produces: `ContentValidationResponse(boolean allowed, String reason)`
- Produces: synchronous `PostResponse FeedService.createPost(CreatePostRequest request)` backed by two combined futures
- Consumes: `UserStatusResponse getAuthorStatus(String authorId)` and local `ContentValidationResponse validatePostContent(String content)`

- [ ] **Step 1: Replace the old async test with failing parallel-validation tests**

Delete `FeedServiceAsyncTests.java`. Create `FeedServiceParallelValidationTests.java`
using the same `MockRestServiceServer`, mocked `PostEventPublisher`, and mocked
`PostStore` setup. Add this controlled executor inside the test class:

```java
private static final class CoordinatingExecutor implements Executor {
    private final AtomicInteger submittedTasks = new AtomicInteger();
    private final CountDownLatch validationTasksStarted = new CountDownLatch(2);
    private final AtomicBoolean startedConcurrently = new AtomicBoolean();

    @Override
    public void execute(Runnable command) {
        int taskNumber = submittedTasks.incrementAndGet();
        Thread thread = new Thread(() -> {
            validationTasksStarted.countDown();
            try {
                if (validationTasksStarted.await(2, TimeUnit.SECONDS)) {
                    startedConcurrently.set(true);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
            command.run();
        }, "parallel-validation-" + taskNumber);
        thread.start();
    }
}
```

The concurrency test constructs `FeedService` with this executor, returns an active
author response, invokes `createPost`, and asserts:

```java
PostResponse post = feedService.createPost(
        new CreatePostRequest("u001", "Hello DevConnect")
);

assertEquals(2, executor.submittedTasks.get());
assertTrue(executor.startedConcurrently.get());
assertEquals("u001", post.authorId());
verify(postStore).save(post);
verify(publisher).publishPostCreated(any());
server.verify();
```

The prohibited-content test uses the existing `ThreadPoolTaskExecutor`, returns an
active author, and asserts:

```java
BusinessException exception = assertThrows(
        BusinessException.class,
        () -> feedService.createPost(
                new CreatePostRequest("u001", "This is a SCAM")
        )
);

assertEquals("Post content contains prohibited words", exception.getMessage());
verify(postStore, never()).save(any());
verify(publisher, never()).publishPostCreated(any());
server.verify();
```

- [ ] **Step 2: Run the new service tests and verify RED**

```powershell
mvn -pl feed-service '-Dtest=FeedServiceParallelValidationTests' test
```

Expected: FAIL because the current service submits one whole-workflow task and does not reject prohibited content.

- [ ] **Step 3: Add the content-validation result record**

Create `ContentValidationResponse.java`:

```java
package com.devconnect.feed.dto;

public record ContentValidationResponse(
        boolean allowed,
        String reason
) {
}
```

- [ ] **Step 4: Implement thenCombine orchestration**

In `FeedService`, add a logger and replace `createPost` with:

```java
public PostResponse createPost(CreatePostRequest request) {
    CompletableFuture<UserStatusResponse> authorStatusFuture =
            CompletableFuture.supplyAsync(
                    () -> getAuthorStatus(request.authorId()),
                    postTaskExecutor
            );
    CompletableFuture<ContentValidationResponse> contentValidationFuture =
            CompletableFuture.supplyAsync(
                    () -> validatePostContent(request.content()),
                    postTaskExecutor
            );

    try {
        PostCreationValidation validation = authorStatusFuture
                .thenCombine(
                        contentValidationFuture,
                        PostCreationValidation::new
                )
                .join();
        validatePostCreation(validation);
        return createAndSavePost(request);
    } catch (CompletionException exception) {
        throw unwrapCompletionException(exception);
    }
}
```

Add validation methods:

```java
private ContentValidationResponse validatePostContent(String content) {
    log.info("Validating content on thread: {}", Thread.currentThread().getName());
    if (content == null || content.isBlank()) {
        return new ContentValidationResponse(false, "Post content must not be blank");
    }
    if (content.length() > 5_000) {
        return new ContentValidationResponse(
                false,
                "Post content must not exceed 5000 characters"
        );
    }
    String normalizedContent = content.toLowerCase(Locale.ROOT);
    boolean containsProhibitedWord = List.of("spam", "scam").stream()
            .anyMatch(normalizedContent::contains);
    if (containsProhibitedWord) {
        return new ContentValidationResponse(
                false,
                "Post content contains prohibited words"
        );
    }
    return new ContentValidationResponse(true, null);
}

private void validatePostCreation(PostCreationValidation validation) {
    if (!"ACTIVE".equals(validation.authorStatus().status())) {
        throw new BusinessException("Author is not active");
    }
    if (!validation.contentValidation().allowed()) {
        throw new BusinessException(validation.contentValidation().reason());
    }
}
```

Move the existing timestamp, post creation, `postStore.save`, event creation,
publication, and return statements into:

```java
private PostResponse createAndSavePost(CreatePostRequest request) {
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

Add exception unwrapping and combined result:

```java
private RuntimeException unwrapCompletionException(CompletionException exception) {
    Throwable cause = exception.getCause();
    if (cause instanceof RuntimeException runtimeException) {
        return runtimeException;
    }
    return new IllegalStateException("Post creation failed", cause);
}

private record PostCreationValidation(
        UserStatusResponse authorStatus,
        ContentValidationResponse contentValidation
) {
}
```

Log author lookup at the start of `getAuthorStatus`:

```java
log.info("Getting author status on thread: {}", Thread.currentThread().getName());
```

- [ ] **Step 5: Run service tests and verify GREEN**

```powershell
mvn -pl feed-service '-Dtest=FeedServiceParallelValidationTests,FeedServiceUserContractTests' test
```

Expected: PASS; both validations are submitted concurrently, prohibited content is rejected, and existing author rules remain compatible.

- [ ] **Step 6: Run full regression and commit Task 2 only**

```powershell
mvn -pl feed-service test
git add -- feed-service/src/main/java/com/devconnect/feed/dto/ContentValidationResponse.java feed-service/src/main/java/com/devconnect/feed/service/FeedService.java feed-service/src/test/java/com/devconnect/feed/service/FeedServiceAsyncTests.java feed-service/src/test/java/com/devconnect/feed/service/FeedServiceParallelValidationTests.java
git commit --only -m "feat: validate post creation in parallel" -- feed-service/src/main/java/com/devconnect/feed/dto/ContentValidationResponse.java feed-service/src/main/java/com/devconnect/feed/service/FeedService.java feed-service/src/test/java/com/devconnect/feed/service/FeedServiceAsyncTests.java feed-service/src/test/java/com/devconnect/feed/service/FeedServiceParallelValidationTests.java
```

Expected: BUILD SUCCESS with all feed-service tests passing before the commit.

### Task 3: Final verification

**Files:**
- Verify only; no planned source changes.

**Interfaces:**
- Consumes: completed controller and parallel-validation tasks
- Produces: verified response contract, concurrency structure, and regression-safe build

- [ ] **Step 1: Verify the required implementation structure**

```powershell
rg -n "ResponseEntity<PostResponse>|thenCombine|PostCreationValidation|ContentValidationResponse" feed-service/src/main
```

Expected: controller raw-response signature plus service combination and validation records.

- [ ] **Step 2: Verify removed or forbidden API patterns**

```powershell
rg -n "DeferredResult|CompletableFuture<ApiResponse<PostResponse>>|AsyncPostService" feed-service/src/main
```

Expected: no matches.

- [ ] **Step 3: Run final verification**

```powershell
git diff --check
mvn -pl feed-service test
git status --short
```

Expected: no whitespace errors, all feed-service tests pass, and unrelated pre-existing documentation changes remain untouched.
