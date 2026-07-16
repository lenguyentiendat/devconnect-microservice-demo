# Feed-to-User OpenFeign Communication Design

## Goal

Replace all `RestClient` communication from feed-service to user-service with a
declarative Spring Cloud OpenFeign client. Keep the existing user-status HTTP
contract, post-creation behavior, parallel validation, and error responses.

## Dependencies

The root Maven project imports the Spring Cloud `2025.1.2` BOM, which supports the
project's Spring Boot `4.1.0` version. Feed-service adds
`spring-cloud-starter-openfeign`; other services do not add the starter.

## Components

### UserServiceClient

`UserServiceClient` is a Feign interface in feed-service. It declares:

```java
@GetMapping("/internal/users/{userId}/status")
ApiResponse<UserStatusResponse> getUserStatus(
        @PathVariable("userId") String userId
);
```

The client name is `user-service`. Its URL and timeouts come from OpenFeign
configuration properties rather than a URL embedded in the annotation.

### UserServiceAdapter

`UserServiceAdapter` is an internal feed-service component, not an API Gateway. It
wraps `UserServiceClient`, validates the response envelope, and translates Feign
transport failures into the feed-service exception model:

- a valid successful envelope returns `UserStatusResponse`;
- HTTP 404, a null response, an unsuccessful envelope, or missing data throws
  `BusinessException("Author not found")`;
- other Feign HTTP, connection, or decoding failures throw
  `DownstreamServiceException("Failed to call User Service", cause)`.

This boundary keeps `FeedService` independent of Feign exception types. The name
`Adapter` avoids confusion with the separate edge API Gateway planned for the
platform.

### FeedService

`FeedService` receives `UserServiceAdapter` instead of `RestClient`. Its author
validation future calls `userServiceAdapter.getUserStatus(authorId)`. The existing
`CompletableFuture`/`thenCombine` flow, content validation, post persistence, and
event publication remain unchanged.

### Application Bootstrap

`FeedServiceApplication` enables only `UserServiceClient` through
`@EnableFeignClients(clients = UserServiceClient.class)`. `RestClientConfig` is
deleted.

## Configuration

Feed-service configures the named client with standard OpenFeign properties:

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

The old `services.user-service.base-url` property is removed. No service discovery
or load balancer is added in this change.

## API Gateway Boundary

A future API Gateway remains the external entry point for clients and routes them
to feed-service, user-service, and other services. Internal
feed-service-to-user-service traffic continues to call user-service directly with
Feign; it does not route through the edge API Gateway. Implementing the API Gateway
is outside this change.

## Testing

- `UserServiceAdapter` unit tests cover successful decoding, invalid envelopes,
  HTTP 404, and other Feign failures.
- `FeedService` tests mock `UserServiceAdapter`; they no longer create a
  `RestClient` or `MockRestServiceServer`.
- The feed application context test verifies that OpenFeign configuration and the
  generated client bean load with existing Cassandra mocks.
- A repository-wide search verifies that feed-service contains no `RestClient`,
  `RestClientException`, `RestClientConfig`, or `MockRestServiceServer` references.
- The complete feed-service test suite provides regression coverage.

## Scope

User-service code and its endpoint contract remain unchanged. Feed controller
responses, content moderation rules, Cassandra storage, Kafka events, executor
configuration, retries, circuit breakers, service discovery, and API Gateway
implementation are not changed.
