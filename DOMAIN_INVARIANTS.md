# Domain Invariants & Financial Integrity Specification

**Repository:** `Ankit8303/distributed-payment-platform`  
**System Class:** Financial Ledger & Distributed Payment Processing Engine  
**Authority:** Core Accounting Invariants

---

## 1. Core Financial Accounting Invariants

### 1.1 Strict Double-Entry Equation
For every immutable ledger transaction persisted in PostgreSQL, the total sum of debits must mathematically equal the total sum of credits:
$$\sum \text{Debits} = \sum \text{Credits}$$
- Enforced at both the domain model level (`LedgerService.java`) and the database schema level (`chk_ledger_entry_amount_positive`, PostgreSQL constraints).
- Immutability is guaranteed by the database trigger `trg_immutable_ledger_entries`, which aborts any `UPDATE` or `DELETE` statement against `ledger_entry`.

### 1.2 Non-Negative Account Balance & No Unintended Overdrafts
- Settled available balance is authoritatively computed as:
$$\text{Available Balance} = \text{Authoritative Ledger Balance} - \sum \text{ACTIVE Reservations}$$
- No debit leg or payout reservation may cause the available balance of an asset account to fall below zero ($< 0$), verified via strict pessimistic row locking (`SELECT ... FOR UPDATE`).

### 1.3 Exact Minor-Unit Money Representation
- All monetary values are represented as 64-bit signed integers (`long amountMinor` / `BIGINT`).
- Floating-point representations (`float`, `double`) are banned.
- Arithmetic operations involving money are verified against overflow using `Math.addExact(...)` and `Math.subtractExact(...)`.
- Multi-currency operations require explicit foreign exchange ledger entries; cross-currency transfers within a single transaction entry without FX legs are prohibited.

---

## 2. Payout & Reservation Lifecycle Invariants (B5 Specification)

### 2.1 Payout Balance Reservation (B5.1)
- Initiating a payout immediately creates a durable `BalanceReservation` in state `ACTIVE` inside the initial database transaction.
- An `ACTIVE` reservation deducts from available balance immediately, preventing double-spend races across concurrent requests.
- Valid terminal states for a balance reservation:
  - `ACTIVE` $\rightarrow$ `CONSUMED`: Transitions atomically when payout reaches `SETTLED`.
  - `ACTIVE` $\rightarrow$ `RELEASED`: Transitions atomically when payout reaches `FAILED`.
- Invariant: A balance reservation may NEVER silently disappear or be deleted from the database.

### 2.2 Payout Idempotency & Crash Recovery (B5.2)
- Replay of an identical idempotency key returns the deterministic cached response or existing payout state.
- In-flight or interrupted operations: If a worker crashes while a payout is in `PROCESSING`, the recovery scanner transitions the payout to `PENDING_RECONCILIATION`.
- Replaying the request during crash recovery MUST NOT issue a secondary debit or re-invoke the external provider rail.
- Invariant: Exactly one external provider transaction per durable `Payout` identity.

---

## 3. Idempotency & Concurrency Invariants

### 3.1 Idempotency Key Semantics
- Every mutating financial endpoint (`/payments`, `/payouts`, `/refunds`, `/reversals`) requires an `Idempotency-Key` header.
- **Identical Key + Identical Payload:** Returns the previously committed response with zero additional side effects.
- **Identical Key + Divergent Payload:** Returns HTTP 409 Conflict (`IDEMPOTENCY_KEY_PAYLOAD_MISMATCH`).
- Unique constraint `uk_idempotency_key_scope` guarantees race safety under concurrent requests.

### 3.2 Global Multi-Account Lock Ordering
To prevent distributed deadlocks during simultaneous cross-account transfers (e.g. Account A $\rightarrow$ Account B and Account B $\rightarrow$ Account A):
- Locks must ALWAYS be acquired in natural lexicographical order of UUID:
$$\min(\text{ID}_A, \text{ID}_B) \rightarrow \max(\text{ID}_A, \text{ID}_B)$$
- No competing lock ordering strategy may be introduced in any service.

---

## 4. External Boundary & Gateway Invariants

### 4.1 Boundary Failure Ambiguity
- Network timeouts, gateway 5xx responses, and connection drops represent **ambiguous outcomes** (`UNKNOWN`), NEVER confirmed failures.
- When gateway outcome is unknown, payout state transitions to `PENDING_RECONCILIATION` and the balance reservation remains `ACTIVE`.
- External provider status polling or file-based reconciliation is required to reach terminal state (`SETTLED` or `FAILED`).

### 4.2 Network Isolation from DB Transactions
- No database connection pool lease or pessimistic lock may be held during external network I/O (`@Transactional` boundary must end before the HTTP call and resume in a new transaction afterwards).

---

## 5. Security & Authorization Invariants

### 5.1 Zero BOLA/IDOR Exposure
- Accessing any account, payment, payout, or refund record verifies that the authenticated caller matches the resource's owner or counterparty.
- Querying a non-owned financial resource returns HTTP 404/403 with no leaked existence metadata.

### 5.2 Immutable Audit Trail
- All financial state transitions, administrative overrides, account freezing/unfreezing, and balance adjustments generate append-only audit events logged with actor identity, timestamp, and correlation ID.
