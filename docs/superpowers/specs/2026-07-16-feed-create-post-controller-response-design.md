# Asynchronous Feed Create Post Response Design

## Goal

Keep the create-post HTTP response body as `ApiResponse<PostResponse>` while making
the Spring MVC request genuinely asynchronous. The controller must not return a
`CompletableFuture`, and `AsyncPostService` remains removed.

## Architecture

`FeedService.createPost` returns `CompletableFuture<PostResponse>` created with
`CompletableFuture.supplyAsync(..., postTaskExecutor)`. It does not call `join()` or
otherwise wait for the workflow. The existing author validation, post persistence,
and event publication remain in a private synchronous worker method.

`FeedController.createPost` returns
`DeferredResult<ApiResponse<PostResponse>>`. It registers a completion callback on
the service future and immediately returns the `DeferredResult`, allowing Spring MVC
to release the servlet request thread. When the future succeeds, the controller
sets the existing success envelope and message on the deferred result. Spring then
performs async dispatch and serializes the contained `ApiResponse<PostResponse>` as
the same JSON shape clients already receive.

This design improves request-thread utilization and service throughput under
concurrency. It does not make the User Service call or Cassandra write complete
faster, so the client still receives the final response only after those operations
finish.

## Error Handling

The controller unwraps `CompletionException` when necessary and passes the original
cause to `DeferredResult.setErrorResult`. Spring's async dispatch then routes
`BusinessException`, `DownstreamServiceException`, and unexpected failures through
the existing `GlobalExceptionHandler`, preserving current HTTP status and error
envelopes.

Spring MVC's configured async request timeout remains in effect. This change does
not introduce cancellation of the blocking worker because interruption could leave
author validation, Cassandra persistence, and event publication in an ambiguous
partially completed state.

## Components

- `FeedController` owns HTTP async adaptation and success-envelope creation.
- `FeedService` owns executor submission and the create-post business workflow.
- `AsyncConfig` continues to provide the bounded `postTaskExecutor`.
- `AsyncPostService` is not restored.

## Testing

- Controller MVC success coverage verifies `asyncStarted()`, performs
  `asyncDispatch`, and checks the unchanged success JSON.
- Controller MVC failure coverage completes the service future exceptionally,
  performs `asyncDispatch`, and verifies the existing business-error response.
- Service coverage uses a controlled worker to prove `createPost` returns an
  incomplete future without waiting and that the workflow runs on the post executor.
- Service failure coverage verifies business failures complete the future
  exceptionally.
- The complete feed-service test suite provides regression and Spring wiring
  coverage.

## Scope

The endpoint path, request schema, final response JSON, success message, author
validation, Cassandra persistence, event publication, and executor configuration
remain unchanged. HTTP `202 Accepted`, polling, new response DTOs, and fire-and-forget
post creation are outside this change.
