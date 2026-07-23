# Last-Created Cursor Pagination Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** Replace page-token pagination in Feed Service with a two-field last-created cursor.

**Architecture:** The controller and service accept a nullable cursor pair. Cassandra fetches pageSize + 1 rows using ordered clustering-key restrictions, and the cache hashes the cursor pair under the existing revisioned page-key namespace. No driver paging state or page-token secret remains.

**Tech Stack:** Java 21, Spring Boot, Spring Data Cassandra, DataStax CQL driver, Caffeine, Redis, JUnit 5, Mockito, MockMvc.

## Global Constraints

- Remove pageNum, pageToken, nextPageToken, PageTokenCodec and CACHE_PAGE_TOKEN_SECRET.
- A cursor is either absent as a pair or present as both ISO-8601 LocalDateTime and UUID.
- Preserve L1 Caffeine, L2 Redis, revision invalidation and fail-open Cassandra behavior.
- Do not refactor code outside Feed pagination and directly related documentation/tests.
- Do not use Redis KEYS or SCAN in application cache invalidation.

---

### Task 1: Define cursor DTOs, key behavior and validation tests

**Files:**

- Modify: feed-service/src/main/java/com/devconnect/feed/dto/FeedPage.java
- Modify: feed-service/src/main/java/com/devconnect/feed/dto/FeedPageResponse.java
- Modify: feed-service/src/main/java/com/devconnect/feed/cache/CacheKeyFactory.java
- Modify: feed-service/src/main/java/com/devconnect/feed/config/CacheProperties.java
- Delete: feed-service/src/main/java/com/devconnect/feed/paging/PageTokenCodec.java
- Modify: feed-service/src/test/java/com/devconnect/feed/cache/CacheKeyFactoryTests.java
- Delete: feed-service/src/test/java/com/devconnect/feed/paging/PageTokenCodecTests.java

**Interfaces:**

- Produces FeedPage(List<PostResponse> items, boolean hasNext).
- Produces FeedPageResponse(List<PostResponse> items, int pageSize, boolean hasNext, LocalDateTime nextLastCreatedAt, String nextLastPostId, long feedRevision).
- Produces CacheKeyFactory.feedPage(String feedId, long revision, int size, LocalDateTime lastCreatedAt, String lastPostId).

- [ ] **Step 1: Write failing cache-key tests**

Add tests asserting a first-page key ends in cursor:first and a later-page key hashes the literal ISO timestamp, a pipe, and UUID. Assert different cursor values create different keys.

- [ ] **Step 2: Run the cache-key tests and observe compilation/test failure**

Run: cd feed-service && ./mvnw -Dtest=CacheKeyFactoryTests test

Expected: FAIL because feedPage does not accept the cursor pair or still accepts token input.

- [ ] **Step 3: Implement the DTO/key/config removals**

Replace FeedPage paging state with hasNext. Replace FeedPageResponse page number/token fields with next cursor pair. Change CacheKeyFactory to derive cursor:first only when both values are null, otherwise require both and hash lastCreatedAt + "|" + lastPostId. Remove pageTokenSecret from CacheProperties and delete PageTokenCodec.

- [ ] **Step 4: Re-run cache-key tests**

Run: cd feed-service && ./mvnw -Dtest=CacheKeyFactoryTests test

Expected: PASS.

### Task 2: Implement Cassandra cursor queries

**Files:**

- Modify: feed-service/src/main/java/com/devconnect/feed/persistence/PostStore.java
- Modify: feed-service/src/main/java/com/devconnect/feed/persistence/CassandraPostStore.java
- Modify: feed-service/src/test/java/com/devconnect/feed/persistence/CassandraPostStoreTests.java

**Interfaces:**

- Consumes findPage(int pageSize, Instant lastCreatedAt, UUID lastPostId).
- Produces at most pageSize items plus hasNext through an internal pageSize + 1 read.
- First page has both cursor arguments null; later page has both non-null.

- [ ] **Step 1: Write failing store tests**

Add tests that capture statements for first page LIMIT 21, same-timestamp continuation with post_id > cursor UUID, and older-timestamp continuation with created_at < cursor instant. Add a test that verifies the extra row is omitted from items and sets hasNext true.

- [ ] **Step 2: Run the Cassandra store tests and observe failure**

Run: cd feed-service && ./mvnw -Dtest=CassandraPostStoreTests test

Expected: FAIL because the store still binds driver PagingState and returns it in FeedPage.

- [ ] **Step 3: Implement two-phase cursor reads**

Change PostStore and CassandraPostStore to receive an Instant/UUID pair. Query one global-feed page with LIMIT pageSize + 1 for the first page. For a later page, query same-created-at rows with post_id > cursor, then older rows with created_at < cursor only when capacity remains. Merge in database order, trim to pageSize, and set hasNext from the extra row. Remove PagingState imports and copy code.

- [ ] **Step 4: Re-run store tests**

Run: cd feed-service && ./mvnw -Dtest=CassandraPostStoreTests test

Expected: PASS.

### Task 3: Replace service/controller contract and preserve cache behavior

**Files:**

- Modify: feed-service/src/main/java/com/devconnect/feed/service/FeedService.java
- Modify: feed-service/src/main/java/com/devconnect/feed/controller/FeedController.java
- Modify: feed-service/src/test/java/com/devconnect/feed/service/FeedServiceCacheTests.java
- Modify: feed-service/src/test/java/com/devconnect/feed/service/FeedServiceParallelValidationTests.java
- Modify: feed-service/src/test/java/com/devconnect/feed/service/FeedServiceUserContractTests.java
- Modify: feed-service/src/test/java/com/devconnect/feed/controller/FeedControllerTests.java
- Modify: feed-service/src/test/java/com/devconnect/feed/controller/FeedOpenApiTests.java
- Modify: feed-service/src/test/java/com/devconnect/feed/integration/RedisCacheIntegrationTests.java
- Modify: feed-service/src/test/java/com/devconnect/feed/cache/FeedRevisionServiceTests.java
- Modify: feed-service/src/test/java/com/devconnect/feed/cache/TwoLevelCacheServiceTests.java
- Modify: feed-service/src/test/java/com/devconnect/feed/cache/CacheInvalidationPublisherTests.java
- Modify: feed-service/src/test/java/com/devconnect/feed/FeedServiceApplicationTests.java

**Interfaces:**

- FeedService.getPosts(int pageSize, LocalDateTime lastCreatedAt, String lastPostId).
- FeedController accepts pageSize, lastCreatedAt and lastPostId query parameters only.
- A partial or malformed cursor raises BusinessException and becomes HTTP 400.

- [ ] **Step 1: Write failing service/controller tests**

Add tests for first-page cache key, later-page key, a partial cursor, malformed timestamp, malformed UUID, and a valid later page. Add MockMvc tests proving pageNum/pageToken are absent from OpenAPI and both new cursor fields are present.

- [ ] **Step 2: Run focused service/controller tests and observe failure**

Run: cd feed-service && ./mvnw -Dtest=FeedServiceCacheTests,FeedControllerTests,FeedOpenApiTests test

Expected: FAIL because current code requires pageNum/pageToken and emits nextPageToken.

- [ ] **Step 3: Implement cursor-pair service/controller behavior**

Remove PageTokenCodec injection and page-number logic. Parse lastPostId only after pair validation, convert lastCreatedAt to UTC Instant for PostStore, use the new page key, and derive the next response cursor from the final returned item only if hasNext is true. Update tests and CacheProperties constructors to omit the removed secret.

- [ ] **Step 4: Re-run focused tests**

Run: cd feed-service && ./mvnw -Dtest=FeedServiceCacheTests,FeedControllerTests,FeedOpenApiTests,FeedServiceParallelValidationTests,FeedServiceUserContractTests test

Expected: PASS.

### Task 4: Remove configuration/docs and verify the module

**Files:**

- Modify: feed-service/src/main/resources/application.yaml
- Modify: docker-compose.yml
- Modify: docs/API.md
- Modify: docs/OPENAPI.md
- Modify: docs/REDIS_CACHE.md
- Modify: README.md when it references opaque cursor behavior

- [ ] **Step 1: Remove page-token configuration and documentation**

Delete page-token-secret YAML/Compose values and revise API examples, OpenAPI guidance and Redis cache key documentation to use lastCreatedAt and lastPostId.

- [ ] **Step 2: Prove page-token references are gone**

Run: rg -n -i 'pageToken|page-token|PageToken|CACHE_PAGE_TOKEN_SECRET|PagingState' feed-service docs README.md docker-compose.yml

Expected: no application, test, configuration or user-facing documentation hits; only historical superpowers design/plan documents may still mention the removed behavior.

- [ ] **Step 3: Run module verification**

Run: cd feed-service && ./mvnw test

Expected: PASS when JDK 21, Maven wrapper access and Docker are available; Testcontainers Redis integration is skipped only when Docker is unavailable.

- [ ] **Step 4: Commit**

Run:

git add feed-service docker-compose.yml docs/API.md docs/OPENAPI.md docs/REDIS_CACHE.md README.md docs/superpowers/plans/2026-07-23-last-created-cursor-pagination.md
git commit -m "feat(feed): use last-created cursor pagination"
