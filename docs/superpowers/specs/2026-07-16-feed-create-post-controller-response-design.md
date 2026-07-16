# Parallel Post Validation Design

## Goal

Keep the HTTP API synchronous and return `ResponseEntity<PostResponse>` while
reducing validation latency by running author-status lookup and content validation
in parallel inside `FeedService.createPost`.

## Architecture

`FeedController.createPost` calls `FeedService.createPost`, then returns the concrete
post with HTTP `201 Created`. Neither the controller signature nor its HTTP response
exposes `CompletableFuture`.

`FeedService.createPost` creates two executor-backed futures before waiting:

- `authorStatusFuture` calls the existing User Service status endpoint.
- `contentValidationFuture` performs local content validation.

The futures use the existing bounded `postTaskExecutor`, whose default core pool
size is four. `thenCombine` creates a `PostCreationValidation` only after both tasks
complete successfully. The request thread waits once on the combined future. This
keeps the HTTP request synchronous while allowing both independent validations to
overlap. With a future external moderation call, validation time approaches the
slower call rather than the sum of both calls. The initial local content validation
is fast, so its immediate latency improvement is expected to be small.

After combination, `validatePostCreation` rejects inactive authors or disallowed
content. Only valid requests reach `createAndSavePost`, which preserves the existing
Cassandra save and `POST_CREATED` event publication behavior.

## Content Validation

Add `ContentValidationResponse(boolean allowed, String reason)` in the feed DTO
package. The local validator rejects:

- null or blank content with `Post content must not be blank`;
- content longer than 5,000 characters with
  `Post content must not exceed 5000 characters`;
- case-insensitive occurrences of `spam` or `scam` with
  `Post content contains prohibited words`.

All other content returns an allowed response. `CreatePostRequest` validation still
rejects blank HTTP input before the controller executes; the service rule also
protects direct service callers.

## Error Handling

Failures raised by either future are surfaced by `thenCombine().join()` as
`CompletionException`. `FeedService` unwraps runtime causes so the existing
`GlobalExceptionHandler` retains current business and downstream error behavior.
Non-runtime causes become `IllegalStateException("Post creation failed", cause)`.

## Observability

`FeedService` logs the current thread name when author lookup and content validation
start. Under normal executor capacity, logs show the two validations on separate
`post-async-*` worker threads. The existing executor name and sizing configuration
remain unchanged.

## Testing

- Controller MVC coverage verifies HTTP `201`, a raw `PostResponse` body, validation
  errors, and direct delegation to `FeedService`.
- Service concurrency coverage uses a controlled executor to prove both validation
  tasks are submitted before either is awaited.
- Service coverage verifies allowed content creates a post, prohibited content is
  rejected without persistence or publication, inactive authors remain rejected,
  and asynchronous failures are unwrapped.
- The full feed-service suite verifies Spring wiring and regressions.

## Scope

The endpoint remains `/api/feed/posts`. `AsyncPostService` remains removed. This
change does not add a Content Moderation Service, change Cassandra persistence,
change event payloads, or make the HTTP request asynchronous.
