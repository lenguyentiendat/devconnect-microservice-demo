# Kafka Read-Model Replay Design

## Goal

Rebuild the in-memory read models owned by `search-service` and `notification-service` whenever either process starts, so committed Kafka offsets do not leave those services empty after a restart.

The design keeps Kafka as the source for rebuilding both demo read models. It does not introduce Elasticsearch, another database, or direct access to Feed Service Cassandra data.

## Root cause

Both consumer services store derived state only in `ConcurrentHashMap` instances:

- `search-service` stores posts by `postId`.
- `notification-service` stores notifications by `notificationId` and processed event IDs by `eventId`.

Kafka consumer offsets survive application container restarts, but these maps do not. Runtime evidence showed both consumer groups resuming `post-events-0` from committed offset `2`, leaving events at offsets `0` and `1` absent from the fresh maps. `auto-offset-reset=earliest` did not help because it only applies when no committed offset exists.

## Selected approach

Each `PostCreatedEventListener` will participate in Spring Kafka partition-assignment callbacks. On the first non-empty assignment in a process, it will seek every assigned partition to the beginning. A process-local guard will ensure normal later rebalances do not trigger another full replay.

The existing consumer group IDs remain unchanged:

- `search-service-group`
- `notification-service-group`

This approach was selected over generating a new consumer group per startup or resetting offsets with an external Docker command. It keeps the behavior inside each service, avoids abandoned consumer groups, and does not add an operational race between offset reset and listener startup.

## Data flow

On each Search or Notification process startup:

1. Spring Kafka assigns one or more `post-events` partitions to the listener.
2. The listener checks its process-local initial-replay guard.
3. On the first non-empty assignment, the listener requests `seekToBeginning` for all assigned partitions and closes the guard.
4. Kafka delivers retained historical `POST_CREATED` events in partition order.
5. The service rebuilds its in-memory read model.
6. After reaching the current end offsets, the same listener continues consuming new events normally.

No REST contract, Kafka topic, event schema, Cassandra table, or Docker topology changes.

## Search behavior

`PostSearchService.indexPost` remains an upsert keyed by `postId`. Replaying an event therefore replaces the same map entry instead of creating a duplicate. Case-insensitive substring search remains unchanged.

The replay concern stays at the Kafka adapter boundary. A later Elasticsearch implementation can replace the in-memory indexing/search internals while retaining the `POST_CREATED` contract and listener flow.

## Notification behavior

`NotificationService` continues to deduplicate events within the current process using `processedEventIds`. A retained event creates at most one notification during a replay, and later delivery of the same `eventId` is ignored.

The current transient notification semantics remain unchanged:

- `notificationId` is generated again during every process rebuild.
- `createdAt` records the rebuild processing time rather than the original event time.
- Notifications and deduplication state are still lost when the process stops and are reconstructed from retained Kafka events on the next startup.

There is no API for notification acknowledgement or lookup by notification ID, so stable IDs and persistent notification state remain outside this change.

## Error and consistency behavior

- A non-`POST_CREATED` event remains ignored.
- Duplicate Search events remain safe because indexing is an upsert by `postId`.
- Duplicate Notification events remain safe within the process because deduplication uses `eventId`.
- If Kafka is temporarily unavailable, the existing Spring Kafka connection/retry behavior remains responsible for recovery.
- Search and Notification APIs may return empty or partial results while an initial replay is still catching up.
- Only events still available under Kafka retention can be rebuilt.
- The repository's single-instance-per-service constraint remains. A consumer group split across multiple instances would leave each instance with only the partitions assigned to its local in-memory map.

## Testing strategy

Implementation will be test-first in both consumer modules.

### Search Service

1. Verify the first non-empty partition assignment seeks all assigned partitions to the beginning.
2. Verify a later assignment in the same process does not replay again.
3. Verify `POST_CREATED` indexes a post and other event types are ignored.
4. Verify duplicate `postId` replay remains one upserted result.
5. Verify case-insensitive content search remains unchanged.

### Notification Service

1. Verify the first non-empty partition assignment seeks all assigned partitions to the beginning.
2. Verify a later assignment in the same process does not replay again.
3. Verify `POST_CREATED` creates one notification and other event types are ignored.
4. Verify duplicate `eventId` delivery creates no duplicate notification.
5. Verify notifications are filtered by user as before.

### Repository and runtime verification

1. Run each changed module's tests.
2. Run the full root Maven test suite.
3. Rebuild and recreate `search-service` and `notification-service` without deleting Kafka data.
4. Confirm startup logs report initial partition replay.
5. Confirm Search finds the retained posts containing `Cassandra` and `Java`.
6. Confirm Notification lists rebuilt notifications for the authors of retained events.
7. Create a new post after replay and confirm both consumers process the live event.

## Documentation impact

Update the current documentation that describes read-model lifecycle, Kafka consumption, restart behavior, local verification, and known limitations. At minimum this includes `README.md`, `docs/ARCHITECTURE.md`, `docs/DATABASE.md`, `docs/EVENTS.md`, and `docs/DEVELOPMENT.md`. The REST API contract does not change.

## Out of scope

- Elasticsearch or another persistent Search index
- Persistent Notification storage or acknowledgement state
- Stable notification IDs across process restarts
- Changing notification timestamps to the event occurrence time
- Infinite Kafka retention or recovery after retained events expire
- Direct Search/Notification access to Cassandra
- New REST endpoints or Kafka event fields
- Multi-instance in-memory read-model correctness
