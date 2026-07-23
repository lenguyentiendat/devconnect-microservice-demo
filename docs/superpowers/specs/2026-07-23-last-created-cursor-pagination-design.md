# Last-Created Cursor Pagination Design

## Goal

Replace Feed Service's opaque Cassandra paging-state token with a client-visible cursor
made of lastCreatedAt and lastPostId. Remove every page-token dependency while
preserving Caffeine L1, Redis L2, revisioned page keys, Pub/Sub invalidation and
fail-open reads from Cassandra.

## Public contract

GET /api/feed/posts accepts:

- pageSize, default 20 and maximum 100;
- no cursor fields for the first page;
- both lastCreatedAt (ISO-8601 LocalDateTime) and lastPostId (UUID) for every
  later page.

pageNum and pageToken are removed. A request containing only one cursor field,
an invalid ISO timestamp or an invalid UUID returns the existing 400
business-error envelope.

The response keeps items, pageSize, hasNext and feedRevision; it replaces pageNum
and nextPageToken with nullable nextLastCreatedAt and nextLastPostId. When hasNext
is true, these fields identify the final returned item; otherwise both are null.

## Cassandra read design

posts_by_feed is ordered by (created_at DESC, post_id ASC). The store requests
pageSize + 1 rows and uses the extra row only to compute hasNext.

- First page: query the global feed with LIMIT pageSize + 1.
- Later page: first read rows with the same created_at and post_id > lastPostId,
  then, if capacity remains, read rows with created_at < lastCreatedAt.
- Merge the two ordered result sets, retain at most pageSize + 1, and map to
  PostResponse.

This explicitly handles multiple posts created in the same millisecond without
relying on a driver PagingState or a mixed-direction tuple comparison.

## Cache design

CacheKeyFactory.feedPage receives the revision, page size, lastCreatedAt and
lastPostId. It uses cursor:first when both cursor values are absent; otherwise
it hashes the UTF-8 string lastCreatedAt|lastPostId with the existing SHA-256
truncation. The cache key stays revisioned, so a post write still makes old page
entries unreachable after revision advance.

## Removals and documentation

Delete PageTokenCodec, its tests, CACHE_PAGE_TOKEN_SECRET, the page-token-secret
property and all documentation/API/OpenAPI references to page tokens. Update
controller, service, store, DTOs, cache tests, controller tests, Cassandra tests,
integration test configuration and Redis cache guide to use the two-field cursor.

## Acceptance criteria

- A first-page request has no cursor fields; later requests supply both.
- Partial, malformed or invalid cursors produce HTTP 400.
- The store never receives or emits a Cassandra PagingState.
- A page cache key has revision, page size and either cursor:first or the hash
  of lastCreatedAt|lastPostId.
- Existing cache-tier, invalidation, revision and Redis-failure fallback tests
  remain covered.
