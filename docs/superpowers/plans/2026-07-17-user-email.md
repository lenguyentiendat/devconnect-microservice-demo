# User Email Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add normalized, validated, case-insensitively unique email support to User Service create/update APIs while preserving legacy rows and the internal user-status contract.

**Architecture:** Keep `String` as the persistence/API type and centralize normalization in `EmailNormalizer`. Flyway V2 leaves `email` nullable for legacy rows, adds a generated lowercase key, and indexes it uniquely; a placeholder accounts for PostgreSQL requiring `STORED` while H2 rejects that keyword. Public write DTOs and `UserResponse` carry email, while `UserStatusResponse` and Feed contracts remain unchanged.

**Tech Stack:** Java 21, Spring Boot Web MVC, Jakarta Validation, Spring Data JPA, Flyway, PostgreSQL 18, H2 2.4 PostgreSQL mode, JUnit 5, Mockito, MockMvc, Maven.

## Global Constraints

- Email is required on create, trimmed, lowercased with `Locale.ROOT`, valid by `@Email`, and at most 254 characters.
- Email is optional on update so old PUT bodies containing only `status` remain valid.
- Email uniqueness is case-insensitive in the service pre-check and database.
- Existing/seed rows keep `NULL`; no fake email values are generated.
- Duplicate email uses the existing 409 conflict envelope and message `Email already exists`.
- Public create/update responses include email; the lightweight internal status response does not.
- Do not add email to Feed contracts, events, logs, tokens, claims, or unrelated services.
- Preserve pre-existing user edits in the dirty worktree and do not edit `docs/CreateEmailForUserService.md`.

---

### Task 1: Database schema and persistence mapping

**Files:**
- Create: `user-service/src/main/resources/db/migration/V2__add_user_email.sql`
- Create: `user-service/src/main/java/com/devconnect/user/support/EmailNormalizer.java`
- Modify: `user-service/src/main/resources/application.yaml`
- Modify: `user-service/src/test/resources/application.yaml`
- Modify: `user-service/src/main/java/com/devconnect/user/persistence/UserEntity.java`
- Modify: `user-service/src/main/java/com/devconnect/user/repository/UserRepository.java`
- Modify: `user-service/src/test/java/com/devconnect/user/UserServiceApplicationTests.java`

**Interfaces:**
- Consumes: existing `users(user_id,status)` and Flyway V1 seed rows.
- Produces: `UserEntity(String,String,String)`, `getEmail()`, `updateEmail(String)`, and `existsByEmailIgnoreCase(String)`.

- [ ] **Step 1: Write failing persistence tests**

Autowire `UserRepository`; test normalized persistence, nullable email on seed `u001`, and database rejection of `user@example.com` plus `USER@example.com`.

~~~java
@Test
void persistsNormalizedEmailAndKeepsLegacySeedNullable() {
    var saved = userRepository.saveAndFlush(
            new UserEntity("email-user", "ACTIVE", "  User@Example.COM  ")
    );

    assertEquals("user@example.com", saved.getEmail());
    assertNull(userRepository.findById("u001").orElseThrow().getEmail());
}

@Test
void databaseRejectsEmailThatDiffersOnlyByCase() {
    userRepository.saveAndFlush(new UserEntity("email-a", "ACTIVE", "user@example.com"));

    assertThrows(
            DataIntegrityViolationException.class,
            () -> userRepository.saveAndFlush(
                    new UserEntity("email-b", "ACTIVE", "USER@example.com")
            )
    );
}
~~~

- [ ] **Step 2: Run RED**

~~~powershell
mvn -pl user-service -Dtest=UserServiceApplicationTests test
~~~

Expected: compilation fails because the three-argument constructor and `getEmail()` do not exist.

- [ ] **Step 3: Add portable Flyway migration**

Main config under `spring.flyway`:

~~~yaml
placeholders:
  emailGeneratedStorage: STORED
~~~

Test config override:

~~~yaml
placeholders:
  emailGeneratedStorage: ""
~~~

Migration:

~~~sql
ALTER TABLE users
    ADD COLUMN email VARCHAR(254);

ALTER TABLE users
    ADD COLUMN email_normalized VARCHAR(254)
        GENERATED ALWAYS AS (LOWER(email)) ${emailGeneratedStorage};

CREATE UNIQUE INDEX users_email_lower_unique
    ON users (email_normalized);
~~~

- [ ] **Step 4: Add normalization and entity/repository mapping**

~~~java
package com.devconnect.user.support;

import java.util.Locale;

public final class EmailNormalizer {

    private EmailNormalizer() {
    }

    public static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
~~~

Add `email` as `@Column(name = "email", length = 254)`. Keep the two-argument entity constructor delegating to the new constructor with `null`; normalize in the three-argument constructor and `updateEmail`. Add `boolean existsByEmailIgnoreCase(String email)` to the repository.

- [ ] **Step 5: Run GREEN**

~~~powershell
mvn -pl user-service -Dtest=UserServiceApplicationTests test
~~~

Expected: all tests pass, including legacy seed loading and database case-insensitive uniqueness.

---

### Task 2: Service behavior and response mapping

**Files:**
- Modify: `user-service/src/main/java/com/devconnect/user/dto/UserResponse.java`
- Modify: `user-service/src/main/java/com/devconnect/user/service/UserService.java`
- Modify: `user-service/src/test/java/com/devconnect/user/service/UserServiceTests.java`
- Modify: `user-service/src/test/java/com/devconnect/user/UserServiceApplicationTests.java`

**Interfaces:**
- Consumes: Task 1 entity/repository interfaces.
- Produces: `createUser(String,String,String)`, `updateUser(String,String,String)`, and `UserResponse(userId,status,email)`.

- [ ] **Step 1: Write failing service tests**

Update valid create to pass `"  User@Example.COM  "`, assert response `user@example.com`, and verify `existsByEmailIgnoreCase("user@example.com")`. Add duplicate-case create, normalized email update, duplicate update, and status-only update retaining the old email.

~~~java
@Test
void rejectsEmailThatAlreadyExistsIgnoringCase() {
    when(userRepository.existsById("u004")).thenReturn(false);
    when(userRepository.existsByEmailIgnoreCase("user@example.com")).thenReturn(true);

    var exception = assertThrows(
            UserAlreadyExistsException.class,
            () -> userService.createUser("u004", "ACTIVE", " USER@EXAMPLE.COM ")
    );

    assertEquals("Email already exists", exception.getMessage());
    verify(userRepository, never()).saveAndFlush(any());
}
~~~

- [ ] **Step 2: Run RED**

~~~powershell
mvn -pl user-service -Dtest=UserServiceTests test
~~~

Expected: compilation fails on the new service signatures and `UserResponse.email()`.

- [ ] **Step 3: Implement service behavior**

Change response:

~~~java
public record UserResponse(String userId, String status, String email) {
}
~~~

Create flow normalizes before querying, checks user ID first, then `existsByEmailIgnoreCase`, saves a three-field entity, and maps email. Update flow finds the user, always updates status, and only checks/changes email when a non-null value differs from the existing normalized value.

Translate named email-index race failures without logging an address:

~~~java
private boolean isEmailConstraintViolation(Throwable exception) {
    Throwable current = exception;
    while (current != null) {
        String message = current.getMessage();
        if (message != null && message.toLowerCase(Locale.ROOT)
                .contains("users_email_lower_unique")) {
            return true;
        }
        current = current.getCause();
    }
    return false;
}
~~~

On create, named email-index violations become `Email already exists`; other integrity violations preserve `User already exists`. On update, integrity violations become `Email already exists`.

- [ ] **Step 4: Update integration flow**

Create with `" Integration@Example.COM "`, update to `updated@example.com`, and assert both normalized public responses. Continue using `getUserStatus` to prove the lightweight contract remains email-free.

- [ ] **Step 5: Run GREEN**

~~~powershell
mvn -pl user-service -Dtest=UserServiceTests,UserServiceApplicationTests test
~~~

Expected: both test classes pass with zero failures/errors.

---

### Task 3: HTTP validation and API contract

**Files:**
- Modify: `user-service/src/main/java/com/devconnect/user/dto/CreateUserRequest.java`
- Modify: `user-service/src/main/java/com/devconnect/user/dto/UpdateUserRequest.java`
- Modify: `user-service/src/main/java/com/devconnect/user/controller/UserInternalController.java`
- Modify: `user-service/src/test/java/com/devconnect/user/controller/UserInternalControllerTests.java`

**Interfaces:**
- Consumes: Task 2 three-argument service methods and email-bearing response.
- Produces: required create email, optional update email, unchanged internal status JSON.

- [ ] **Step 1: Write failing MVC tests**

Update valid create to send whitespace/uppercase email, verify the normalized service argument, and assert `$.data.email`. Add missing, invalid, oversized create email tests; normalized update success; blank/invalid update tests; and duplicate update 409. Keep status-only PUT and internal GET regression tests.

~~~java
@Test
void rejectsMissingCreateEmail() throws Exception {
    mockMvc.perform(post("/api/users")
                    .contentType("application/json")
                    .content("""
                            {"userId":"u004","status":"ACTIVE"}
                            """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.message").value("email is required"));

    verifyNoInteractions(userService);
}
~~~

- [ ] **Step 2: Run RED**

~~~powershell
mvn -pl user-service -Dtest=UserInternalControllerTests test
~~~

Expected: compile/behavior failures because request DTOs and controller calls lack email.

- [ ] **Step 3: Add DTO normalization and validation**

Create request:

~~~java
public record CreateUserRequest(
        @NotBlank(message = "userId is required")
        @Size(max = 64, message = "userId must not exceed 64 characters")
        String userId,
        @NotBlank(message = "status is required")
        @Pattern(regexp = "ACTIVE|INACTIVE", message = "status must be ACTIVE or INACTIVE")
        String status,
        @NotBlank(message = "email is required")
        @Email(message = "email must be valid")
        @Size(max = 254, message = "email must not exceed 254 characters")
        String email
) {
    public CreateUserRequest {
        email = EmailNormalizer.normalize(email);
    }
}
~~~

Update request:

~~~java
public record UpdateUserRequest(
        @NotBlank(message = "status is required")
        @Pattern(regexp = "ACTIVE|INACTIVE", message = "status must be ACTIVE or INACTIVE")
        String status,
        @Pattern(regexp = ".*\\S.*", message = "email must not be blank")
        @Email(message = "email must be valid")
        @Size(max = 254, message = "email must not exceed 254 characters")
        String email
) {
    public UpdateUserRequest {
        email = EmailNormalizer.normalize(email);
    }
}
~~~

- [ ] **Step 4: Pass email through controller writes**

Call `createUser(request.userId(), request.status(), request.email())` and `updateUser(userId, request.status(), request.email())`.

- [ ] **Step 5: Run GREEN**

~~~powershell
mvn -pl user-service test
~~~

Expected: all User Service tests pass; internal status JSON still contains only `userId` and `status`.

---

### Task 4: API/database documentation

**Files:**
- Modify: `README.md`
- Modify: `docs/API.md`
- Modify: `docs/DATABASE.md`

**Interfaces:**
- Consumes: Tasks 1-3 behavior.
- Produces: accurate public examples, validation/conflict behavior, and migration notes.

- [ ] **Step 1: Update create contract**

Add required `email` to request examples and public responses. Document trim/lowercase, valid format, max 254, and duplicate 409.

- [ ] **Step 2: Update PUT contract**

Document optional email: omitting it retains the stored address; supplying it updates after normalization and may return 409.

- [ ] **Step 3: Document migration**

Describe nullable legacy rows, generated lowercase key, unique index, the PostgreSQL/H2 placeholder difference, no fake backfill, and the prerequisite for a future `NOT NULL` migration.

- [ ] **Step 4: Search stale examples**

~~~powershell
rg -n "POST /api/users|PUT /api/users|userId.*status|users \(" README.md docs/API.md docs/DATABASE.md
~~~

Expected: create examples include email, PUT explains optional email, internal status remains unchanged.

---

### Task 5: Verification and review

**Files:** Verify only; modify scoped files only when a failing check proves a defect.

- [ ] **Step 1: Focused tests**

~~~powershell
mvn -pl user-service -Dtest=UserServiceTests,UserInternalControllerTests,UserServiceApplicationTests test
~~~

Expected: zero failures/errors.

- [ ] **Step 2: User Service clean build**

~~~powershell
mvn -pl user-service clean verify
~~~

Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Full reactor build**

~~~powershell
mvn clean verify
~~~

Expected: every module succeeds with zero test failures/errors.

- [ ] **Step 4: Compose validation**

~~~powershell
docker compose config --quiet
~~~

Expected: exit 0; do not start or remove containers/volumes.

- [ ] **Step 5: Privacy and compatibility review**

Confirm no logging contains email, `UserStatusResponse` and Feed files are unchanged, legacy rows stay nullable, and no event/token contains email.

- [ ] **Step 6: Final diff review**

~~~powershell
git diff --check
git status --short
git diff --stat
~~~

Expected: no whitespace errors; task changes are separated from pre-existing dirty files.

