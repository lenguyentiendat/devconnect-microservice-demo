# No-Prefix Cache Eviction Design

## Decision

Remove prefix eviction completely from Feed caching. The cache API, Redis store,
Pub/Sub payload, listener, tests, and documentation must not expose or execute
prefix deletion or Redis `SCAN`.

## Invalidation Flow

- Post-by-ID values use exact-key eviction or replacement.
- Feed pages use a key containing the current `feedRevision`.
- After Cassandra saves a post, Feed advances the revision and publishes only the
  affected exact post key. Older page generations become unreachable and expire
  through their configured TTLs.
- No local or Redis page-prefix deletion runs on normal writes or via Pub/Sub.

## Constraints

- Redis `KEYS` and `SCAN` are not used by application cache code.
- Cache failures remain fail-open to Cassandra.
- Cassandra and Kafka behavior remain unchanged.
