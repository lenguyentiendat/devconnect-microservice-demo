# User API Documentation Synchronization Design

## Goal

Synchronize all nine current DevConnect documentation files with the implemented User Service create/update API while preserving historical design and implementation records under `docs/superpowers/`.

## Scope

The following current documentation files will be reviewed and updated:

- `README.md`
- `ASYNC-JAVA.md`
- `docs/README.md`
- `docs/API.md`
- `docs/ARCHITECTURE.md`
- `docs/DATABASE.md`
- `docs/DEVELOPMENT.md`
- `docs/DOCKER.md`
- `docs/EVENTS.md`

Existing specs and plans under `docs/superpowers/` are historical snapshots and will not be rewritten, except for this new spec and its implementation plan.

## Documentation strategy

Use targeted synchronization rather than repeating the complete API contract in every file. Each document remains authoritative for its own concern and links readers to `docs/API.md` for the full REST contract.

### Root README

Keep the quick-start orientation. Ensure the service summary, create/update examples, system flow, test coverage, and documentation links all reflect the current User API.

### Documentation index

Update `docs/README.md` so the API, architecture, database, development, Docker, events, and async descriptions mention the User write capability where relevant. Keep fixed ports and service ownership facts consistent with Compose.

### REST API reference

Make `docs/API.md` the authoritative source for:

- `POST /api/users` with `201 Created`
- `PUT /api/users/{userId}` with `200 OK`
- `GET /internal/users/{userId}/status` with its existing compatibility contract
- request field constraints
- `400`, `404`, `409`, and `500` error envelopes
- PowerShell and cURL examples

The update endpoint remains strict: an unknown user returns `404 Not Found` and is never created implicitly.

### Architecture

Add the User write flow and boundaries to `docs/ARCHITECTURE.md`: controller validation, `UserService`, JPA repository, PostgreSQL constraints, and the unchanged Feed-to-User status lookup. Explicitly state that Search and Notification are not coupled to User writes.

### Database

Document how both write endpoints use the existing `users(user_id,status)` schema, why no migration is needed, how `saveAndFlush` surfaces constraint violations, and how the primary key remains the concurrency-safe duplicate guard.

### Development workflow

Update `docs/DEVELOPMENT.md` with the current automated test strategy and a copy-pasteable smoke sequence:

1. Create an active user.
2. Read the internal status endpoint.
3. Create a Feed post as that user.
4. Update the user to inactive.
5. Read the new status.
6. Verify a subsequent Feed create is rejected.

The documentation must distinguish automated H2/MockRestServiceServer coverage from runtime Compose smoke testing.

### Docker guide

Update `docs/DOCKER.md` to identify the User Service public write endpoints and add host-port verification examples. Container port `8081` and host port `3000` remain unchanged; no Compose topology change is implied.

### Event reference

Clarify in `docs/EVENTS.md` that User create/update currently emits no Kafka events. `post-events` and `POST_CREATED` remain the only event contract, so Search and Notification require no change.

### Async guide

Clarify in `ASYNC-JAVA.md` that User create/update request handling is synchronous. The existing async servlet/executor boundary belongs to Feed post creation, and Kafka async processing remains downstream of `POST_CREATED`.

## Consistency rules

- User Service host URL is `http://localhost:3000`; its container and default host-run port is `8081`.
- Only `ACTIVE` and `INACTIVE` are valid user statuses.
- Feed still calls `GET /internal/users/{userId}/status` and only accepts `ACTIVE` authors.
- User create/update does not publish Kafka events.
- Search and Notification have no direct User API dependency.
- Documentation must not claim that user changes require a migration or direct database access.
- Historical `docs/superpowers/` records remain unchanged.

## Verification

After editing, run:

- a scan for stale User Service claims and outdated endpoint paths;
- a local Markdown-link target check for all nine current documentation files;
- a consistency scan for ports, endpoint status codes, and valid statuses;
- `git diff --check`;
- a final review confirming all nine files have an intentional update and no historical records were unintentionally changed.

No production code or automated Java test changes are required for this documentation-only task.
