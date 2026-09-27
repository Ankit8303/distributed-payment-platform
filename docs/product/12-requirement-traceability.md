# Requirement Traceability Matrix

## 1. Overview
The Requirement Traceability Matrix guarantees that every functional requirement, financial invariant, domain rule, and security constraint defined in Phase 0 is mapped directly to its architectural and testing counterparts. No requirement is left unverified.

---

## 2. Financial Invariants Traceability Matrix

| Invariant ID & Name | Business Rule | Database Enforcement | Application Layer Enforcement | Automated Test Strategy | Acceptance Criterion |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **INV-01: Double-Entry Balance** | $\sum \text{Debits} == \sum \text{Credits}$ for all posted transactions. | Constraint / Trigger on `ledger_transactions` preventing status `POSTED` if un-balanced. | `LedgerTransaction.post()` calculates sums using 64-bit integers; throws `UnbalancedTransactionException`. | Unit tests with unbalanced inputs; integration tests asserting DB rollback. | **AC-01** |
| **INV-02: Ledger Immutability** | Posted ledger entries cannot be updated or deleted. | Trigger `trg_immutable_ledger_entries` intercepts `UPDATE/DELETE` and raises SQL exception. | Entities omit setters; repository interfaces omit update/delete methods. | Integration test asserting raw SQL update throws `CANNOT_MODIFY_POSTED_LEDGER`. | **AC-02** |
| **INV-03: Refund Limitation** | $\sum \text{Refunds} \le \text{Captured Amount}$. | Relational row lock on `payments` table during refund creation. | `RefundService` validates $\sum \text{Settled} + \text{requested} \le \text{amountMinor}$. | Concurrency test with parallel threads attempting over-refunding. | **AC-04** |
| **INV-04: Durable Idempotency** | Identical key yields identical financial effect. | Unique constraint `(actor_id, operation, idempotency_key)` on `idempotency_records`. | Hash comparison: Case A returns cached result; Case B returns 409; Case C rejects in-flight. | 50 concurrent threads submitting identical request; assert 1 payment created. | **AC-03, AC-14** |
| **INV-05: Financial Atomicity** | Payment, ledger entries, and outbox event commit in 1 ACID transaction. | PostgreSQL transaction commit/rollback semantics. | Spring `@Transactional(isolation = READ_COMMITTED)` coordinates all writes. | Failure injection test right before commit; assert zero rows persisted. | **AC-01, AC-15** |
| **INV-06: Non-Authoritative Client Balances** | Client-supplied balances are untrusted inputs. | Schema contains no writable client balance field. | Balances derived directly from database ledger entries. | Penetration test submitting `balance` payload; assert server ignores it. | **AC-05** |
| **INV-07: Redis Auxiliary Boundary** | Redis failures never corrupt or drop financial data. | PostgreSQL is the sole persistent store for financial state. | Application falls back gracefully to DB if Redis is unreachable. | Chaos test: terminate Redis container; assert payments complete via DB. | **AC-01, AC-03** |
| **INV-08: Kafka Transport Boundary** | Kafka lag or rebalance never alters financial truth. | Core ledger never queries Kafka to determine balances. | Transactional outbox decouples DB commit from Kafka publish. | Chaos test: stop Kafka broker; assert DB commits and outbox queues events. | **AC-06** |
| **INV-09: Compensating Reversals** | Refunds/adjustments append opposite entries; never delete history. | Relational FK linking compensating transaction to original entity. | Reversal engine creates inverse debit/credit entries. | Full refund test; assert net balance 0 and 4 total ledger entries exist. | **AC-04, AC-17** |
| **INV-10: Currency Consistency** | All entries in a transaction must share identical currency. | Database constraint `ledger_entries.currency == ledger_transactions.currency`. | `Money` value object validates currency on arithmetic operations. | Unit test attempting cross-currency addition throws exception. | **AC-05** |
| **INV-11: Prohibition of Admin Balance Override** | No direct balance mutation API or procedure exists. | Application database role has no arbitrary balance update procedures. | Admin service only exposes account status freeze/unfreeze. | Security audit asserting no `PUT /accounts/{id}/balance` exists. | **AC-08** |
| **INV-12: Permanent Audit History** | Financial and audit records cannot be deleted to hide errors. | PostgreSQL trigger `trg_immutable_audit_logs` bars `DELETE`. | Reconciliation discrepancies marked `RESOLVED`, never deleted. | Integration test attempting SQL delete on `audit_logs` fails. | **AC-02, AC-17** |
| **INV-13: Account-Type Non-Negative Balance** | Customer accounts must remain $\ge 0$. | `CHECK (account_type != 'CUSTOMER' OR materialized_balance_minor >= 0)`. | Pessimistic lock on debtor account verifies funds before debiting. | Double-spend test attempting to spend $100 twice against $100 balance. | **AC-13** |
| **INV-14: Materialized Balance Reconcilability** | Cache balance must match sum of historical ledger entries. | Column `accounts.materialized_balance_minor` updated in same transaction. | Periodic audit calculates $\sum \text{Credits} - \sum \text{Debits}$ and asserts match. | Corruption test asserting ledger sum overrides cached value. | **AC-12** |
| **INV-15: Monotonic Ledger Sequence Numbers** | Entries per account have gapless monotonic sequence. | Unique constraint `(account_id, sequence_number)` on `ledger_entries`. | Account row lock guarantees sequential counter increment. | High-concurrency test asserting strictly monotonic numbering without gaps. | **AC-01** |

---

## 3. End-to-End Functional Capabilities Traceability

| Requirement | Use Case | Domain Object | Database Table | API Endpoint | Kafka Event | Security Control | Acceptance Test |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **User Registration & Auth** | UC-01 | `User`, `Account` | `users`, `accounts` | `POST /auth/register`, `POST /auth/login` | `AccountCreated` | BCrypt/Argon2id, JWT validation, rate limiting | AC-10 |
| **Account Lifecycle & Freeze** | UC-02 | `Account`, `AuditLog` | `accounts`, `audit_logs` | `POST /admin/accounts/{id}/freeze` | `AccountFrozen`, `AccountUnfrozen` | `ROLE_ADMIN`, mandatory audit reasoning | AC-07, AC-08 |
| **Idempotent Payment Settlement** | UC-03 | `Payment`, `LedgerTransaction`, `LedgerEntry` | `payments`, `ledger_transactions`, `ledger_entries` | `POST /payments` | `PaymentSettled` | IDOR ownership check, token bucket rate limit | AC-01, AC-03 |
| **Provider Timeout Handling** | UC-11 | `Payment` | `payments` | `POST /payments` (returns 202) | `PaymentPendingReconciliation` | Non-failure on ambiguity, provider query | AC-09, AC-11 |
| **Partial & Full Refunds** | UC-04 | `Refund`, `LedgerTransaction` | `refunds`, `ledger_transactions` | `POST /payments/{id}/refunds` | `RefundSettled` | Payee ownership check, cumulative refund cap | AC-04 |
| **Authoritative Balance Query** | UC-05 | `Account`, `LedgerEntry` | `ledger_entries` | `GET /accounts/{id}/balance` | N/A | IDOR ownership check, no client balance trust | AC-05, AC-12 |
| **Transactional Outbox Dispatch** | UC-06 | `OutboxEvent` | `outbox_events` | N/A (Background Scheduler) | All domain events | At-least-once delivery, partition ordering | AC-06 |
| **Webhook Ingestion** | UC-07 | `Payment`, `Refund` | `payments`, `refunds` | `POST /webhooks/{provider}` | N/A | HMAC-SHA256 signature, 300s replay window | AC-10 |
| **Reconciliation Execution** | UC-08 | `ReconciliationRun`, `ReconciliationDifference` | `reconciliation_runs`, `reconciliation_differences` | `POST /reconciliation/runs` | `ReconciliationCompleted` | `ROLE_ADMIN`, automated discrepancy tagging | AC-16 |
| **Discrepancy Resolution** | UC-09 | `ReconciliationDifference`, `LedgerTransaction` | `reconciliation_differences`, `ledger_transactions` | `POST /reconciliation/differences/{id}/resolve` | N/A | `ROLE_ADMIN`, compensating transaction only | AC-17 |
| **End-to-End Traceability** | UC-03, UC-06 | All Entities | All Tables (`correlation_id`) | All Endpoints (`X-Correlation-ID`) | All Events (`correlationId` in header/envelope) | MDC logging, request trace propagation | AC-19 |
