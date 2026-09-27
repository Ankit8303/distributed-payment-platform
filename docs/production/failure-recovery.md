# Production Failure Recovery and Resilience Runbook

**Document Version**: 1.0  
**Phase**: Phase 17 — Production Hardening  

---

## 1. Overview & Failure Invariants

The platform is designed around deterministic recovery under infrastructure disruption. In all failure modes:
1. **PostgreSQL is the Sole Financial Source of Truth**: State transitions in PostgreSQL determine financial status.
2. **Double-Entry Balance is Invariant**: `SUM(debit) == SUM(credit)` for every posted ledger entry.
3. **Auxiliary Degradation is Non-Authoritative**: Outages in Redis or Kafka never cause incorrect balances or lost financial records.

---

## 2. Failure Scenarios & Recovery Matrix

### 2.1 Payment Provider Timeout / Network Severance
- **Symptom**: External payment gateway authorization or capture fails to respond within timeout window.
- **Application State**:
  - Payment enters `PENDING_RECONCILIATION`.
  - HTTP endpoint returns `202 Accepted` with correlation ID.
  - Zero ledger entries are posted prematurely.
- **Recovery Procedure**:
  - Background `ReconciliationWorker` polls pending payments.
  - Queries provider status endpoint via `queryOperationStatus(operationId, providerReference)`.
  - If provider succeeded: triggers `settlePaymentWithLedger(...)` atomically.
  - If provider declined or not found: marks payment `FAILED` with no ledger mutation.

### 2.2 Kafka Broker Unavailability / Partition Split
- **Symptom**: Kafka cluster is unreachable, broker rebalancing, or disk full on brokers.
- **Application State**:
  - Business operations (payment authorization, capture, ledger posting, refunds) continue normally!
  - Events are saved to `outbox_events` table in PostgreSQL in the SAME transaction as the ledger entries.
  - Outbox relay logs warnings and backs off without dropping events.
- **Recovery Procedure**:
  - Once Kafka brokers recover, `OutboxRelayScheduler` resumes polling `outbox_events`.
  - Retries up to `max-retries = 5` per event with exponential backoff.
  - Exactly-once consumer deduplication (`ConsumerDeduplicationService`) prevents duplicate downstream processing.

### 2.3 Redis Outage / Cache Invalidation
- **Symptom**: Redis instance unreachable, out-of-memory eviction, or network timeout.
- **Application State**:
  - Rate limiting policy `FAIL_OPEN` permits traffic safely without blocking customer payments.
  - `AccountReadCacheService` catches Redis connection exceptions and directly falls back to PostgreSQL `accounts` table.
- **Recovery Procedure**:
  - Redis recovers. Cache repopulates on subsequent read misses. No manual financial reconciliation is required.

### 2.4 Database Outage / Failover
- **Symptom**: Primary PostgreSQL node fails; replica promoted.
- **Application State**:
  - Inflight transactions rollback cleanly.
  - HikariCP connection pool attempts reconnection every `connection-timeout: 30s`.
  - Container readiness probe (`/actuator/health/readiness`) transitions to `DOWN`, removing instance from upstream ingress.
- **Recovery Procedure**:
  - Once replica promotion completes and DNS resolves to new primary, HikariCP re-establishes pool.
  - Readiness probe transitions to `UP`.
  - Outbox relay reclaims any stale leases (`lease_seconds = 30`) and resumes publishing.

### 2.5 Outbox Relay Worker Crash (Stale Lease Recovery)
- **Symptom**: Application node crashes mid-batch after claiming outbox rows.
- **Application State**:
  - Claimed outbox rows remain with `status = PROCESSING` and `lease_expires_at = timestamp`.
- **Recovery Procedure**:
  - After 30 seconds (`lease_seconds`), `lease_expires_at` lapses.
  - Surviving worker nodes select stale events where `status = PROCESSING AND lease_expires_at < NOW()`.
  - Events are reclaimed and published to Kafka. Zero event loss.
