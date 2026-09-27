# ADR-007: Idempotency Recovery and Concurrency Hardening

## Status
Accepted

## Context
Phase 7 audits the platform's idempotency and concurrency architecture against real-world distributed failure scenarios:
1. Application or container crashes occurring after external payment capture and internal database settlement, leaving idempotency records trapped in `IN_PROGRESS`.
2. Concurrent duplicate requests competing at high concurrency (20+ threads).
3. Concurrent opposing transfers (`A -> B` and `B -> A`) creating potential database deadlock hazards on account row locks.
4. Database-level idempotency uniqueness enforcement on the primary `payments` table to guarantee that race conditions can never produce duplicate payment entities even if application checks fail or idempotency records are archived.
5. Inaccurate HTTP status code caching in idempotency records (e.g. recording 201 for asynchronous `PENDING_RECONCILIATION` instead of 202).

## Decisions

### 1. Database-Enforced Idempotency Uniqueness on `payments` Table
Added migration `V6__phase7_idempotency_concurrency_hardening.sql`:
```sql
CREATE UNIQUE INDEX uq_payments_scope_key ON payments(idempotency_scope, idempotency_key);
```
While `idempotency_records` provides the primary idempotency consistency gate (`uq_idempotency_scope`), the `uq_payments_scope_key` index provides a second, irrevocable database invariant ensuring that two payment rows with the same actor/operation scope and idempotency key cannot physically coexist in PostgreSQL under any concurrency condition.

### 2. Frozen Concurrent Idempotency Semantics & Complete Crash-Recovery Matrix
- **Concurrent Same-Key Requests**: When $N$ simultaneous requests arrive with the same actor, operation, idempotency key, and payload, exactly 1 request acquires the lock and returns `201 Created` (or `202 Accepted`); all $N - 1$ competing requests return `409 Conflict` (`IDEMPOTENCY_CONCURRENT_REQUEST`). Replay of the completed response only occurs on subsequent requests once the state is `COMPLETED`.
- **In-Progress Crash Recovery**: If an application process crashes while an idempotency record is `IN_PROGRESS`, subsequent retries with the same key and payload behave deterministically according to the state of the existing `PaymentEntity`:
  - `SETTLED`: Reconstructs response, marks idempotency `COMPLETED`, returns `201 Created`, zero duplicate provider/ledger effects.
  - `PENDING_RECONCILIATION`: Reconstructs response, marks idempotency `COMPLETED`, returns `202 Accepted`, manual reconciliation required.
  - `DECLINED`: Replays provider failure deterministically, marks idempotency `FAILED`, returns `400 Bad Request`.
  - `FAILED`: Replays failure deterministically, marks idempotency `FAILED`, returns `400 Bad Request`.
  - `CREATED`, `AUTHORIZING`, `AUTHORIZED`, `CAPTURING`: If within active processing window (<10s), rejected with `409 IDEMPOTENCY_CONCURRENT_REQUEST` to protect active thread. If orphaned (>10s), ambiguous state cannot be safely guessed; external provider is never re-invoked; payment transitions safely to `PENDING_RECONCILIATION`, idempotency marks `COMPLETED` with 202, and returns `202 Accepted` with poll URL.

### 3. Preserving Accurate HTTP Status Codes in Idempotency Records
When completing an idempotency record for payments that resolve to `PENDING_RECONCILIATION` (such as provider timeouts or post-capture database errors), the record persists HTTP status code `202 ACCEPTED` (instead of a blanket `201 CREATED`).

### 4. Deterministic Account Lock Ordering (Deadlock Immunity)
All operations that lock multiple accounts (such as `LedgerService.settlePaymentWithLedger()`) strictly sort account IDs using `UUID.compareTo()` before invoking `AccountRepository.findByIdForUpdate()`. Under concurrent opposing transfers (`Account A -> B` and `Account B -> A`), all database transactions acquire locks in the exact same global order, mathematically eliminating ABBA deadlocks.

### 5. Redis and Kafka Transport Independence
PostgreSQL remains the sole authoritative store of financial truth and idempotency state. Caches (Redis) and event streaming (Kafka) are strictly auxiliary. An outage or absence of Redis or Kafka does not compromise financial transactions, double-entry ledger balance calculations, or database uniqueness guarantees.

## Consequences

- **Positive**:
  - Resilient to container crashes and redeployments during payment settlement.
  - Zero lost updates, zero double spends, and zero database deadlocks under high concurrency.
  - Double-layer database enforcement against duplicate payment creation.
  - Complete compliance with Phase 0 financial invariants.
- **Negative / Trade-offs**:
  - Additional query on `PaymentRepository` during retry of an `IN_PROGRESS` record.
