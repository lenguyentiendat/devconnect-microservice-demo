# No-Prefix Cache Eviction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove all prefix-based cache eviction while retaining exact post invalidation and revisioned page caching.

**Architecture:** Page keys are revisioned; advancing the revision makes old pages unreachable until TTL expiry. Pub/Sub distributes only exact post invalidation.

**Tech Stack:** Java 21, Spring Boot, Caffeine, Redis, JUnit 5.

## Global Constraints

- Application cache code must not use prefix eviction, Redis `SCAN`, or Redis `KEYS`.
- Cassandra remains authoritative and cache errors fail open.
- Kafka post event behavior remains unchanged.

### Task 1: Remove prefix eviction implementation and contracts

**Files:**
- Modify: `feed-service/src/main/java/com/devconnect/feed/cache/CacheService.java`
- Modify: `feed-service/src/main/java/com/devconnect/feed/cache/NoOpCacheService.java`
- Modify: `feed-service/src/main/java/com/devconnect/feed/cache/TwoLevelCacheService.java`
- Modify: `feed-service/src/main/java/com/devconnect/feed/cache/RedisCacheStore.java`
- Modify: `feed-service/src/main/java/com/devconnect/feed/cache/CacheInvalidation.java`
- Modify: `feed-service/src/main/java/com/devconnect/feed/cache/CacheInvalidationListener.java`
- Modify: `feed-service/src/main/java/com/devconnect/feed/service/FeedService.java`
- Modify: affected cache/service tests

- [ ] **Step 1:** Remove `evictPrefixKey`, `scanDelete`, prefix payload/listener logic, and all call sites.
- [ ] **Step 2:** Keep exact post eviction/replacement and revision advance; do not delete page keys after writes.
- [ ] **Step 3:** Add regression tests proving no prefix cache operation exists and page invalidation depends on revision.
- [ ] **Step 4:** Run focused cache/service tests and commit.

### Task 2: Align integration tests and documentation

**Files:**
- Modify: Redis integration tests and cache documentation.

- [ ] **Step 1:** Remove prefix-delete integration assertions and document revision-only page invalidation.
- [ ] **Step 2:** Verify no `scanDelete`, `evictPrefixKey`, or application Redis `SCAN` reference remains.
- [ ] **Step 3:** Run focused verification and commit.
