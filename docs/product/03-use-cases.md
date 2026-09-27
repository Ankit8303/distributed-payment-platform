# Use Cases Specification

## 1. Scope & Actors
This document defines the formal operational and financial use cases for the **Distributed Payment & Ledger Platform**.
Primary Actors: `CUSTOMER`, `MERCHANT`, `ADMIN / OPERATIONS`, `SYSTEM / SCHEDULER`.

---

## 2. Core Use Cases

### UC-01: User Registration & Authentication
- **Actors**: `CUSTOMER`, `MERCHANT`.
- **Preconditions**: Client has valid email, credentials, and network connectivity.
- **Main Success Scenario**:
  1. Client sends `POST /api/v1/auth/register` with registration details and role.
  2. System validates email format, password complexity, and verifies uniqueness.
  3. System hashes password with Argon2id / BCrypt (cost $\ge 12$).
  4. System inserts `users` record in `ACTIVE` status.
  5. System triggers automatic creation of default `Account` (`CUSTOMER` or `MERCHANT` type) in `ACTIVE` status with zero balance.
  6. Client invokes `POST /api/v1/auth/login` with credentials.
  7. System validates credentials, issues signed short-lived JWT access token and HTTP-only refresh token.
- **Alternative / Failure Flows**:
  - *Duplicate Email*: System returns `409 Conflict`.
  - *Weak Password*: System returns `400 Bad Request` with validation constraints.
  - *Invalid Credentials*: System returns `401 Unauthorized`.
- **Postconditions**: User is authenticated; default operational account exists and is ready for transactions.

---

### UC-02: Account Lifecycle Management
- **Actors**: `ADMIN / OPERATIONS`, `SYSTEM`.
- **Preconditions**: Admin is authenticated with `ROLE_ADMIN`. Target account exists.
- **Main Success Scenario (Freeze & Unfreeze)**:
  1. Admin inspects flagged account and calls `POST /api/v1/admin/accounts/{id}/freeze` supplying a mandatory audit reason.
  2. System verifies account is currently `ACTIVE`.
  3. System updates account status to `FROZEN` and inserts an immutable `audit_logs` record.
  4. System enqueues `AccountFrozen` event in transactional outbox.
  5. Subsequent payment debit attempts against this account are rejected with `422 Unprocessable Entity`.
  6. Once investigation concludes, Admin calls `POST /api/v1/admin/accounts/{id}/unfreeze` with resolution justification.
  7. Account returns to `ACTIVE`; `AccountUnfrozen` event is emitted.
- **Alternative / Failure Flows**:
  - *Missing Justification*: System returns `400 Bad Request`.
  - *Non-Existent Account*: System returns `404 Not Found`.
  - *Unauthorized Actor*: Non-admin receives `403 Forbidden`.
- **Postconditions**: Account state transitions safely; all operations are permanently audited.

---

### UC-03: Process Payment (Idempotent Checkout & Instant Capture)
- **Actors**: `CUSTOMER`, `SYSTEM`, `PaymentProviderAdapter`.
- **Preconditions**:
  - Customer is authenticated. Customer account is `ACTIVE`.
  - Merchant account exists, is `ACTIVE`, and matches payment currency.
  - Header contains `Idempotency-Key` and `X-Correlation-ID`.
- **Main Success Scenario**:
  1. Customer issues `POST /api/v1/payments` with `{ payeeAccountId, amountMinor, currency, paymentMethodToken }`.
  2. **Idempotency Gate**: System queries `idempotency_records` within scope `(customerId, 'PAYMENT_CREATE', idempotencyKey)`.
     - If key exists with identical hash and `status == COMPLETED`, system returns cached response immediately (**Case A**).
     - If key exists with mismatched hash, system returns `409 Conflict` (**Case B**).
     - If key exists with `status == IN_PROGRESS`, system returns `409 Conflict` / `429 Too Many Requests` (**Case C**).
     - If key is new, system inserts `idempotency_records` row with `status = IN_PROGRESS`.
  3. System validates Customer balance: $\text{Customer Balance} \ge \text{amountMinor}$.
  4. System calls `PaymentProviderAdapter.authorizeAndCapture(...)`.
  5. Provider returns success with `providerTransactionReference`.
  6. **Atomic Financial Commit** (Single Database ACID Transaction):
     - Update payment record to `SETTLED`.
     - Insert `ledger_transactions` record of type `PAYMENT` with `status = POSTED`.
     - Insert balanced `ledger_entries`:
       - `DEBIT` Customer Account (`amountMinor`)
       - `CREDIT` Merchant Account (`amountMinor - feeAmountMinor`)
       - `CREDIT` Platform Fee Account (`feeAmountMinor`)
       - *Invariant Check*: $\text{Debits} == \text{Credits} = \text{amountMinor}$.
     - Update `accounts.materialized_balance_minor` for affected accounts.
     - Insert `PaymentSettled` event into `outbox_events`.
     - Update `idempotency_records` with `status = COMPLETED` and response payload.
  7. System returns `201 Created` with Payment details and transaction trace.
- **Alternative / Failure Flows**:
  - *Insufficient Balance*: Returns `422 Unprocessable Entity` (`INSUFFICIENT_FUNDS`). Idempotency record updated to `FAILED`.
  - *Provider Decline*: Provider returns `DECLINED`. Payment updated to `DECLINED`. No ledger entries posted. Idempotency updated to `FAILED`.
  - *Provider Timeout / Network Loss*: Handled by **UC-11**.
- **Postconditions**: Funds are irrevocably moved in the ledger; outbox event is ready for Kafka dispatch.

---

### UC-04: Process Partial and Full Refund
- **Actors**: `MERCHANT`, `SYSTEM`.
- **Preconditions**:
  - Original payment exists and is in `SETTLED` status.
  - Merchant owns the payment payee account.
  - Cumulative refunded amount + requested refund amount $\le$ original payment amount.
- **Main Success Scenario**:
  1. Merchant issues `POST /api/v1/payments/{paymentId}/refunds` with `{ amountMinor, reason }` and `Idempotency-Key`.
  2. Idempotency gate checks uniqueness and payload match.
  3. System calculates remaining refundable balance:
     $$\text{Remaining Refundable} = \text{payment.amountMinor} - \sum \text{Settled Refunds}$$
     Validates $\text{amountMinor} \le \text{Remaining Refundable}$.
  4. System invokes `PaymentProviderAdapter.refund(...)`.
  5. Provider confirms refund execution.
  6. **Atomic Financial Commit** (Single Database ACID Transaction):
     - Insert `refunds` row with `status = SETTLED`.
     - Insert compensating `ledger_transactions` row of type `REFUND`.
     - Insert balanced compensating `ledger_entries`:
       - `DEBIT` Merchant Account (`amountMinor - feeRefundPortion`)
       - `DEBIT` Platform Fee Account (`feeRefundPortion`)
       - `CREDIT` Customer Account (`amountMinor`)
       - *Invariant Check*: $\sum \text{Debits} == \sum \text{Credits}$.
     - Update materialized account balances.
     - Insert `RefundSettled` outbox event.
     - Mark idempotency record `COMPLETED`.
  7. System returns `201 Created` with Refund details.
- **Alternative / Failure Flows**:
  - *Exceeds Refundable Amount*: Returns `422 Unprocessable Entity` (`REFUND_AMOUNT_EXCEEDS_PAYMENT`).
  - *Original Payment Not Settled*: Returns `422 Unprocessable Entity`.
- **Postconditions**: Compensating journal entries appended; historical records remain immutable.

---

### UC-05: Query Account Balances & Ledger Statement
- **Actors**: `CUSTOMER`, `MERCHANT`, `ADMIN`.
- **Preconditions**: Actor is authenticated and authorized to access target account (ownership check for Customer/Merchant).
- **Main Success Scenario**:
  1. Client calls `GET /api/v1/accounts/{id}/balance`.
  2. System checks ownership (IDOR defense).
  3. System computes authoritative balance directly from ledger entries:
     $$\text{Authoritative Balance} = \sum_{\text{posted}} \text{Credits} - \sum_{\text{posted}} \text{Debits}$$
  4. System verifies that authoritative balance matches `materialized_balance_minor`. If mismatch is detected, triggers discrepancy alert and logs divergence.
  5. System returns current balance, available balance, currency, and as-of timestamp.
  6. Client calls `GET /api/v1/accounts/{id}/transactions` with pagination.
  7. System returns chronological list of posted ledger entries and transaction references.
- **Postconditions**: Client receives authoritative, tamper-proof financial statement.

---

### UC-06: Outbox Event Polling & Kafka Relay
- **Actors**: `SYSTEM / SCHEDULER`.
- **Preconditions**: Outbox poller runs at configured interval (e.g. 500ms). Kafka broker is reachable.
- **Main Success Scenario**:
  1. Poller queries batch of pending events:
     `SELECT * FROM outbox_events WHERE status = 'PENDING' ORDER BY created_at ASC LIMIT 100 FOR UPDATE SKIP LOCKED`.
  2. For each event, poller sends message to appropriate Kafka topic (partitioned by `aggregateId` or `accountId` to preserve ordering).
  3. Kafka acknowledges receipt (`acks=all`).
  4. Poller updates `outbox_events.status = 'PUBLISHED'` and sets `published_at = NOW()`.
- **Alternative / Failure Flows**:
  - *Kafka Unavailable / Disconnect*: Poller catches exception, increments `retry_count`, computes exponential backoff for `next_retry_at`, and leaves status `PENDING`. No messages are dropped.
- **Postconditions**: Events are reliably published to Kafka with at-least-once delivery guarantees.

---

### UC-07: Ingest Payment Webhooks
- **Actors**: External Payment Provider, `PaymentProviderAdapter`.
- **Preconditions**: Provider delivers signed HTTP POST webhook.
- **Main Success Scenario**:
  1. Provider issues webhook to `/api/v1/webhooks/{provider}` with signature header.
  2. Adapter verifies cryptographic signature (HMAC-SHA256) using pre-shared secret.
  3. Adapter verifies timestamp freshness window ($\le 300$ seconds) to defeat replay attacks.
  4. Adapter extracts external event ID and checks idempotency deduplication table. If already processed, returns `200 OK` immediately.
  5. Adapter maps vendor payload to internal domain event (e.g. `PAYMENT_CAPTURED_EXTERNALLY` or `DISPUTE_OPENED`).
  6. System executes corresponding state transition and ledger transaction within a database transaction.
  7. System returns `200 OK`.
- **Alternative / Failure Flows**:
  - *Invalid Signature*: Returns `401 Unauthorized`. Request rejected and logged in security audit.
  - *Expired Timestamp*: Returns `400 Bad Request`.
- **Postconditions**: Asynchronous provider events are ingested idempotently without duplicate side effects.

---

### UC-08: Execute Financial Reconciliation Run
- **Actors**: `ADMIN / OPERATIONS`, `SYSTEM / SCHEDULER`.
- **Preconditions**: External settlement report for date $T$ is available.
- **Main Success Scenario**:
  1. Scheduler or Admin triggers `POST /api/v1/reconciliation/runs` with `{ windowStart, windowEnd, providerName }`.
  2. System creates `reconciliation_runs` in `RUNNING` status.
  3. System parses external settlement file and loads all internal `payments` and `refunds` for the specified time window.
  4. System performs two-way match on transaction references, status, currency, and amount.
  5. Discrepancies are identified and classified into standard categories:
     - `MISSING_INTERNAL`: Present in provider file, absent internally.
     - `MISSING_PROVIDER`: Present internally, absent in provider file.
     - `AMOUNT_MISMATCH`: Net or fee amount discrepancy.
     - `STATUS_MISMATCH`: Internal state differs from external settlement.
  6. Discrepancies are inserted into `reconciliation_differences` with status `OPEN`.
  7. Run completes; status set to `COMPLETED` with summary statistics.
- **Postconditions**: Discrepancies are isolated, documented, and ready for operational review.

---

### UC-09: Resolve Reconciliation Discrepancy
- **Actors**: `ADMIN / OPERATIONS`.
- **Preconditions**: An open `reconciliation_differences` record exists. Admin has `ROLE_ADMIN`.
- **Main Success Scenario**:
  1. Admin inspects discrepancy details via `GET /api/v1/reconciliation/differences/{id}`.
  2. Admin determines cause (e.g. external provider levied an unanticipated cross-border fee).
  3. Admin issues `POST /api/v1/reconciliation/differences/{id}/resolve` with `{ reason, resolutionType, counterpartyAccountId }`.
  4. **Strict Financial Constraint**: System **NEVER** executes an `UPDATE` or `DELETE` on historical ledger entries.
  5. System creates an explicit compensating `LedgerTransaction` of type `SYSTEM_ADJUSTMENT` with balanced debit and credit entries adjusting the fee/clearing account.
  6. Difference is marked `RESOLVED` with reference to the compensating transaction ID.
  7. Immutable audit record is persisted.
- **Postconditions**: Discrepancy is resolved cleanly through compensating double-entry accounting.

---

### UC-10: Administrative Security Audit Trail Retrieval
- **Actors**: `ADMIN / OPERATIONS`.
- **Preconditions**: Admin is authenticated with `ROLE_ADMIN`.
- **Main Success Scenario**:
  1. Admin queries `GET /api/v1/admin/audit-logs` with filters (`actorId`, `entityType`, `dateRange`).
  2. System returns immutable chronological audit records including actor role, IP address, user agent, action, and JSON state diffs.
- **Postconditions**: Complete forensics trail provided without exposing sensitive user secrets or card tokens.

---

### UC-11: Provider Timeout & Ambiguity Recovery
- **Actors**: `CUSTOMER`, `SYSTEM / SCHEDULER`, `PaymentProviderAdapter`.
- **Preconditions**: Customer initiates payment; network socket to provider times out during capture call.
- **Main Success Scenario**:
  1. System sends capture request to provider gateway.
  2. Provider receives request and debits the underlying card, but network connection drops before application receives HTTP response.
  3. Socket timeout triggers in `PaymentProviderAdapter`.
  4. **Guaranteed Non-Failure Rule**: System **MUST NOT** mark payment as `FAILED` or `DECLINED`. Doing so would cause an immediate balance mismatch.
  5. System transitions payment status to `PENDING_RECONCILIATION`. No ledger entries are posted yet.
  6. System returns `202 Accepted` to client with `status = PENDING_RECONCILIATION` and `pollUrl`.
  7. **Active Recovery**:
     - System immediately schedules an active provider status lookup using the payment's unique idempotency key.
     - Alternatively, if incoming webhook arrives first, it matches the payment reference.
  8. Once provider confirms successful capture, system executes atomic commit: updates payment to `SETTLED`, posts double-entry ledger entries, inserts outbox event, and marks idempotency record `COMPLETED`.
  9. If provider confirms the transaction was never received or declined, payment transitions to `FAILED`.
- **Postconditions**: Indeterminate network outcomes are resolved deterministically without phantom charges or lost money.
