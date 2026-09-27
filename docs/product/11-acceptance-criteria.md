# Acceptance Criteria Specification

## 1. Quality Standards & Verifiability
Every major functional capability and financial invariant must possess unambiguous, testable acceptance criteria expressed in Given-When-Then format. No capability is deemed complete without passing automated test evidence corresponding to these criteria.

---

## 2. Core Acceptance Criteria Matrix

### AC-01: Balanced Double-Entry Ledger Posting
- **Given**: A customer initiates a valid payment of 10,000 minor units ($100.00 USD) with a 250 minor unit ($2.50 USD) platform fee.
- **When**: The payment is confirmed and the double-entry ledger transaction is posted.
- **Then**:
  - Exactly one `ledger_transactions` row is created with status `POSTED`.
  - Exactly three `ledger_entries` rows are created:
    - Customer Account: `DEBIT` 10,000 minor units
    - Merchant Account: `CREDIT` 9,750 minor units
    - Platform Fee Account: `CREDIT` 250 minor units
  - The equation $\sum \text{Debits} == \sum \text{Credits}$ holds true ($10,000 == 9,750 + 250$).
  - An attempt to post an unbalanced transaction raises `UnbalancedTransactionException` and rolls back the database.

---

### AC-02: Strict Ledger Immutability
- **Given**: A posted ledger transaction and its associated ledger entries exist in the database.
- **When**: An administrative user, application process, or SQL query attempts an `UPDATE` or `DELETE` on any row in `ledger_entries` or `ledger_transactions`.
- **Then**:
  - The database trigger `trg_immutable_ledger_entries` intercepts the command and raises a fatal exception `CANNOT_MODIFY_POSTED_LEDGER`.
  - The row remains completely unmodified; zero records are deleted.

---

### AC-03: Durable Idempotency with Concurrent Race Condition Prevention
- **Given**: A client submits a payment request with `Idempotency-Key: KEY-12345`.
- **When**: 50 concurrent threads submit the exact same request payload with `KEY-12345` simultaneously.
- **Then**:
  - Exactly 1 payment record is created in `payments`.
  - Exactly 1 `ledger_transactions` row and its balanced entries are posted.
  - Exactly 1 thread receives `201 Created` with the transaction result.
  - The remaining 49 threads receive `409 Conflict` (if processed concurrently) or `200/201` with the exact cached response from the completed execution.
  - Customer account is debited exactly once; no multi-billing occurs.

---

### AC-04: Accurate Refund Limitation and Partial Refunds
- **Given**: A settled payment of 10,000 minor units exists.
- **When**:
  1. A partial refund of 4,000 minor units is requested and processed.
  2. A second partial refund of 6,000 minor units is requested and processed.
  3. A third refund of 1 minor unit ($0.01) is requested.
- **Then**:
  - The first two refunds succeed (`201 Created`), creating compensating balanced ledger transactions.
  - The third refund is rejected with `422 Unprocessable Entity` (`REFUND_AMOUNT_EXCEEDS_PAYMENT`).
  - Total settled refunds equal exactly 10,000 minor units; net merchant settlement is exactly 0.

---

### AC-05: Non-Authoritative Client Balances & Currency Enforcement
- **Given**: A client submits a payment request payload attempting to specify their account balance: `{"amountMinor": 5000, "balance": 9999999}`.
- **When**: The system processes the payment request.
- **Then**:
  - The client-supplied `balance` attribute is completely ignored by the deserializer and domain logic.
  - The system checks balance strictly against the database-backed ledger.
  - If the currency of the payer account does not match the payment currency, the request is rejected with `422 Unprocessable Entity` (`CURRENCY_MISMATCH`).

---

### AC-06: Outbox Event Reliability and Deduplication
- **Given**: A payment settles successfully within a database transaction.
- **When**:
  1. The transaction commits to PostgreSQL.
  2. The outbox relay reads the record and publishes to Kafka.
  3. A network glitch causes Kafka broker to acknowledge late, triggering an outbox retry and duplicate Kafka message delivery.
- **Then**:
  - The event is delivered at least once to Kafka.
  - The downstream consumer processes the first message, inserts a record into `consumed_messages`, and sends a customer notification.
  - When the duplicate message arrives, the consumer detects the existing message ID, skips processing immediately, and prevents duplicate customer notifications.

---

### AC-07: IDOR Protection on Accounts, Payments, and Refunds
- **Given**: Customer A (authenticated) and Customer B (authenticated) have distinct accounts.
- **When**: Customer A calls `GET /api/v1/accounts/{customerB_accountId}` or `GET /api/v1/payments/{customerB_paymentId}`.
- **Then**:
  - The API intercepts the request via method-level authorization.
  - Customer A receives `403 Forbidden` or `404 Not Found`.
  - Zero financial data of Customer B is returned in the response payload.

---

### AC-08: Administrative Access Control and Immutability of Balance
- **Given**: An administrator is authenticated with `ROLE_ADMIN`.
- **When**:
  1. Admin attempts to call `PUT /api/v1/accounts/{id}/balance` or inject an arbitrary balance adjustment without a balanced transaction.
- **Then**:
  - The API returns `404 Not Found` (endpoint does not exist) or `405 Method Not Allowed`.
  - Admin cannot directly alter balance fields.
  - Any administrative adjustment must be executed via `POST /api/v1/reconciliation/differences/{id}/resolve` resulting in an explicit, balanced `SYSTEM_ADJUSTMENT` double-entry ledger transaction with full audit trail.

---

### AC-09: Provider Timeout Recovery and Reconciliation Detection
- **Given**: A payment capture call to the external gateway experiences an HTTP socket read timeout after 10 seconds.
- **When**: The timeout occurs during the payment execution flow.
- **Then**:
  - The payment status transitions to `PENDING_RECONCILIATION` (NOT `FAILED` or `DECLINED`).
  - No double-entry ledger transaction is posted yet.
  - The API returns `202 Accepted` with a status polling URL.
  - Active background status queries or the daily reconciliation run confirm the provider outcome and transition the payment deterministically to `SETTLED` (with ledger posting) or `FAILED`.

---

### AC-10: Rate Limiting & Webhook Signature Validation
- **Given**: The public endpoint `/api/v1/auth/login` and webhook endpoint `/api/v1/webhooks/stripe`.
- **When**:
  1. An attacker sends 15 login requests within 30 seconds from a single IP.
  2. An attacker sends a webhook request with a spoofed or absent `X-Signature-SHA256` header.
- **Then**:
  - Login request 11+ is rejected with `429 Too Many Requests`.
  - Webhook request with invalid signature is rejected with `401 Unauthorized` and an immutable security audit event is logged.

---

### AC-11: Provider Timeout Does Not Incorrectly Mark Successful Payment as Failed
- **Given**: External provider successfully captures customer funds, but network socket drops before application receives HTTP response.
- **When**: The application timeout handler executes.
- **Then**:
  - The system records state as `PENDING_RECONCILIATION`.
  - The system does NOT mark the payment as `FAILED` or `DECLINED`.
  - Subsequent status check or webhook confirming success settles the payment and posts the ledger without duplicate charging.

---

### AC-12: Materialized Balance Cannot Silently Become Financial Truth
- **Given**: A deliberate discrepancy is injected into `accounts.materialized_balance_minor` (simulating cache corruption).
- **When**: `GET /api/v1/accounts/{id}/balance` or a reconciliation health audit executes.
- **Then**:
  - The system calculates balance directly from `SUM(ledger_entries)`.
  - The authoritative balance overrides the materialized cache.
  - An integrity discrepancy alert is emitted to the operational monitoring dashboard.

---

### AC-13: Account-Type Balance Rules Enforced According to Account Semantics
- **Given**: Accounts of type `CUSTOMER`, `MERCHANT`, and `INTERNAL_SETTLEMENT`.
- **When**: A debit operation is attempted that would result in a balance $< 0$.
- **Then**:
  - `CUSTOMER` account: Request is strictly rejected with `422 Unprocessable Entity` (`INSUFFICIENT_FUNDS`).
  - `MERCHANT` account: Request is rejected unless an explicit contractual overdraft flag is present.
  - `INTERNAL_SETTLEMENT` account: Allowed to hold a technical negative clearing balance representing external processor receivables.

---

### AC-14: Same Idempotency Key with Different Request Produces Deterministic Conflict
- **Given**: A payment request is processed with `Idempotency-Key: KEY-999` and amount 5,000 minor units.
- **When**: A subsequent request arrives with `Idempotency-Key: KEY-999` but with amount 7,500 minor units or a different payee account.
- **Then**:
  - The system detects payload hash mismatch.
  - The request is immediately rejected with `409 Conflict` (`IDEMPOTENCY_KEY_PAYLOAD_MISMATCH`).
  - No payment or ledger transaction is initiated.

---

### AC-15: Committed Financial Operation Safely Recovers After Application Crash
- **Given**: A payment transaction commits state changes, ledger entries, and outbox event, but the JVM is terminated before transmitting the HTTP response to the client.
- **When**: The client reconnects and retries the payment request with the same idempotency key.
- **Then**:
  - The system queries `idempotency_records`, detects `status == COMPLETED`, and returns the cached HTTP response and payment ID.
  - Zero duplicate payments or ledger entries are created.

---

### AC-16: Reconciliation Identifies Standard Discrepancy Categories
- **Given**: An external provider settlement file containing missing transactions, altered fee amounts, and currency discrepancies.
- **When**: The reconciliation engine executes against the internal database.
- **Then**:
  - Discrepancies are categorized accurately as `MISSING_INTERNAL`, `MISSING_PROVIDER`, `AMOUNT_MISMATCH`, or `STATUS_MISMATCH`.
  - Discrepancy records are stored in `reconciliation_differences` with status `OPEN`.

---

### AC-17: Reconciliation Never Mutates Posted Ledger History
- **Given**: An open reconciliation discrepancy of type `AMOUNT_MISMATCH` is investigated by an administrator.
- **When**: The administrator issues an approved resolution command.
- **Then**:
  - The historical `ledger_entries` rows remain completely untouched (`UPDATE/DELETE` barred).
  - A new compensating `LedgerTransaction` of type `SYSTEM_ADJUSTMENT` is posted with balanced debits and credits.
  - Discrepancy status is updated to `RESOLVED` referencing the compensating transaction ID.

---

### AC-18: Provider-Specific Models Do Not Leak into Core Domain
- **Given**: External provider SDK DTOs (e.g. `StripeCharge`, `StripeError`).
- **When**: Core domain classes (`Payment`, `LedgerService`, `Account`) are inspected.
- **Then**:
  - ArchUnit tests assert zero imports of third-party payment gateway packages in `com.paymentledger.payment.domain` or `com.paymentledger.ledger.domain`.
  - All external interactions occur via the abstract `PaymentProviderGateway` interface.

---

### AC-19: Financial Operations are Traceable End-to-End
- **Given**: A customer initiates payment with `X-Correlation-ID: CORR-ABC-123`.
- **When**: The payment traverses HTTP request, database storage, outbox publishing, Kafka transport, and consumer notification.
- **Then**:
  - `CORR-ABC-123` is persisted in `payments.idempotency_scope` / metadata.
  - `CORR-ABC-123` is embedded in the Kafka event header and JSON envelope.
  - `CORR-ABC-123` appears in all structured log lines in MDC.
  - An auditor can query the complete event trail using only the correlation ID.

---

### AC-20: Requirement Traceability is Complete
- **Given**: All platform functional requirements and financial invariants specified in Phase 0.
- **When**: The Traceability Matrix (`docs/product/12-requirement-traceability.md`) is evaluated.
- **Then**:
  - 100% of requirements map directly to use cases, domain entities, database structures, API endpoints, Kafka events, security controls, and automated acceptance criteria.
  - Zero orphan requirements or untested invariants exist.
