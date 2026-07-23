Analyze the current project structure and implement support for an `email` field in the User Service.

Before modifying anything, inspect the existing architecture, coding conventions, database technology, migration mechanism, validation approach, exception handling, DTO mapping, API design, and test structure. Follow the existing project patterns instead of introducing a new architecture.

Requirements:

1. Add an `email` field to the user-service/src/main/java/com/devconnect/user/persistence/UserEntity.java.
    - Use the appropriate type and persistence annotation based on the current project.
    - The email must be required when creating a new user.
    - Trim leading and trailing spaces.
    - Normalize the email to lowercase before saving.
    - Validate that the value has a valid email format.
    - Add a reasonable maximum length, preferably 254 characters unless the project already uses another convention.

2. Ensure email uniqueness.
    - Add a unique database constraint/index.
    - Add a repository method such as `existsByEmailIgnoreCase` or the equivalent supported by the current data access technology.
    - Check duplicate emails in the service layer before saving.
    - Return the project’s standard conflict or validation exception when an email already exists.
    - Do not rely only on the service-level check; keep the database constraint to prevent race conditions.

3. Update all relevant API models and flows.
    - Create-user request.
    - Update-user request, if the service supports updating users.
    - User response DTO.
    - Entity/domain-to-DTO mappers.
    - Service methods.
    - Controller endpoints.
    - Feign clients, events, commands, projections, or shared contracts that contain user information, but only when required by the existing business flow.

4. Preserve backward compatibility where possible.
    - Do not rename or remove existing API fields.
    - Do not unnecessarily change endpoint paths or response structures.
    - If existing database records may not have an email, create a safe schema-update strategy based on the project’s current bootstrap mechanism.
    - Do not invent fake email values for existing users unless the project explicitly requires a backfill strategy.
    - Explain any migration limitation that requires manual data preparation.

5. Database schema management.
    - Detect whether the project uses schema scripts, JPA auto-DDL, or another mechanism.
    - Update the appropriate schema definition following the project’s conventions.
    - Add the email column and a case-insensitive uniqueness strategy if supported by the current database.
    - Make the migration safe for existing data.

6. Error handling.
    - Use the project’s existing exception types and global exception handler.
    - Duplicate email should produce an appropriate HTTP status, preferably 409 Conflict if that matches the project convention.
    - Invalid or missing email should produce the project’s standard validation response.

7. Security and privacy.
    - Do not log the full email unnecessarily.
    - Do not expose the email in public or lightweight user DTOs unless those DTOs are intended to contain private profile information.
    - Do not add email to tokens or authentication claims unless the current requirements already need it.

8. Tests.
   Add or update tests for:
    - Creating a user with a valid email.
    - Rejecting a missing email.
    - Rejecting an invalid email format.
    - Normalizing uppercase and whitespace.
    - Rejecting a duplicate email, including duplicates with different letter casing.
    - Updating a user’s email, if supported.
    - Database persistence and mapping.
    - Controller/API response behavior.
    - Existing tests must continue to pass.

9. Verification.
    - Run the relevant unit tests and integration tests.
    - Run the project build.
    - Fix compilation errors and test failures caused by the change.
    - Do not suppress tests or remove existing validation to make the build pass.

At the end, provide:
- A summary of the implementation.
- The list of modified and created files.
- The database migration behavior.
- The API contract changes.
- The commands used to test/build the project.
- Any assumptions, risks, or follow-up work.

Proceed with the implementation directly. Only ask for clarification if a critical business decision cannot be inferred from the existing codebase.
