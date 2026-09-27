# API Inventory & Endpoint Catalog

**Repository:** `Ankit8303/distributed-payment-platform`  
**API Specification:** REST over JSON (HTTP/1.1 & HTTP/2)  
**Standard Prefix:** `/api/v1`  
**Error Standard:** RFC 7807 Problem Details compatible (`ApiErrorResponse`)

---

## 1. Authentication & Session Endpoints (`AuthController`)

| Method | Path | Security Classification | Idempotency Support | Rate Limit (Tier) | Description / Contract |
| :--- | :--- | :--- | :---: | :---: | :--- |
| `POST` | `/api/v1/auth/register` | Public | No (Validation) | High (Auth) | Registers new user; returns generated User ID and default role. |
| `POST` | `/api/v1/auth/login` | Public | No | High (Auth) | Authenticates email + password; returns JWT access + refresh token. |
| `POST` | `/api/v1/auth/refresh` | Public / Token Bound | No | High (Auth) | Rotates JWT access token given a valid, unrevoked refresh token. |

---

## 2. Core Financial Customer & Merchant Endpoints

### 2.1 Accounts (`AccountController`)

| Method | Path | Security Classification | Idempotency Support | Rate Limit | Description / Contract |
| :--- | :--- | :--- | :---: | :---: | :--- |
| `POST` | `/api/v1/accounts` | Authenticated (`CUSTOMER`, `MERCHANT`) | Required (`Idempotency-Key`) | Standard | Creates new financial account bound to authenticated principal. |
| `GET` | `/api/v1/accounts/{id}` | Authenticated (Owner Only) | N/A (Safe) | Standard | Fetches account details; enforces IDOR ownership check. |
| `GET` | `/api/v1/accounts/{id}/balance` | Authenticated (Owner Only) | N/A (Safe) | Standard | Returns authoritative settled balance and active reservations. |
| `GET` | `/api/v1/accounts` | Authenticated (`CUSTOMER`, `MERCHANT`) | N/A (Safe) | Standard | Lists accounts owned by authenticated caller with pagination. |

### 2.2 Payments (`PaymentController`)

| Method | Path | Security Classification | Idempotency Support | Rate Limit | Description / Contract |
| :--- | :--- | :--- | :---: | :---: | :--- |
| `POST` | `/api/v1/payments` | Authenticated (`CUSTOMER`, `MERCHANT`) | Required (`Idempotency-Key`) | Critical (Payment) | Executes payment transfer between accounts. Double-entry ledger update. |
| `GET` | `/api/v1/payments/{id}` | Authenticated (Payer / Payee) | N/A (Safe) | Standard | Fetches payment record; enforces double-entity IDOR ownership check. |
| `GET` | `/api/v1/payments` | Authenticated (`CUSTOMER`, `MERCHANT`) | N/A (Safe) | Standard | Lists payments where caller is origin or destination. |

### 2.3 Payouts (`PayoutController`)

| Method | Path | Security Classification | Idempotency Support | Rate Limit | Description / Contract |
| :--- | :--- | :--- | :---: | :---: | :--- |
| `POST` | `/api/v1/payouts` | Authenticated (`CUSTOMER`, `MERCHANT`) | Required (`Idempotency-Key`) | Critical (Payout) | Initiates outbound payout. Deducts active reservation before provider. |
| `GET` | `/api/v1/payouts/{id}` | Authenticated (Origin Owner) | N/A (Safe) | Standard | Returns payout status; enforces origin account ownership check. |
| `GET` | `/api/v1/payouts` | Authenticated (`CUSTOMER`, `MERCHANT`) | N/A (Safe) | Standard | Paginated list of payouts initiated by authenticated user. |

### 2.4 Refunds (`RefundController`)

| Method | Path | Security Classification | Idempotency Support | Rate Limit | Description / Contract |
| :--- | :--- | :--- | :---: | :---: | :--- |
| `POST` | `/api/v1/refunds` | Authenticated (`MERCHANT`, `ADMIN`) | Required (`Idempotency-Key`) | Critical | Issues partial or full refund on settled payment. |
| `GET` | `/api/v1/refunds/{id}` | Authenticated (Party to Payment) | N/A (Safe) | Standard | Retrieves refund details; validated against original transaction parties. |

### 2.5 Reversals & Webhooks (`ReversalController`, `WebhookController`)

| Method | Path | Security Classification | Idempotency Support | Rate Limit | Description / Contract |
| :--- | :--- | :--- | :---: | :---: | :--- |
| `POST` | `/api/v1/reversals` | Authenticated (`ADMIN`, `SYSTEM`) | Required (`Idempotency-Key`) | Standard | Executes system-initiated reversal with reversing ledger transaction. |
| `POST` | `/api/v1/webhooks` | Authenticated (`MERCHANT`, `ADMIN`) | Optional | Standard | Registers webhook endpoint for asynchronous event notifications. |
| `GET` | `/api/v1/webhooks` | Authenticated (`MERCHANT`, `ADMIN`) | N/A (Safe) | Standard | Lists active webhook subscriptions for caller. |

---

## 3. Administrative & Operational Endpoints (`ADMIN`, `SYSTEM` Only)

All administrative endpoints require `@PreAuthorize("hasAnyRole('ADMIN', 'SYSTEM')")`.

| Controller | Method | Path | Security Role | Description |
| :--- | :--- | :--- | :---: | :--- |
| `AdminAccountController` | `GET` | `/api/v1/admin/accounts` | `ADMIN`, `SYSTEM` | Global view of all platform accounts with pagination & filtering. |
| `AdminFreezeController` | `POST` | `/api/v1/admin/accounts/{id}/freeze` | `ADMIN`, `SYSTEM` | Transitions account state to `FROZEN` preventing debits/credits. |
| `AdminFreezeController` | `POST` | `/api/v1/admin/accounts/{id}/unfreeze`| `ADMIN`, `SYSTEM` | Unfreezes account back to `ACTIVE` state. |
| `AdminLedgerController` | `GET` | `/api/v1/admin/ledger/transactions` | `ADMIN`, `SYSTEM` | Queries immutable ledger transactions across system. |
| `AdminLedgerEntryController`| `GET`| `/api/v1/admin/ledger/entries` | `ADMIN`, `SYSTEM` | Queries immutable ledger entries (debit/credit legs). |
| `AdminAdjustmentController`| `POST`| `/api/v1/admin/adjustments` | `ADMIN`, `SYSTEM` | Creates auditable double-entry adjustment with operator reason. |
| `AdminReconciliationController`| `POST`| `/api/v1/admin/reconciliation/run`| `ADMIN`, `SYSTEM` | Manually triggers reconciliation job across external providers. |
| `AdminReconciliationController`| `GET`| `/api/v1/admin/reconciliation/reports`| `ADMIN`, `SYSTEM`| Queries discrepancy reports and reconciliation attempt histories. |
| `AdminAuditController` | `GET` | `/api/v1/admin/audit` | `ADMIN`, `SYSTEM` | Accesses system audit logs (user actions, security events). |
| `AdminAuditSummaryController`| `GET`| `/api/v1/admin/audit/summary` | `ADMIN`, `SYSTEM` | Aggregated compliance and audit metrics. |
| `AdminMetricsController` | `GET` | `/api/v1/admin/metrics` | `ADMIN`, `SYSTEM` | Operational metric snapshots (throughput, error rates). |
| `AdminOutboxController` | `GET` | `/api/v1/admin/outbox/backlog` | `ADMIN`, `SYSTEM` | Monitors pending outbox messages and failure counts. |

---

## 4. Observability & Actuator Endpoints

| Path | Protocol | Access Level | Description |
| :--- | :---: | :---: | :--- |
| `/actuator/health` | HTTP GET | Public (Liveness/Readiness probes) | Returns overall UP/DOWN status; probe details restricted in prod. |
| `/actuator/info` | HTTP GET | Internal / Admin | Git commit metadata, build version, environment. |
| `/actuator/metrics` | HTTP GET | Authenticated (`ADMIN`, `SYSTEM`) | Application runtime micrometer metric keys. |
| `/actuator/prometheus` | HTTP GET | Internal / Scraper Network | Prometheus text format metric exposition. |
