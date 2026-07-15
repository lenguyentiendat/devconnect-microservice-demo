# User Create and Update Endpoints Design

## Goal

Add two write endpoints to `user-service` so clients can create a user and update an existing user's status without changing the existing service-to-service status lookup contract.

## API contract

### Create user

- Method and path: `POST /api/users`
- Request body: `{"userId":"u004","status":"ACTIVE"}`
- Valid statuses: `ACTIVE`, `INACTIVE`
- Success: `201 Created` with the standard `ApiResponse` envelope and the created user in `data`
- Duplicate `userId`: `409 Conflict`
- Missing, blank, oversized, or invalid fields: `400 Bad Request`

### Update user

- Method and path: `PUT /api/users/{userId}`
- Request body: `{"status":"INACTIVE"}`
- The path identifies the user; the user ID cannot be changed by this operation
- Success: `200 OK` with the standard `ApiResponse` envelope and the updated user in `data`
- Unknown `userId`: `404 Not Found`; the endpoint never creates a user implicitly
- Missing or invalid status: `400 Bad Request`

### Existing internal endpoint

`GET /internal/users/{userId}/status` remains unchanged for `feed-service`. Its response schema and `404 Not Found` behavior remain backward compatible.

## Code structure

Following the selected approach, the new public endpoint methods will be added directly to the existing `UserInternalController`. The controller's class-level `/internal/users` mapping will be replaced by explicit method-level paths so the existing internal GET and the new public POST/PUT can coexist in one controller.

Request and response records will keep transport validation separate from persistence. `UserService` will own duplicate detection, lookup, creation, status update, and entity-to-response mapping. `UserRepository` remains the persistence boundary. `UserEntity` will expose a focused status-update method; no schema migration is required because the current table already stores `user_id` and `status` with the required constraints.

A centralized exception handler will translate validation and domain failures into the existing `ApiResponse` error envelope. Database uniqueness remains the final concurrency-safe guard for duplicate IDs, while the service performs an early existence check to return a clear conflict in the normal path.

## Validation and error handling

- `userId` is required, non-blank, and at most 64 characters to match the database column.
- `status` is required and must be exactly `ACTIVE` or `INACTIVE`.
- Invalid JSON or validation failures return `400` with `success=false` and `data=null`.
- Duplicate creation returns `409`.
- Updating an unknown user returns `404`.
- Unexpected failures return `500` in the same envelope format.

## Service impact

- `user-service`: controller, DTOs, service behavior, entity mutation, exception mapping, and tests change.
- `feed-service`: no production contract change. Its existing call to `GET /internal/users/{userId}/status` must remain green; regression tests will verify active/inactive behavior still works.
- `search-service` and `notification-service`: no direct dependency on user data or the User API, so no code change is required. Their module tests/build still run as part of full verification.
- Docker Compose: no topology or environment change is required. Compose configuration will be validated.

## Testing strategy

Implementation will be test-first:

1. Controller tests cover successful create/update, response status and envelope, validation failures, duplicate ID, unknown update target, and unchanged internal GET behavior.
2. Service tests cover persistence, duplicate detection, update behavior, and missing users.
3. Integration coverage uses the current H2 PostgreSQL-compatibility configuration and Flyway migration to verify repository/entity compatibility.
4. Feed regression tests verify User Service status responses continue to drive author eligibility without any contract change.
5. Run the full root Maven test suite for all four modules, then validate Docker Compose configuration. If the local Docker stack is available, run API smoke tests against the built stack without deleting existing volumes.

## Documentation

Update `README.md`, `docs/API.md`, `docs/ARCHITECTURE.md`, and `docs/DATABASE.md` to describe the new endpoints, status rules, errors, examples, and the fact that User Service data can now be changed through its API.

## Out of scope

- Additional profile fields such as name or email
- Authentication and authorization
- User deletion or list endpoints
- User lifecycle events over Kafka
- Upsert semantics
- Database schema changes
