# Feed Create Post Controller Response Design

## Goal

Change the create-post HTTP endpoint so the controller returns
`ApiResponse<PostResponse>` instead of `CompletableFuture<ApiResponse<PostResponse>>`.
Remove `AsyncPostService` and keep asynchronous task execution inside
`FeedService.createPost`.

## Design

`FeedController` depends only on `FeedService`. Its `createPost` method calls
`FeedService.createPost` and wraps the returned post in the existing success
response with the message `Post created successfully`.

`FeedService` receives the existing `postTaskExecutor`. Its public `createPost`
method submits the existing create-post workflow with `CompletableFuture.supplyAsync`
and waits with `join()` so the method can return a concrete `PostResponse`.
The existing workflow moves to a private synchronous method. This preserves the
required API contract while ensuring the workflow runs on the configured post
executor. The HTTP request still waits for author validation and Cassandra storage;
the change moves work off the request thread but does not reduce response latency.

`AsyncPostService` and its dedicated tests are removed. `AsyncConfig` remains
because `FeedService` still uses `postTaskExecutor`.

## Error Handling

`CompletableFuture.join()` wraps task failures in `CompletionException`.
`FeedService.createPost` unwraps and rethrows runtime causes such as
`BusinessException` and `DownstreamServiceException`, allowing the existing global
exception handlers to preserve their current HTTP status and response body.
Unexpected non-runtime causes are rethrown as an `IllegalStateException`.

## Testing

- A controller MVC test verifies that POST is handled synchronously, returns the
  existing success envelope, and delegates to `FeedService`.
- A controller MVC test verifies that a `BusinessException` still reaches the
  existing handler without async dispatch.
- Service tests verify that the create workflow runs on `postTaskExecutor` and that
  business exceptions are rethrown without a `CompletionException` wrapper.
- Existing feed-service tests run as regression coverage.

## Scope

This change does not alter the endpoint path, request schema, success message,
post persistence semantics, event publication behavior, or executor configuration.
