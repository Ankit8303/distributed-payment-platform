# ADR-011: Post-Settlement Financial Operations: Refunds, Reversals, Payouts & Controlled Financial Adjustments

## Status
Accepted

## Context
In Phases 0 through 10, the platform successfully established:
- An immutable double-entry bookkeeping ledger (`ledger_transactions`, `ledger_entries`).
- Durable idempotency at the database boundary (`idempotency_records`).
- External payment gateway separation with atomic ledger settlement (`LedgerService.settlePaymentWithLedger`).
- Transactional outbox event publishing to Kafka (`outbox_events`).
- Redis auxiliary caching and rate limiting.

However, once payments reach `SETTLED`, real-world financial commerce requires controlled compensating and disbursement operations:
1. **Refunds**: Returning funds to payers (full or partial) due to order cancellations, returns, or customer service issues.
2. **Reversals**: Complete cancellation of settled payments due to fraud, chargebacks, or processing anomalies.
3. **Payouts**: Outward disbursement of cleared merchant funds to external bank accounts.
4. **Controlled Administrative Financial Adjustments**: Audited, balanced ledger adjustments to resolve reconciliation discrepancies.

Implementing these operations poses serious architectural hazards if done naively:
- Modifying existing `payments` or `ledger_entries` rows would destroy audit trails and violate immutable double-entry bookkeeping.
- Arbitrary balance modifications without balanced ledger entries would corrupt account integrity.
- Concurrent opposing adjustments could result in database deadlocks.
- Dual-write problems between database mutations and notification events could cause downstream desynchronization.

---

## Decisions

### 1. Absolute Preservation of Double-Entry Ledger Immutability
**Decision**: Posted ledger transactions and entries are strictly immutable.
- No `UPDATE` or `DELETE` statements are ever executed against `ledger_transactions` or `ledger_entries`.
- Every refund, reversal, payout, and administrative adjustment creates a brand new, balanced `LedgerTransactionEntity` containing at least two `LedgerEntryEntity` records where:
  $$\sum \text{DEBIT amounts} = \sum \text{CREDIT amounts}$$
- Account `materialized_balance_minor` values remain strictly derived optimizations, updated atomically alongside immutable ledger postings.

### 2. First-Class Child Domain Entities vs Mutating Parent Payments
**Decision**: Model `RefundEntity` and `ReversalEntity` as first-class domain models with their own dedicated tables (`refunds`, `reversals`) rather than mutating the original `PaymentEntity`.
- A payment that has been settled remains `SETTLED` in `payments`.
- Refunds point to the parent payment via `payment_id` foreign key.
- Reversals point to the parent payment with a database-level `UNIQUE (payment_id)` constraint, strictly guaranteeing that a payment can be reversed at most once.
- Payments that have active or settled refunds cannot be reversed; payments that are reversed cannot be refunded.

### 3. Cumulative Partial Refund Validation
**Decision**: Enforce strict cumulative bounds across partial refunds:
$$\sum (\text{SETTLED} + \text{PROCESSING}) + \text{requested refund} \le \text{payment.amountMinor}$$
- State machine transitions (`PENDING` $\to$ `PROCESSING` $\to$ `SETTLED` / `FAILED`) guarantee that in-flight refunds reserve refundable capacity.
- Database locking on the parent payment row ensures that concurrent refund requests cannot over-refund a payment.

### 4. Authoritative Ledger Verification for Payouts
**Decision**: Payout requests verify debtor account eligibility by calculating the true ledger balance:
```java
long authoritativeBalance = ledgerEntryRepository.calculateLedgerBalanceMinor(accountId);
if (authoritativeBalance < payoutAmountMinor) {
    throw new PayoutDomainException(ErrorCode.PAYOUT_INSUFFICIENT_FUNDS);
}
```
- Compensating ledger transaction debits the merchant account and credits an internal settlement account (`SETTLEMENT-{currency}`).
- Materialized account balances are adjusted only after the double-entry transaction posts successfully.

### 5. Audited Administrative Financial Adjustments & Deadlock Elimination
**Decision**: Administrative adjustments must adhere to strict auditability and deterministic concurrency protocols:
- Mandatory fields: `source_account_id`, `target_account_id`, `amount_minor`, `currency`, `reason`, `operator_id`, `compensating_ledger_transaction_id`.
- RBAC authorization strictly requires `ROLE_ADMIN` or `ROLE_SYSTEM`.
- **Deterministic Lock Ordering**: When locking accounts in PostgreSQL to prevent deadlocks from concurrent opposing adjustments (e.g. Account A $\to$ Account B while Account B $\to$ Account A):
  ```java
  if (sourceAccountId.compareTo(targetAccountId) < 0) {
      sourceAccount = accountRepository.findByIdForUpdate(sourceAccountId).orElseThrow();
      targetAccount = accountRepository.findByIdForUpdate(targetAccountId).orElseThrow();
  } else {
      targetAccount = accountRepository.findByIdForUpdate(targetAccountId).orElseThrow();
      sourceAccount = accountRepository.findByIdForUpdate(sourceAccountId).orElseThrow();
  }
  ```
  Accounts are locked strictly by ascending UUID order across all threads.

### 6. Atomic Outbox Integration
**Decision**: Every post-settlement financial mutation publishes its corresponding domain event (`RefundSettled`, `ReversalSettled`, `PayoutSettled`, `FinancialAdjustmentPosted`) via the transactional outbox pattern inside the **same database transaction** that posts the ledger entries.
- If the database transaction rolls back, no outbox event is persisted.
- Once committed, the outbox relay guarantees at-least-once delivery to Kafka `payment.events`.

---

## Consequences

### Positive
- **Auditing Integrity**: Every cent moving in or out of the system is permanently tracked by balanced double-entry entries.
- **Deadlock Immunity**: Deterministic UUID lock ordering eliminates deadlock exceptions during high-concurrency opposing transfers.
- **Over-Refund Protection**: Concurrent refund attempts are serialized and bounded by cumulative settled/processing totals.
- **Event-State Consistency**: Transactional outbox ensures zero dual-write anomalies for post-settlement events.

### Negative / Trade-offs
- **Storage Growth**: Because rows are never updated or deleted, the `ledger_transactions` and `ledger_entries` tables grow append-only. (Addressed by database indexing and future table partitioning).
- **Latency**: Payouts and adjustments require row-level pessimistic locks (`SELECT ... FOR UPDATE`), which introduces slight lock contention under high-frequency writes to the exact same accounts.
