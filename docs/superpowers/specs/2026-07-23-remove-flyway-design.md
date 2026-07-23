# Remove Flyway from User Service Design

## Goal

Remove Flyway completely from the project while keeping `user-service` able
to start against an empty PostgreSQL database and create users through
`POST /api/users`.

## Chosen approach

`user-service` will use Hibernate schema synchronization with
`spring.jpa.hibernate.ddl-auto=update` in both its runtime and test
configuration. No database migration framework, migration resource, or
automatic demo-data seed will replace Flyway.

This is intended for the repository's local/demo environment. Existing data
is retained when Hibernate can reconcile it with the `UserEntity` mapping;
the application no longer depends on `flyway_schema_history`.

## Scope

- Remove the Flyway starter and PostgreSQL Flyway runtime dependency from
  `user-service/pom.xml`.
- Remove every `spring.flyway` configuration block.
- Delete `user-service/src/main/resources/db/migration/` and its SQL files.
- Change JPA schema mode from `validate` to `update` for runtime and H2 tests.
- Replace integration tests that assert migration seed/history behavior with
  tests that create their own users against the Hibernate-managed schema.
- Update user/database/architecture/development documentation to describe
  Hibernate-managed schema and API-based user creation, without claiming
  database-level Flyway generated-column or history behavior.

## Data and API behavior

The `users` table is derived from `UserEntity`: `user_id` is the primary key,
`status` is required, and `email` remains nullable and unique. The application
normalizes email to lowercase before persisting it, so a normal unique
constraint on `email` preserves case-insensitive uniqueness without the former
generated column. `POST /api/users` remains the supported way to create user
records; it requires `userId`, `status`, and `email`.

The former `u001`, `u002`, and `u003` demo seed records are removed. Any
consumer or manual test needing a user must create it explicitly before use.

## Error handling and verification

On an empty PostgreSQL database, application startup must create the mapped
schema without a Flyway dependency or a `flyway_schema_history` table. On an
existing local database, startup must not fail merely because that history
table is absent.

Tests will load the Spring context with H2 and create their own user data.
Verification includes a Maven package build for `user-service` and a Compose
smoke test that waits for `devconnect-user-service` to become healthy, then
creates a user through `POST /api/users`.
