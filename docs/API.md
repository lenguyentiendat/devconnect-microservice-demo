# API Reference

## Base URL and conventions

All public requests go through the Gateway:

```text
http://localhost:8090
```

Do not use service ports `8081`–`8084` from a browser or Swagger UI. The Gateway preserves each controller path; it does not strip a prefix.

Most read responses use an envelope:

```json
{ "success": true, "message": "...", "data": {} }
```

Validation failures use `400 Bad Request`; missing resources use `404 Not Found`; duplicate user IDs or emails use `409 Conflict`. Feed creation can return `503 Service Unavailable` when User Service validation cannot be reached.

## User Service

### Create user

`POST /api/users`

```json
{
  "userId": "u001",
  "status": "ACTIVE",
  "email": "u001@example.com"
}
```

`userId` is required and at most 64 characters. `status` is `ACTIVE` or `INACTIVE`. `email` is required, valid, at most 254 characters, trimmed, and normalized to lowercase before uniqueness is checked. A successful request returns `201 Created`.

### Update user

`PUT /api/users/{userId}`

```json
{ "status": "INACTIVE", "email": "new-address@example.com" }
```

`email` is optional on update; when present it is normalized and must remain unique. A successful request returns `200 OK`.

The User Service also provides `GET /internal/users/{userId}/status` for Feed Service's discovery-based Feign call. That endpoint is deliberately not routed by Gateway and is not a frontend API.

## Feed Service

### Create post

`POST /api/feed/posts`

```json
{ "authorId": "u001", "content": "Hello from DevConnect" }
```

Both fields are required. Feed Service checks the author's active status through User Service, saves Cassandra read models, publishes a `POST_CREATED` event, and returns `201 Created` with the created post.

### Read posts

`GET /api/feed/posts` returns a cursor-paged feed in an API envelope. The first
page uses optional `pageNum` and `pageSize` parameters (defaults: `1` and `20`):

```bash
curl 'http://localhost:8090/api/feed/posts?pageNum=1&pageSize=20'
```

When `data.hasNext` is `true`, request the next page with the returned token:

```bash
curl --get 'http://localhost:8090/api/feed/posts' \
  --data-urlencode 'pageNum=2' \
  --data-urlencode 'pageSize=20' \
  --data-urlencode 'pageToken=TOKEN_FROM_PREVIOUS_RESPONSE'
```

Clients must treat `pageToken` as opaque: do not parse, construct, or modify it.
`pageToken` is required after page one. The response data contains `items`,
`pageNum`, `pageSize`, `hasNext`, `nextPageToken`, and `feedRevision`.

`GET /api/feed/posts/{postId}` returns one post in an API envelope. Use the `postId` returned by creation.

## Search Service

### Search posts

`GET /api/search/posts?keyword={keyword}`

Example:

```bash
curl 'http://localhost:8090/api/search/posts?keyword=DevConnect'
```

The result contains post projections with `postId`, `authorId`, and `content`. Search Service currently maintains an in-memory projection from `post-events`; it has no Elasticsearch endpoint or Elasticsearch dependency. A newly created post may not appear until its Kafka event is consumed.

## Notification Service

### List a user's notifications

`GET /api/notifications/users/{userId}`

Example:

```bash
curl http://localhost:8090/api/notifications/users/u001
```

The response contains in-memory notifications with `notificationId`, `userId`, `title`, `message`, and `createdAt`. Notifications are created by the Kafka consumer after a post is created, so this endpoint is eventually consistent.

Notification Service exposes REST only in the current implementation. It does not expose SSE or WebSocket endpoints.

## Typical end-to-end sequence

1. Create an active user.
2. Create a post with that user as `authorId`.
3. Poll Search and Notification endpoints briefly until the Kafka projections have consumed the event.

Use [Gateway Swagger UI](http://localhost:8090/swagger-ui.html) for interactive requests. Swagger loads one aggregate document at `/v3/api-docs/aggregate`; see [OpenAPI and CORS](OPENAPI.md) for aggregation and the underlying service documents.
