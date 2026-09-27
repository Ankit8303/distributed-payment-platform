# ADR-010: Redis Auxiliary Infrastructure Architecture

## Status
Accepted

## Context
As the Distributed Payment & Ledger Platform scaled through Phase 9 (Transactional Outbox), transaction volumes increased, necessitating auxiliary infrastructure for:
1. High-frequency non-authoritative read caching (e.g. account profile lookups).
2. Distributed rate limiting for public-facing endpoints (e.g. login and registration) to defend against credential stuffing and brute-force abuse.
3. Ephemeral short-lived state management with deterministic TTL boundaries.

However, incorporating an in-memory distributed key-value store introduces severe architectural hazards if boundaries are not strictly defined. Specifically, treating in-memory storage as financial truth, write-through balance caches, or primary idempotency locks risks catastrophic ledger corruption, balance desynchronization, and lost updates during network partitions or crashes.

## Decisions

### 1. Why Redis?
Redis is selected because:
- Sub-millisecond read/write latency for high-frequency auxiliary workloads.
- Single-threaded execution model enabling atomic Lua script execution for rate limiting.
- Native key expiration (TTL) mechanisms for automatic lifecycle cleanup.
- Supported directly by Spring Data Redis (`LettuceConnectionFactory`) without introducing third-party framework sprawl.

### 2. Why is Redis Strictly Auxiliary?
Redis is classified as **disposable auxiliary infrastructure**:
- Redis possesses no durability guarantees comparable to PostgreSQL write-ahead logging (WAL) with synchronous commits.
- Redis memory is volatile; evictions, failovers, or node reboots can lose unpersisted keys.
- Therefore, Redis is forbidden from participating in the financial commit path. If Redis is completely unavailable, the platform must continue operating with 100% financial correctness.

### 3. Why is PostgreSQL Still the Sole Financial Authority?
PostgreSQL provides ACID transactions with strict serializability / row-level pessimistic locking (`SELECT ... FOR UPDATE`), durable disk writes, and relational referential integrity. In double-entry bookkeeping, an account balance is an aggregation of immutable ledger entries (`ledger_entries`). PostgreSQL ensures that no debit can occur without a corresponding credit and that balances never diverge.

### 4. Why is Redis NOT Used for Balances?
A write-through or cache-first pattern:
```text
Payment Request → Redis Balance → PostgreSQL Ledger (WRONG)
```
violates financial correctness:
- A crash between Redis mutation and PostgreSQL commit results in phantom funds or lost money.
- Concurrent updates across distributed nodes could corrupt balance totals.
- Therefore, all debit decisions, authorization checks, and settlements execute strictly against PostgreSQL using `SELECT ... FOR UPDATE` row locks and authoritative double-entry ledger calculations.

### 5. Why is Redis NOT Authoritative for Idempotency?
Phase 7 established durable idempotency at the database boundary (`idempotency_records` table with unique constraint on `(actor_id, operation, idempotency_key)`).
- If Redis was authoritative for idempotency, a Redis restart or key eviction could allow duplicate payments to execute.
- Database unique constraints are durable, ACID-compliant, and survive system crashes. Redis may optionally accelerate auxiliary deduplication, but PostgreSQL remains the sole authoritative idempotency barrier.

### 6. Why is Redis NOT the Transactional Outbox?
The Transactional Outbox pattern implemented in Phase 9 requires atomic state-to-event consistency:
```sql
BEGIN;
  INSERT INTO payments ...;
  INSERT INTO ledger_entries ...;
  INSERT INTO outbox_events ...;
COMMIT;
```
Redis cannot participate in PostgreSQL local ACID transactions. Attempting to write outbox events to a Redis queue during a database transaction reintroduces the dual-write failure problem (PostgreSQL commits, Redis write fails). The outbox remains durably inside PostgreSQL `outbox_events`.

### 7. Why Cache-Aside?
Cache-aside (lazy loading) was chosen for account reads:
```text
Client GET /api/v1/accounts/{id}
   │
   ├── Cache HIT  → return cached DTO
   │
   └── Cache MISS → query PostgreSQL
                       ↓
                    populate Redis with TTL
                       ↓
                    return entity
```
- Only requested accounts consume memory.
- If Redis fails, the application falls back immediately to PostgreSQL with zero data loss.
- PostgreSQL state is always authoritative; the cache is disposable.

### 8. How is Cache Invalidation Handled?
- Cache entries have a deterministic 300-second TTL (`app.cache.account.ttl-seconds`).
- Authoritative mutations (`freezeAccount`, `unfreezeAccount`) execute cache eviction (`accountReadCacheService.evict(accountId)`) immediately after the database commit.
- Eviction failures (e.g. if Redis is unreachable) are logged as warnings and do NOT roll back the database transaction. When Redis recovers, the TTL naturally purges stale data, and subsequent reads fall back to PostgreSQL.

### 9. What Happens During a Redis Outage?
- **Financial Settlement**: 100% operational. Zero Redis calls exist in `LedgerService.settlePaymentWithLedger`.
- **Ledger Operations**: 100% operational. Double-entry entries post directly to PostgreSQL.
- **Transactional Outbox**: 100% operational. The outbox relay reads PostgreSQL and publishes to Kafka.
- **Account Reads**: Seamless fallback. The cache GET catches connection exceptions, increments `redis.cache.errors`, and loads the account from PostgreSQL.
- **Rate Limiting**: Enforces the configured degradation policy (`FAIL_OPEN` or `FAIL_CLOSED`).

### 10. Which Workloads Fail Open? Which Fail Closed?
- **Standard Read Endpoints / APIs (`FAIL_OPEN`)**: Availability is prioritized over strict throttling. If Redis is down, legitimate traffic is permitted.
- **Security-Critical Endpoints (`FAIL_CLOSED`)**: When protecting against credential stuffing or distributed brute-force attacks, security takes precedence over availability. If configured to fail closed, requests exceeding capacity or during partition are blocked until telemetry confirms infrastructure recovery.

### 11. How are TTLs Chosen?
- `cache:account:v1:{id}`: 300 seconds (5 minutes). Balances read caching performance against potential divergence window.
- `rate-limit:auth:v1:{actor}`: 60 seconds (1 minute). Standard sliding/fixed authentication throttling window.
- Absolute TTL rule: Every Redis key MUST have an intentional, bounded TTL. No permanent keys are permitted.

### 12. How is Concurrent Rate Limiting Made Atomic?
Rate limiting uses an atomic Lua script:
```lua
local current = redis.call('INCR', KEYS[1])
if current == 1 then
    redis.call('EXPIRE', KEYS[1], ARGV[2])
end
local ttl = redis.call('TTL', KEYS[1])
local allowed = 1
if current > tonumber(ARGV[1]) then
    allowed = 0
end
return {allowed, current, ttl}
```
Because Redis executes Lua scripts as a single atomic unit, the increment, expiration, and threshold check occur without interleaving. Concurrent requests cannot bypass the limit under any thread volume.

### 13. How Does the Design Behave Across Multiple Application Instances?
- All application instances share the centralized Redis cluster.
- Rate limiting counters and cache entries are globally coordinated without application-level distributed locks.
- If Redis fails, all instances independently and gracefully degrade to PostgreSQL per their documented policies.

## Consequences
- **Positive**: Drastically reduced read pressure on PostgreSQL; robust protection against brute-force authentication attacks; zero risk of financial ledger corruption during Redis failures.
- **Trade-offs**: Read cache may be temporarily stale up to TTL in the event of an invalidation delivery failure; rate limiting requires network hop to Redis.
