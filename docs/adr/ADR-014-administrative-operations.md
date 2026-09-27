# ADR-014: Administrative & Operations Layer

## Status
Accepted and Frozen (Phase 14)

## Context
The Distributed Payment & Ledger Platform operates a multi-account, double-entry financial core with event-driven notifications, outbox-mediated asynchronous messaging via Kafka, and automated reconciliation against external providers.

While financial core operations (Phases 0–13) enforce strict non-negotiable invariants (such as double-entry ledger balancing, append-only entries, immutable financial records, and provider-reconciled consistency), operations teams and compliance officers require controlled capabilities to:
1. Investigate customer accounts, transactions, refunds, payouts, ledger history, reconciliation cases, and notification deliveries.
2. Intervene in account lifecycles (freezing and unfreezing accounts due to sanctions, fraud detection, or compliance reviews).
3. Safely re-trigger operational actions (such as re-evaluating reconciliation cases or retrying failed notifications) without bypassing domain invariants.
4. Maintain an immutable, tamper-evident audit record of every administrative action.

Crucially, **administrative operations must never directly mutate financial truth**.

---

## Decision Drivers
- **Financial Invariant Preservation**: Administrators cannot arbitrarily edit balances, mark payments `SETTLED`, craft ledger transactions out of thin air, or delete ledger history.
- **Least-Privilege Authorization**: Privileged APIs are strictly restricted to `ROLE_ADMIN` and `ROLE_SYSTEM`. Customers and merchants must receive HTTP 403 Forbidden.
- **Traceability & Correlation**: Every operation must record the actor, action, resource, reason, correlation ID, and HTTP request ID.
- **Tamper-Evident Audit Trail**: Privileged operations write append-only records into `admin_audit_logs`. No update, edit, or delete endpoints exist. Sensitive credentials (passwords, tokens, CVVs, card numbers, private keys) are scrubbed before storage.
- **Safe Operational Lifecycle**: Account freeze and unfreeze are lifecycle/security state changes, not balance operations. They do not alter ledger transactions, posted payments, or ledger entries.
- **Concurrency & Idempotency**: Transitions employ pessimistic locking (`SELECT FOR UPDATE`) on the account aggregate to prevent race conditions and lost updates. Idempotent replays succeed cleanly.

---

## Architectural Decisions

### 1. Administrative Boundary & Authorization Model
All administrative capabilities are grouped under the `/api/v1/admin/**` URI prefix and enforced with method-level Spring Security annotations:
- `@PreAuthorize("hasAnyRole('ADMIN', 'SYSTEM')")` protects controllers and domain operations.
- Roles `ROLE_CUSTOMER` and `ROLE_MERCHANT` are rejected with HTTP 403 Forbidden.
- Unauthenticated requests are rejected with HTTP 401 Unauthorized.
- DTO representations cleanly separate internal database models from external consumers. Under no circumstances are JPA entities exposing credentials (e.g. `passwordHash`) returned to clients.

### 2. Immutable Administrative Audit Architecture
- A dedicated Flyway migration (`V12__admin_and_operations.sql`) creates the `admin_audit_logs` table.
- `AdminAuditLogEntity` is an append-only entity with no `updated_at` column.
- `AdminAuditService.recordAudit(...)` persists audit records. When invoked within a transactional boundary (such as account freeze), the audit record shares the transaction with the mutation: if the mutation fails or rolls back, no false-success audit log is retained.
- An automatic sanitization filter parses structured metadata and message text to redact sensitive tokens, JWTs, credentials, and PAN details before insertion.
- The repository and controller expose `GET /api/v1/admin/audit-logs` and `GET .../{id}`. All HTTP `PUT`, `PATCH`, and `DELETE` methods are rejected with HTTP 405 Method Not Allowed.

### 3. Account Freeze & Unfreeze Semantics
- Exposed via `POST /api/v1/admin/accounts/{accountId}/freeze` and `POST /api/v1/admin/accounts/{accountId}/unfreeze`.
- Invariants:
  - Account state transitions from `ACTIVE` to `FROZEN`, or `FROZEN` to `ACTIVE`.
  - Freezing or unfreezing a `CLOSED` account is invalid and rejected with HTTP 400 (domain conflict).
  - Freezing an already `FROZEN` account or unfreezing an already `ACTIVE` account is idempotent (returns HTTP 200 OK without state corruption).
  - Pessimistic locking (`findByIdForUpdate`) ensures sequential consistency during concurrent requests.
  - Lifecycle changes emit `AccountFrozen` or `AccountUnfrozen` domain events through the transactional outbox (`OutboxService`) to Kafka topic `account.events`.
  - Cache entries in Redis (`AccountReadCacheService`) are evicted immediately.
  - Zero modifications to ledger entries, ledger transactions, or account balances occur.

### 4. Authoritative Financial vs Materialized Balance Investigation
- `GET /api/v1/admin/accounts/{accountId}/balance-summary` returns:
  - `materializedBalanceMinor`: Read from the account row or cache.
  - `authoritativeLedgerBalanceMinor`: Dynamically computed as the sum of immutable ledger entries (`SUM(CASE WHEN direction = 'CREDIT' THEN amount_minor ELSE -amount_minor END)`).
  - `differenceMinor`: The delta between materialized and authoritative figures.
  - `isConsistent`: Boolean flag indicating mathematical alignment.
- This ensures operations personnel always see the authoritative double-entry truth alongside cached states without altering any database records.

### 5. Unified Investigation Trace Architecture
- `GET /api/v1/admin/investigations/payments/{paymentId}` constructs a full end-to-end trace:
  - Payment details (amount, currency, status, provider reference).
  - Payer and payee account summaries.
  - Double-entry ledger transactions and child entry splits (`DEBIT` and `CREDIT`).
  - Transactional outbox events associated with the payment.
  - Reconciliation cases and attempt history.
  - Notifications dispatched and delivery attempts.
- The investigation API executes pure read queries across optimized indexed repositories without locking financial records or modifying state.

### 6. Controlled Operational Retries
- **Reconciliation Retry**: `POST /api/v1/admin/reconciliation/cases/{caseId}/retry` invokes `ReconciliationService.reconcileCase(...)` and logs an administrative audit entry. It does not allow manual status overwriting.
- **Notification Retry**: `POST /api/v1/admin/notifications/{notificationId}/retry` invokes `NotificationService.retryNotification(...)` and logs an administrative audit entry. It reuses the existing Phase 13 notification engine.

### 7. Pagination, Filtering, and IDOR Protection
- Standardized pagination utility `PageUtils.clamp(Pageable)` enforces an upper bound of `MAX_PAGE_SIZE = 100`. Excessive page sizes requested by clients are clamped safely to prevent memory exhaustion and DoS vulnerabilities.
- Filter criteria (status, accountId, paymentId, currency, date range) use parameterized JPA query derivations.
- Non-existent resource identifiers return HTTP 404 Not Found rather than ambiguous responses or stack traces.

---

## Consequences & Guarantees

### Positive
- Strict separation between operational tooling and financial truth is maintained.
- Every privileged state mutation is accompanied by an immutable audit log.
- Operations can diagnose reconciliation issues, notification failures, and ledger balances in real time.
- Standardized pagination prevents large memory allocations during administrative searches.

### Negative / Trade-offs
- Administrators cannot "fix" a stuck payment by changing its status to `SETTLED`. They must trigger reconciliation or initiate refunds/adjustments through domain-sanctioned channels.
- Slightly higher database storage requirements due to append-only audit logging for privileged actions.

---

## Rejected Alternatives
1. **Direct Status Override APIs**: Rejected. Allowing administrators to directly flip payment status to `SETTLED` or adjust account balances violates the double-entry bookkeeping invariant and causes phantom discrepancies with external payment processors.
2. **Mutable Audit Tables with Soft Deletes**: Rejected. Audit logs must be tamper-evident and append-only to satisfy SOC 2, PCI-DSS, and regulatory financial compliance standards.
3. **Unbounded Admin Query APIs**: Rejected. Allowing unbounded pagination exposes the platform to memory exhaustion attacks when retrieving large ledger tables.
