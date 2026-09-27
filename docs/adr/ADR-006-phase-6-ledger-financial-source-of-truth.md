# ADR-006: Phase 6 Ledger as Financial Source of Truth and Transaction Boundaries

## Status
Accepted

## Context
Phase 6 introduces the authoritative Double-Entry Ledger. We must define the boundaries between the internal double-entry ledger, the materialized account balances, the payment processing engine, and the external payment providers. Specifically, we must resolve how to guarantee atomic correctness when integrating an external network call (`provider.capture()`) with a strict internal DB transaction (`LedgerService.settlePaymentWithLedger()`).

A distributed transaction (two-phase commit) between the external payment provider and the PostgreSQL database is deliberately NOT attempted, because:
1. External payment gateways do not support XA/2PC protocols.
2. Holding a database transaction open during a network call introduces unbounded latency and connection pool starvation risk.
3. The provider side-effect (captured funds) cannot be rolled back by PostgreSQL under any circumstances.

Instead, the architecture explicitly separates the external side-effect from the database transaction and handles the resulting edge cases through a deterministic state machine and reconciliation path.

## Decisions

### 1. Ledger as Financial Source of Truth
The `ledger_entries` table is the definitive financial history. The system continuously enforces `SUM(DEBIT) == SUM(CREDIT)` for every transaction. `AccountEntity.materialized_balance_minor` is strictly a derived optimization. All financial validation during a transaction (e.g. `INSUFFICIENT_FUNDS`) calculates the authoritative balance from the `ledger_entries` table.

### 2. Immutable Ledger Entries
Database-level PostgreSQL triggers (`trg_immutable_ledger_entries`) intercept and block all `UPDATE` and `DELETE` queries on the `ledger_entries` table, forcing all financial changes to occur via compensating transactions rather than destructive mutations.

### 3. Materialized Balance Semantics
The `materialized_balance_minor` is updated synchronously at the exact time the `LedgerTransaction` is posted within the same database transaction. The integrity of `materialized_balance_minor` can always be verified and rebuilt from the sum of the associated `ledger_entries`.

### 4. Deterministic Lock Ordering
To prevent database deadlocks when executing transactions involving multiple accounts, the `AccountRepository.findByIdForUpdate` lock acquisitions are strictly ordered by comparing the raw `UUID`s of the payer and payee accounts. This ensures that concurrent threads always acquire locks in the same hierarchical order.

### 5. External Provider Isolation — Provider Call Outside DB Transaction
The `@Transactional` annotation was intentionally removed from the payment workflow orchestrator (`PaymentService.createPayment()`). The external `provider.capture()` executes completely outside any database transaction boundary.

```
provider.capture()             ← external side-effect, NO DB transaction
        ↓
external result known
        ↓
LedgerService.settlePaymentWithLedger()   ← @Transactional (single atomic DB TX)
```

### 6. Unified Financial Transaction — Payment SETTLED + Ledger POSTED in ONE Transaction
After the external provider capture succeeds, `LedgerService.settlePaymentWithLedger()` executes a single `@Transactional` method that atomically:

1. Re-loads and locks accounts (pessimistic write, deterministic ordering)
2. Calculates the authoritative ledger-derived balance
3. Validates sufficient funds
4. Prevents duplicate ledger posting
5. Creates the `LedgerTransaction` and balanced `LedgerEntries` (debit + credit)
6. Updates `materialized_balance_minor` on both accounts
7. Transitions the `PaymentEntity` from `CAPTURING` → `SETTLED`
8. Saves the payment entity

All of the above commit or roll back together as one atomic database operation. There is NO window where:
- The ledger is POSTED but the payment is not SETTLED
- The payment is SETTLED but the ledger is not POSTED
- Materialized balances are updated but ledger entries don't exist

### 7. Provider SUCCESS + Database FAILURE → PENDING_RECONCILIATION
If `LedgerService.settlePaymentWithLedger()` throws any exception (constraint violation, insufficient funds, etc.) after the provider has already captured funds:

1. The entire database transaction rolls back automatically
2. No ledger entries are persisted
3. No materialized balance changes are persisted
4. The payment remains in `CAPTURING` state in the database
5. `PaymentService` catches the exception, re-loads the payment from the database, and transitions it to `PENDING_RECONCILIATION` via a separate recovery save

This correctly parks the discrepancy (provider CAPTURED but ledger NOT POSTED) for administrative intervention (e.g., executing an external refund) rather than ignoring the failure or incorrectly marking the internal transaction as a success.

### 8. Duplicate Posting Protection
Ledger transactions enforce strict idempotency via a `UNIQUE` index constraint on `(source_reference_type, source_reference_id)` in the `ledger_transactions` table. This prevents concurrent threads from ever posting double ledgers for the same payment settlement event, regardless of application-level race conditions.

### 9. Concurrent Overspending Prevention
All payment settlements undergo an optimistic check before external capture, followed by an authoritative check inside the unified `LedgerService` transaction. Concurrent overspending attempts that successfully bypass the optimistic check will inevitably serialize at the `findByIdForUpdate` barrier. The losing transaction discovers the depleted authoritative balance, the entire DB transaction rolls back, and the payment is safely parked in `PENDING_RECONCILIATION` for manual offset against the captured provider funds.

## Consequences
- **Positive:** Financial integrity is mathematically and physically sealed by database constraints. Payment SETTLED and Ledger POSTED are guaranteed to be atomic. Database transactions remain fast and untethered from external network latency.
- **Negative:** Increased complexity in handling the `PENDING_RECONCILIATION` state. Rare occurrences of valid provider captures but internal DB failures require manual/scheduled resolution paths (Phase 7+).
