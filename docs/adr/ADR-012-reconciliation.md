# ADR-012: Reconciliation & Financial Consistency Engine Architecture

## Status
Accepted

## Context
In the Distributed Payment & Ledger Platform, payment capture, refunds, and payouts involve two fundamentally disparate systems:
1. **External Payment Providers / Gateways**: Remote third-party APIs accessed over unreliable networks (HTTP/TLS).
2. **Local PostgreSQL Database**: The authoritative, ACID-compliant persistence store housing the immutable double-entry ledger, account balances, and transactional outbox.

Because two-phase commit (2PC) is impossible across external payment gateways, atomic execution across the provider network call and the local database transaction cannot be guaranteed:
- A gateway network call may succeed or time out, but the subsequent local database transaction may fail (e.g. database failover, connection drop, constraint error).
- Conversely, a local database failure might occur while external funds have already left or entered customer accounts.

When this occurs, local operations enter `PENDING_RECONCILIATION`. Without an automated, robust reconciliation engine:
- Transactions remain permanently indeterminate.
- Balances desynchronize between external banking rails and the internal ledger.
- Manual intervention risks ad-hoc SQL modifications that corrupt historical accounting history.

---

## Decisions

### 1. Why Reconciliation is Necessary
Reconciliation is the asynchronous consistency engine that brings the local system of record and external provider states into alignment. It identifies uncertain operations, queries external provider reality outside database transactions, and commits compensating financial resolutions deterministically.

### 2. Why Provider Calls and PostgreSQL Transactions Cannot Be Combined
Executing external HTTP calls inside an open PostgreSQL transaction is an anti-pattern that causes catastrophic connection pool exhaustion and database lock starvation. If a provider call hangs for 15–30 seconds under an active database transaction, database connections and row locks are held hostage.
Therefore:
```text
Provider Status Query (Outside DB Transaction)
        ↓
BEGIN Local PostgreSQL Transaction
        ↓
Acquire Pessimistic Locks (SELECT ... FOR UPDATE)
        ↓
Re-verify Authoritative Local State
        ↓
Post Double-Entry Ledger Transaction & Update Operation State
        ↓
Persist Transactional Outbox Event
        ↓
COMMIT
```

### 3. PostgreSQL Remains the Sole Financial Authority
The external provider is NOT authoritative over the internal ledger. A provider response is raw external evidence. The local reconciliation engine verifies whether the local operation is eligible, re-checks account status and balance constraints, and executes the appropriate double-entry accounting entries. Provider states are never blindly copied over local business rules.

### 4. Absolute Prohibition Against Modifying Historical Ledger Rows
Posted ledger records in `ledger_transactions` and `ledger_entries` are immutable append-only facts. Reconciliation MUST NEVER issue `UPDATE` or `DELETE` statements on historical entries.
If money must be credited, debited, or reversed:
- A brand new, balanced `LedgerTransactionEntity` is created ($\sum \text{DEBIT} = \sum \text{CREDIT}$).
- Historical transactions remain permanently intact for complete financial auditability.

### 5. Idempotent Resolution & Duplicate Guarding
When reconciling an operation:
- The worker locks the business entity (`PaymentEntity`, `RefundEntity`, `PayoutEntity`) using `findByIdForUpdate`.
- If the operation has already reached a terminal state (`SETTLED`, `FAILED`, `DECLINED`), reconciliation marks the case `RESOLVED` with classification `ALREADY_RESOLVED` and halts immediately.
- No duplicate ledger entries and no duplicate outbox events are ever created.

### 6. Concurrency Control & Worker Leases
Multiple reconciliation worker nodes can run concurrently without race conditions:
- **Case Claiming**: Workers claim batches using PostgreSQL row-level locks:
  ```sql
  SELECT * FROM reconciliation_cases
  WHERE (reconciliation_status IN ('OPEN', 'RETRY_REQUIRED') AND next_attempt_at <= :now)
     OR (reconciliation_status = 'IN_PROGRESS' AND lease_expires_at < :now)
  ORDER BY next_attempt_at ASC LIMIT :limit FOR UPDATE SKIP LOCKED
  ```
- **Lease Durations**: Claimed cases are leased to the claiming worker with a deterministic expiration (`lease_expires_at = now + 60s`).

### 7. Recovery from Worker Crashes
If a worker crashes while processing a case, the lease expires automatically after 60 seconds (`lease_expires_at < now`). The next polling cycle re-claims the stranded case and continues execution safely.

### 8. Handling Indeterminate / Unknown Provider States
When an external provider returns `UNKNOWN` or suffers a network timeout:
- The reconciliation engine does NOT assume success or failure.
- No ledger entries are created.
- The case transitions to `RETRY_REQUIRED` with exponential backoff ($1\text{s}, 2\text{s}, 4\text{s}, 8\text{s}, 16\text{s}, 32\text{s}, 60\text{s}$).
- Upon exceeding `maxAttempts` (5), the case transitions to `MANUAL_REVIEW`, flagging the case for human operator investigation through controlled operational runbooks.

### 9. Outbox Atomicity & Infrastructure Resilience
- **Outbox Atomicity**: Settle operation + post compensating ledger entries + persist outbox event commit in the same local ACID transaction.
- **Kafka Resilience**: If Kafka is partitioned or offline, reconciliation commits safely to PostgreSQL. The transactional outbox relays events once Kafka recovers.
- **Redis Resilience**: Redis is purely auxiliary. Complete Redis outages have zero effect on reconciliation correctness.

---

## Consequences

### Positive
- **Guaranteed Consistency**: Dual-write and timeout discrepancies between external gateways and the local database are automatically and safely resolved.
- **Audit Compliance**: All corrections are documented by immutable compensating ledger entries and structured attempt logs in `reconciliation_attempts`.
- **Zero Stranded Operations**: In-flight timeouts eventually reach deterministic terminal states (`RESOLVED` or `MANUAL_REVIEW`).
- **Deadlock & Race Immunity**: PostgreSQL `FOR UPDATE SKIP LOCKED` and deterministic UUID locking guarantee zero deadlocks and zero duplicate resolutions.

### Negative / Trade-offs
- **Polling Overhead**: Requires periodic database queries for candidate discovery and lease claims. (Mitigated by database partial indexes on `reconciliation_status` and `next_attempt_at`).
- **Eventual Consistency**: Operations that experience network timeouts resolve asynchronously on subsequent reconciliation cycles rather than synchronously in-band.
