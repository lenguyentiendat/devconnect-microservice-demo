# Remove Flyway from User Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove Flyway completely while allowing `user-service` to manage its local/demo PostgreSQL schema through Hibernate and create users through the existing API.

**Architecture:** Hibernate becomes the only schema bootstrapper for User Service. `UserEntity` expresses the `users` schema, including a unique normalized email; Flyway dependencies, properties and SQL migrations disappear. Tests create their own data instead of relying on migration seeds.

**Tech Stack:** Spring Boot 4.1, Spring Data JPA/Hibernate, PostgreSQL, H2, Maven, Docker Compose.

## Global Constraints

- Remove all Flyway dependencies, configuration, resources, tests and user-facing documentation references.
- Use `spring.jpa.hibernate.ddl-auto=update` in runtime and test profile.
- Do not replace Flyway with another migration tool or a new automatic seed mechanism.
- Preserve the existing `POST /api/users` and `PUT /api/users/{userId}` contracts.
- Retain case-insensitive email uniqueness by storing normalized lowercase email in a unique `users.email` column.

---

### Task 1: Make the Hibernate mapping self-sufficient

**Files:**
- Modify: `user-service/src/main/java/com/devconnect/user/persistence/UserEntity.java`
- Modify: `user-service/src/main/resources/application.yaml`
- Modify: `user-service/src/test/resources/application.yaml`
- Test: `user-service/src/test/java/com/devconnect/user/UserServiceApplicationTests.java`

**Interfaces:**
- Produces: a Hibernate-created `users` table with primary key `user_id`, required `status`, and unique `email`.
- Consumes: `UserService.createUser(String, String, String)` and `UserRepository`.

- [ ] **Step 1: Write the failing integration test for a schema with no seed users**

Replace the seed-dependent assertions with a test that stores a new `UserEntity` and verifies its normalized email can be read back:

```java
@Test
void persistsNormalizedEmailAgainstHibernateManagedSchema() {
    userRepository.saveAndFlush(new UserEntity("email-user", "ACTIVE", " User@Example.COM "));

    assertEquals("user@example.com", userRepository.findById("email-user").orElseThrow().getEmail());
}
```

- [ ] **Step 2: Run the integration test to verify it fails before schema bootstrap changes**

Run: `mvn -B -ntp -pl user-service -am -Dtest=UserServiceApplicationTests test`

Expected: the context cannot bootstrap an empty schema with `ddl-auto=validate` once Flyway is removed.

- [ ] **Step 3: Express the schema in JPA and remove Flyway configuration**

Set `@Column(name = "email", length = 254, unique = true)` on `UserEntity.email`. In both YAML files replace:

```yaml
hibernate:
  ddl-auto: validate
flyway:
  enabled: true
```

with:

```yaml
hibernate:
  ddl-auto: update
```

Also remove the Flyway placeholder blocks.

- [ ] **Step 4: Run the integration test to verify Hibernate creates and maps the schema**

Run: `mvn -B -ntp -pl user-service -am -Dtest=UserServiceApplicationTests test`

Expected: `UserServiceApplicationTests` passes with no Flyway startup.

### Task 2: Remove Flyway assets and migration-dependent tests

**Files:**
- Modify: `user-service/pom.xml`
- Delete: `user-service/src/main/resources/db/migration/V1__create_users.sql`
- Delete: `user-service/src/main/resources/db/migration/V2__add_user_email.sql`
- Modify: `user-service/src/test/java/com/devconnect/user/UserServiceApplicationTests.java`

**Interfaces:**
- Produces: User Service with no Flyway classpath dependency or SQL migration input.
- Consumes: the Hibernate mapping from Task 1.

- [ ] **Step 1: Remove the two Flyway dependencies**

Delete these dependency blocks from `user-service/pom.xml`:

```xml
<artifactId>spring-boot-starter-flyway</artifactId>
<artifactId>flyway-database-postgresql</artifactId>
```

- [ ] **Step 2: Delete the migration resources**

Delete `V1__create_users.sql` and `V2__add_user_email.sql`; do not add replacement seed SQL.

- [ ] **Step 3: Remove obsolete seed and generated-column assertions**

Delete `flywaySeedsDemoUsers` and replace `persistsNormalizedEmailAndKeepsLegacySeedNullable` with the Hibernate-managed schema assertion from Task 1. Replace the direct database race assertion with a service-level case-insensitive duplicate test that calls `createUser` twice and expects `UserAlreadyExistsException`.

```java
assertThrows(UserAlreadyExistsException.class,
        () -> userService.createUser("email-b", "ACTIVE", "USER@example.com"));
```

- [ ] **Step 4: Run user-service tests**

Run: `mvn -B -ntp -pl user-service -am test`

Expected: all user-service tests pass without a Flyway artifact on the classpath.

### Task 3: Update documentation and verify Compose startup

**Files:**
- Modify: `docs/DATABASE.md`
- Modify: `docs/ARCHITECTURE.md`
- Modify: `docs/DEVELOPMENT.md`
- Modify: `docs/README.md`

**Interfaces:**
- Produces: documentation that directs users to Hibernate-managed schema and `POST /api/users`, not Flyway history/migrations.

- [ ] **Step 1: Replace Flyway operational guidance**

Describe `ddl-auto=update`, the absence of demo seed users, and creating users through:

```bash
curl -X POST http://localhost:8081/api/users \
  -H 'Content-Type: application/json' \
  -d '{"userId":"u001","status":"ACTIVE","email":"u001@example.com"}'
```

Remove migration rules, generated-column details and `flyway_schema_history` commands.

- [ ] **Step 2: Verify no Flyway reference remains**

Run: `rg -n -i "flyway" --glob '!docs/superpowers/**'`

Expected: no matches.

- [ ] **Step 3: Build and smoke-test the user service**

Run: `mvn -B -ntp -pl user-service -am package -Dmaven.test.skip=true`

Then run `docker compose up -d --build user-service`, wait for `docker compose ps user-service` to report `healthy`, and POST the example user. Expect HTTP `201`.

- [ ] **Step 4: Commit**

```bash
git add user-service docs
git commit -m "refactor(user): remove Flyway schema management"
```
