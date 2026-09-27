# Financial Invariants Specification

## 1. Principles of Financial Invariants
In a distributed financial ledger, invariants are non-negotiable mathematical and operational rules that must hold true across all states, concurrent executions, network partitions, and application failures. A failure to enforce an invariant constitutes a critical system defect.

Every invariant defined below specifies:
1. **Business Rationale**
2. **Technical Enforcement Strategy**
3. **Database Enforcement**
4. **Application Layer Enforcement**
5. **Automated Test Strategy**

---

## 2. Platform Financial Invariants

### Invariant 1: Double-Entry Balance Equation
- **Formal Definition**: For every posted ledger transaction $T$:
  $$\sum_{e \in T, \text{direction}=\text{DEBIT}} e.\text{amountMinor} == \sum_{e \in T, \text{direction}=\text{CREDIT}} e.\text{amountMinor}$$
- **Business Rationale**: Money cannot be created or destroyed out of nothing. Every debit to an account must have an exact, matching credit to a counterparty account (customer, merchant, fee, or clearing account).
- **Technical Enforcement**:
  - A transaction cannot transition to `POSTED` unless debit sum equals credit sum.
- **Database Enforcement**:
  - PostgreSQL trigger or transaction-level validation check before marking `ledger_transactions.status = 'POSTED'`.
  - Transaction-level foreign key and constraint preventing orphaned entries.
- **Application Enforcement**:
  - `LedgerTransaction.post()` calculates both sums using 64-bit integer addition. If `debitSum != creditSum`, throws `UnbalancedTransactionException` and aborts before persistence.
- **Test Strategy**:
  - Unit test trying to post an unbalanced transaction (e.g. 100 debit vs 90 credit) asserts uncatchable domain failure.
  - Concurrency test verifying batch postings maintain balanced ledger at all times.

---

### Invariant 2: Immutability of Posted Ledger Entries
- **Formal Definition**: Let $E$ be a posted `ledger_entry` or `ledger_transaction`. For any time $t > t_{\text{posted}}$:
  $$\frac{\partial E}{\partial t} = 0 \quad (\text{No updates or deletes permitted})$$
- **Business Rationale**: Accounting books must maintain a permanent, unalterable historical record of economic activity to satisfy forensic, audit, and regulatory requirements.
- **Technical Enforcement**:
  - No `UPDATE` or `DELETE` SQL queries exist in repository interfaces.
- **Database Enforcement**:
  - PostgreSQL `BEFORE UPDATE OR DELETE` trigger on `ledger_entries` and `ledger_transactions` that raises a SQL exception `CANNOT_MODIFY_POSTED_LEDGER`.
  - Database user role for runtime application can be granted `INSERT, SELECT` on ledger tables, but restricted from `UPDATE, DELETE`.
- **Application Enforcement**:
  - Entity classes omit setter methods for financial fields (`amountMinor`, `direction`, `accountId`).
- **Test Strategy**:
  - Integration test directly attempting `jdbcTemplate.update("UPDATE ledger_entries SET amount_minor = 0")` asserts fatal database trigger exception.

---

### Invariant 3: Refund Limitation (Cumulative Refund Cap)
- **Formal Definition**: For any payment $P$ with captured amount $A_P$:
  $$\sum_{R \in \text{Refunds}(P), R.\text{status} \in \{\text{SETTLED}, \text{PROCESSING}\}} R.\text{amountMinor} \le A_P$$
- **Business Rationale**: Merchants and customers cannot refund more money than was originally captured. Over-refunding causes direct operational loss and capital deficit.
- **Technical Enforcement**:
  - Pessimistic locking (`SELECT FOR UPDATE`) on the parent `Payment` row during refund creation to prevent concurrent race conditions.
- **Database Enforcement**:
  - Relational lock on `payments` table and check against sum of existing refunds.
- **Application Enforcement**:
  - `RefundService` queries sum of active refunds under row lock, verifies `requestedAmount <= (capturedAmount - alreadyRefunded)`, and throws `RefundAmountExceedsPaymentException` if violated.
- **Test Strategy**:
  - Concurrency test: 10 parallel threads each attempting a $60 refund on a $100 payment. Exactly one succeeds; nine are deterministically rejected with `422 Unprocessable Entity`.

---

### Invariant 4: Durable Idempotency Determinism
- **Formal Definition**: For any tuple $(\text{actorId}, \text{operation}, \text{idempotencyKey})$:
  $$\text{Executions} \ge 1 \implies \text{Financial Effects} \equiv 1$$
- **Business Rationale**: Network retries, client double-clicks, and webhook replays must never result in duplicate billing or multi-credit ledger entries.
- **Technical Enforcement**:
  - Authoritative database table `idempotency_records` with unique index.
- **Database Enforcement**:
  - `UNIQUE (actor_id, operation, idempotency_key)` constraint in PostgreSQL.
- **Application Enforcement**:
  - Request payload SHA-256 hash validation: Case A returns cached response; Case B returns `409 Conflict`; Case C rejects concurrent requests.
- **Test Strategy**:
  - Integration test sending identical payload 50 times sequentially and concurrently; assert payment created count is 1 and ledger entries count is 2.

---

### Invariant 5: Atomicity of Financial State Changes
- **Formal Definition**: Business status update, double-entry ledger posting, and outbox event creation must succeed or fail as a single indivisible unit:
  $$\Delta(\text{Payment}) \land \Delta(\text{Ledger}) \land \Delta(\text{Outbox}) \land \Delta(\text{Idempotency}) \in \text{ACID Transaction}$$
- **Business Rationale**: If a payment is marked `SETTLED` but ledger entries fail to post, or outbox fails to register, the system enters an inconsistent state where money is lost or downstream systems are desynchronized.
- **Technical Enforcement**:
  - Single Spring `@Transactional(isolation = Isolation.READ_COMMITTED)` boundary enclosing all database writes.
- **Database Enforcement**:
  - PostgreSQL transaction commit / rollback semantics.
- **Application Enforcement**:
  - Service methods coordinate all writes within a single transactional boundary without committing prematurely or performing non-transactional network I/O inside the database transaction.
- **Test Strategy**:
  - Failure injection test: Inject an artificial exception right before transaction commit; assert database rolls back completely with zero rows in payments, ledger, and outbox.

---

### Invariant 6: Non-Authoritative Client Balances
- **Formal Definition**: Client-supplied balances are untrusted inputs:
  $$\text{Balance}_{\text{effective}} = \sum \text{Ledger Credits} - \sum \text{Ledger Debits} \ne \text{Input}_{\text{client}}$$
- **Business Rationale**: Malicious or buggy clients could submit requests claiming arbitrary account balances.
- **Technical Enforcement**:
  - API contracts forbid client-supplied balance fields in request payloads.
- **Database Enforcement**:
  - Schema contains no column for client-supplied balance assertions.
- **Application Enforcement**:
  - Balances are derived directly from PostgreSQL database records.
- **Test Strategy**:
  - API security tests injecting `balance` or `amount` tampering parameters verify that server rejects or completely ignores external balance parameters.

---

### Invariant 7: Redis Auxiliary Boundary (Zero Financial Truth in Cache)
- **Formal Definition**:
  $$\text{State}(\text{PostgreSQL}) \equiv \text{Truth}; \quad \text{State}(\text{Redis}) \subseteq \text{Transient Optimization}$$
- **Business Rationale**: Redis is an in-memory cache vulnerable to eviction, crash, or failover data loss. It must never hold sole record of money or idempotency.
- **Technical Enforcement**:
  - System operates with full financial consistency even if Redis is completely offline (`FLUSHALL` or stopped).
- **Database Enforcement**:
  - PostgreSQL persists all financial entities, ledger rows, and idempotency records.
- **Application Enforcement**:
  - Application gracefully falls back to PostgreSQL queries if Redis connection fails.
- **Test Strategy**:
  - Chaos test: Stop Redis container mid-payment processing; verify that payments, ledger postings, and idempotency checks complete successfully against PostgreSQL.

---

### Invariant 8: Kafka Transport Boundary (Zero Financial Truth in Stream)
- **Formal Definition**:
  $$\text{PostgreSQL} \succ \text{Outbox} \xrightarrow{\text{transport}} \text{Kafka} \xrightarrow{\text{eventual}} \text{Consumers}$$
- **Business Rationale**: Kafka message lag, partition rebalancing, or out-of-order delivery must never alter the authoritative state of account balances or settled transactions.
- **Technical Enforcement**:
  - Kafka is strictly a read-only transport for published domain events.
- **Database Enforcement**:
  - Core ledger engine never reads from Kafka to determine current balances.
- **Application Enforcement**:
  - Outbox pattern ensures Kafka publishing is asynchronous and non-blocking for financial transactions.
- **Test Strategy**:
  - Verify that tearing down Kafka cluster does not block payment settlement or alter ledger balances in PostgreSQL.

---

### Invariant 9: Compensating Refunds and Reversals
- **Formal Definition**: To reverse an existing transaction $T_1$, the system must append a new compensating transaction $T_2$:
  $$T_2.\text{type} = \text{COMPENSATING}, \quad E_{T_2} = -E_{T_1}, \quad \text{Entries}(T_1) \cup \text{Entries}(T_2) \to \text{Net Zero}$$
- **Business Rationale**: Mistakes or refunds are corrected by writing an opposite entry, never by erasing or modifying original records.
- **Technical Enforcement**:
  - Refund service generates a new `LedgerTransaction` linking `sourceReferenceId = refund.id` with opposite debits/credits.
- **Database Enforcement**:
  - Relational FK linking refund transaction to original payment context.
- **Application Enforcement**:
  - Reversal logic appends inverse debit/credit ledger lines.
- **Test Strategy**:
  - Execute full payment followed by full refund; query account balance and assert net change is exactly 0, while ledger entries count is exactly 4 (2 initial + 2 compensating).

---

### Invariant 10: Strict Currency Consistency
- **Formal Definition**: For every transaction $T$:
  $$\forall e_1, e_2 \in T: \quad e_1.\text{currency} == e_2.\text{currency} == T.\text{currency}$$
- **Business Rationale**: Debiting 100 USD and crediting 100 JPY within the same transaction without an explicit exchange clearing counterparty creates immediate financial deficit.
- **Technical Enforcement**:
  - Transaction creation enforces uniform currency across all entry lines.
- **Database Enforcement**:
  - Database table constraint or trigger verifying `ledger_entries.currency == ledger_transactions.currency`.
- **Application Enforcement**:
  - `Money` operations enforce currency matching.
- **Test Strategy**:
  - Unit test trying to add a EUR entry into a USD transaction throws `CurrencyMismatchException`.

---

### Invariant 11: Prohibition of Arbitrary Administrative Balance Modifications
- **Formal Definition**: No direct API or operator action may execute:
  $$\text{accounts.balance} \leftarrow X \quad (\text{Direct balance override forbidden})$$
- **Business Rationale**: Unilateral balance edits by administrators create un-audited money leaks, enable fraud, and corrupt double-entry integrity.
- **Technical Enforcement**:
  - No controller endpoint exists for editing balances.
  - Adjustments require an explicit `SYSTEM_ADJUSTMENT` balanced transaction specifying reason, operator ID, and offsetting clearing account.
- **Database Enforcement**:
  - Lack of writable balance update procedures for admin roles.
- **Application Enforcement**:
  - Admin service only exposes account status modification (freeze/unfreeze), not balance modification.
- **Test Strategy**:
  - Security audit verifying that no admin endpoint accepts balance override payloads.

---

### Invariant 12: Non-Destructive Error Correction (Permanent Audit History)
- **Formal Definition**:
  $$\text{History}(t) \subseteq \text{History}(t + \Delta t)$$
- **Business Rationale**: System errors, miscalculations, or failed operations must remain visible in audit logs and reconciliation tables to allow forensic auditing.
- **Technical Enforcement**:
  - All audit logs and reconciliation records are append-only.
- **Database Enforcement**:
  - PostgreSQL trigger barring `DELETE` on `audit_logs` and `reconciliation_differences`.
- **Application Enforcement**:
  - Reconciliation workflows flag discrepancies as `RESOLVED` via linked compensating IDs rather than deleting records.
- **Test Strategy**:
  - Integration test asserting that resolving a discrepancy keeps the discrepancy record intact with `status = 'RESOLVED'`.

---

### Invariant 13: Account-Type Non-Negative Balance Enforcement
- **Formal Definition**: Account balances must adhere to their specific accounting semantics:
  - `CUSTOMER`: $\text{Balance} \ge 0$ strictly enforced at all times.
  - `MERCHANT`: $\text{Balance} \ge 0$ enforced unless an authorized contractual overdraft policy is linked.
  - `FEES`: $\text{Balance} \ge 0$ (normal credit balance for revenue).
  - `ESCROW`: $\text{Balance} \ge 0$ strictly enforced.
  - `INTERNAL_SETTLEMENT`: Cleared as a technical asset/liability clearing account; may be positive or negative depending on external provider settlement cycles.
- **Business Rationale**: Customers cannot spend money they do not have.
- **Technical Enforcement**:
  - Pre-debit balance check under pessimistic row lock (`SELECT FOR UPDATE`).
- **Database Enforcement**:
  - `CHECK (account_type != 'CUSTOMER' OR materialized_balance_minor >= 0)` constraint on `accounts`.
- **Application Enforcement**:
  - Application checks balance before creating debit entries for `CUSTOMER` accounts.
- **Test Strategy**:
  - Attempting to debit $101 from a customer account with $100 throws `InsufficientFundsException` and database check constraint triggers if bypassed.

---

### Invariant 14: Materialized Balance Optimization & Reconcilability
- **Formal Definition**:
  $$\text{accounts.materialized\_balance\_minor} \equiv \sum_{e \in \text{posted}} e.\text{direction\_multiplier} \times e.\text{amountMinor}$$
- **Business Rationale**: Materialized balance provides fast $O(1)$ reads but must never drift from immutable ledger entries.
- **Technical Enforcement**:
  - Materialized balance is updated atomically with entry creation in the same transaction.
  - Periodic background reconciliation audits materialized balances against aggregated ledger entries.
- **Application Enforcement**:
  - Divergence triggers an immediate critical operational alert; ledger entries override materialized field.
- **Test Strategy**:
  - Unit test verifying that after 1,000 randomized debit/credit operations, `materialized_balance_minor` matches `SELECT SUM(...)` exactly.

---

### Invariant 15: Monotonic Ledger Sequence Numbers
- **Formal Definition**: For every account $A$, ledger entries must form a strictly increasing sequence:
  $$S_{A, n+1} == S_{A, n} + 1$$
- **Business Rationale**: Guarantees gapless chronological ordering for every account ledger, enabling deterministic transaction history reconstruction and replay.
- **Technical Enforcement**:
  - Account row lock enforces sequential entry generation per account.
- **Database Enforcement**:
  - `UNIQUE (account_id, sequence_number)` constraint in PostgreSQL.
- **Test Strategy**:
  - Concurrent posting test asserts no duplicate sequence numbers and zero sequence gaps.
