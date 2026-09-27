# API Contract Specification

## 1. REST API Standards & Conventions
- **Protocol**: HTTPS / REST over JSON
- **Base Path**: `/api/v1`
- **Authentication**: `Authorization: Bearer <JWT_ACCESS_TOKEN>`
- **Mandatory Headers**:
  - `X-Correlation-ID`: Client-supplied or server-generated UUID tracing requests end-to-end.
  - `Idempotency-Key`: Mandatory for all state-mutating requests (`POST`, `PUT`).
- **Standard Date/Time**: ISO 8601 UTC string (`YYYY-MM-DDTHH:mm:ss.sssZ`).
- **Standard Monetary Format**: Minor units integer (`amountMinor: long`) and ISO 4217 currency (`currency: string`).

---

## 2. Standard Error Format (RFC 7807)
All error responses adhere to the RFC 7807 `application/problem+json` structure:
```json
{
  "type": "https://api.paymentledger.com/errors/INSUFFICIENT_FUNDS",
  "title": "Insufficient Account Balance",
  "status": 422,
  "detail": "Customer account balance (5000 USD cents) is insufficient for transaction amount (10000 USD cents).",
  "instance": "/api/v1/payments",
  "errorCode": "INSUFFICIENT_FUNDS",
  "correlationId": "c4b3a987-e21b-4f90-8b65-685b882312a0",
  "timestamp": "2026-09-23T15:30:00.000Z",
  "invalidParameters": []
}
```

### Standard Error Codes
| HTTP Status | Error Code | Description |
| :--- | :--- | :--- |
| `400` | `INVALID_PAYLOAD` | Request validation failure (missing fields, negative amount, etc.). |
| `401` | `UNAUTHORIZED` | Missing, expired, or invalid JWT access token. |
| `403` | `FORBIDDEN` | Caller lacks the role or resource ownership required. |
| `404` | `RESOURCE_NOT_FOUND` | Account, payment, refund, or transaction does not exist. |
| `409` | `IDEMPOTENCY_KEY_PAYLOAD_MISMATCH` | Idempotency key previously used with different payload (**Case B**). |
| `409` | `IDEMPOTENCY_CONCURRENT_REQUEST` | Identical idempotency key request currently executing (**Case C**). |
| `422` | `INSUFFICIENT_FUNDS` | Debtor account has insufficient funds for transaction. |
| `422` | `ACCOUNT_FROZEN` | Account is administratively frozen; debits are barred. |
| `422` | `REFUND_EXCEEDS_PAYMENT` | Refund amount exceeds net refundable balance on payment. |
| `429` | `RATE_LIMIT_EXCEEDED` | Request rate exceeds configured token bucket thresholds. |
| `502` | `PROVIDER_UNAVAILABLE` | External gateway unreachable or returned 5xx. |
| `504` | `PROVIDER_TIMEOUT` | External gateway socket timed out; transaction in reconciliation. |

---

## 3. Endpoints

### 3.1 Authentication (`/api/v1/auth`)

#### `POST /api/v1/auth/register`
- **Actor**: Anonymous
- **Request**:
```json
{
  "email": "customer@example.com",
  "password": "StrongPassword123!",
  "role": "CUSTOMER" // "CUSTOMER" or "MERCHANT"
}
```
- **Response**: `201 Created`
```json
{
  "userId": "d290f1ee-6c54-4b01-90e6-d701748f0851",
  "email": "customer@example.com",
  "role": "CUSTOMER",
  "defaultAccountId": "7b8e5c3e-8f24-4f76-9289-53e9cb14c412",
  "createdAt": "2026-09-23T15:00:00.000Z"
}
```

#### `POST /api/v1/auth/login`
- **Actor**: Anonymous
- **Request**: `{ "email": "customer@example.com", "password": "StrongPassword123!" }`
- **Response**: `200 OK`
```json
{
  "accessToken": "eyJhbGciOi...",
  "refreshToken": "d8e371b2...",
  "tokenType": "Bearer",
  "expiresInSeconds": 900
}
```

---

### 3.2 Accounts (`/api/v1/accounts`)

#### `GET /api/v1/accounts/{id}`
- **Actor**: `CUSTOMER`, `MERCHANT` (owner only), `ADMIN`
- **Response**: `200 OK`
```json
{
  "accountId": "7b8e5c3e-8f24-4f76-9289-53e9cb14c412",
  "accountNumber": "ACC-USD-981245",
  "ownerId": "d290f1ee-6c54-4b01-90e6-d701748f0851",
  "accountType": "CUSTOMER",
  "currency": "USD",
  "status": "ACTIVE",
  "createdAt": "2026-09-23T15:00:00.000Z"
}
```

#### `GET /api/v1/accounts/{id}/balance`
- **Actor**: `CUSTOMER`, `MERCHANT` (owner only), `ADMIN`
- **Description**: Computes authoritative balance directly from double-entry ledger history and asserts agreement with cache.
- **Response**: `200 OK`
```json
{
  "accountId": "7b8e5c3e-8f24-4f76-9289-53e9cb14c412",
  "currency": "USD",
  "balanceMinor": 15000, // $150.00
  "availableBalanceMinor": 15000,
  "asOf": "2026-09-23T15:35:00.000Z"
}
```

#### `GET /api/v1/accounts/{id}/transactions`
- **Actor**: `CUSTOMER`, `MERCHANT` (owner only), `ADMIN`
- **Query Params**: `page=0&size=20&sort=createdAt,desc`
- **Response**: `200 OK` (Paginated list of immutable ledger entries).

---

### 3.3 Payments (`/api/v1/payments`)

#### `POST /api/v1/payments`
- **Actor**: `CUSTOMER`
- **Headers**:
  - `Idempotency-Key`: `string` (UUID or client-unique key)
  - `X-Correlation-ID`: `string` (UUID)
- **Request**:
```json
{
  "payeeAccountId": "8f3e2b1a-4c5d-6e7f-8a9b-0c1d2e3f4a5b",
  "amountMinor": 5000, // $50.00
  "currency": "USD",
  "paymentMethodToken": "tok_visa_4242"
}
```
- **Responses**:
  - `201 Created`: Payment confirmed and ledger settled.
```json
{
  "paymentId": "5e1a3b8c-9d2e-4f7a-8b1c-3d5e7f9a1b3c",
  "idempotencyKey": "9a8b7c6d-5e4f-3a2b-1c0d-e9f8a7b6c5d4",
  "payerAccountId": "7b8e5c3e-8f24-4f76-9289-53e9cb14c412",
  "payeeAccountId": "8f3e2b1a-4c5d-6e7f-8a9b-0c1d2e3f4a5b",
  "amountMinor": 5000,
  "feeAmountMinor": 150,
  "currency": "USD",
  "status": "SETTLED",
  "providerReference": "ch_3MtwL2LkdIwHu7ix0snN00fn",
  "ledgerTransactionId": "6a2b4c8e-0f1a-3b5d-7e9c-1a3b5d7e9c1a",
  "correlationId": "c4b3a987-e21b-4f90-8b65-685b882312a0",
  "createdAt": "2026-09-23T15:40:00.000Z"
}
```
  - `202 Accepted`: Network timeout during capture call; transitioned to `PENDING_RECONCILIATION`.
```json
{
  "paymentId": "5e1a3b8c-9d2e-4f7a-8b1c-3d5e7f9a1b3c",
  "status": "PENDING_RECONCILIATION",
  "message": "Transaction state indeterminate due to gateway timeout. Reconciliation active.",
  "pollUrl": "/api/v1/payments/5e1a3b8c-9d2e-4f7a-8b1c-3d5e7f9a1b3c"
}
```
  - `409 Conflict`:
    - `IDEMPOTENCY_CONCURRENT_REQUEST`: Returned when a concurrent request with the same idempotency key is already actively executing (e.g., 20 simultaneous identical requests produce 1 × 201/202 and 19 × 409).
    - `IDEMPOTENCY_KEY_PAYLOAD_MISMATCH`: Returned when a request uses an idempotency key that was already registered with a different payload.

##### Idempotency & Crash-Recovery Contract
1. **Concurrent Simultaneous Requests (Case C)**:
   - For $N$ simultaneous requests with the same actor, operation, idempotency key, and identical payload:
     - Exactly **1 request** acquires the idempotency lock (`IN_PROGRESS`) and proceeds to settlement.
     - The remaining **$N - 1$ requests** receive `409 Conflict` (`IDEMPOTENCY_CONCURRENT_REQUEST`).
     - No duplicate payments, external provider charges, or ledger entries can occur.
2. **Completed Idempotent Replay (Case A)**:
   - Once the operation transitions to `COMPLETED`, subsequent requests with the same key and same payload return the cached replay response (`201 Created` or `202 Accepted`) with identical resource ID.
3. **Payload Mismatch (Case B)**:
   - Requests reusing an existing key with a mismatched payload immediately fail with `409 Conflict` (`IDEMPOTENCY_KEY_PAYLOAD_MISMATCH`).
4. **Crash-Recovery Matrix (`idempotency_record.status = IN_PROGRESS`)**:
   - If an application crashes or restarts while an idempotency record is `IN_PROGRESS` and the client retries with the same key and payload:
     | Payment State | Retry Allowed | Provider Invocation | Payment Reused | Record Final Status | HTTP Status | Response Replayed | Reconciliation Needed |
     | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
     | `CREATED` | Yes | **NO** | Yes | `COMPLETED` | `202 Accepted` | Yes (`PENDING_RECONCILIATION`) | **Yes** |
     | `AUTHORIZING` | Yes | **NO** | Yes | `COMPLETED` | `202 Accepted` | Yes (`PENDING_RECONCILIATION`) | **Yes** |
     | `AUTHORIZED` | Yes | **NO** | Yes | `COMPLETED` | `202 Accepted` | Yes (`PENDING_RECONCILIATION`) | **Yes** |
     | `CAPTURING` | Yes | **NO** | Yes | `COMPLETED` | `202 Accepted` | Yes (`PENDING_RECONCILIATION`) | **Yes** |
     | `SETTLED` | Yes | **NO** | Yes | `COMPLETED` | `201 Created` | Yes (cached `SETTLED`) | No |
     | `PENDING_RECONCILIATION` | Yes | **NO** | Yes | `COMPLETED` | `202 Accepted` | Yes (cached `PENDING_RECONCILIATION`) | **Yes** |
     | `DECLINED` | Yes | **NO** | Yes | `FAILED` | `400 Bad Request` | Yes (cached error) | No |
     | `FAILED` | Yes | **NO** | Yes | `FAILED` | `400 Bad Request` | Yes (cached error) | No |
   - **Invariants**:
     - The external provider is **never** invoked a second time merely because an idempotency record was `IN_PROGRESS`.
     - Ambiguous in-flight states are never guessed; they transition deterministically to `PENDING_RECONCILIATION`.
     - Zero duplicate ledger postings or duplicate payment entities can ever be generated.

#### `GET /api/v1/payments/{id}`
- **Actor**: `CUSTOMER` (payer), `MERCHANT` (payee), `ADMIN`
- **Response**: `200 OK` (Current payment state and ledger links).

---

### 3.4 Refunds (`/api/v1/refunds`)

#### `POST /api/v1/payments/{paymentId}/refunds`
- **Actor**: `MERCHANT` (payee owner of original payment)
- **Headers**: `Idempotency-Key: string`, `X-Correlation-ID: string`
- **Request**:
```json
{
  "amountMinor": 2500, // Partial refund: $25.00
  "reason": "Customer returned partial merchandise"
}
```
- **Response**: `201 Created`
```json
{
  "refundId": "1b2c3d4e-5f6a-7b8c-9d0e-1f2a3b4c5d6e",
  "paymentId": "5e1a3b8c-9d2e-4f7a-8b1c-3d5e7f9a1b3c",
  "amountMinor": 2500,
  "currency": "USD",
  "status": "SETTLED",
  "providerReference": "re_3MtwL2LkdIwHu7ix0xyz00fn",
  "compensatingLedgerTransactionId": "8b9c0d1e-2f3a-4b5c-6d7e-8f9a0b1c2d3e",
  "createdAt": "2026-09-23T15:45:00.000Z"
}
```

---

### 3.5 Admin Operations (`/api/v1/admin`)

#### `POST /api/v1/admin/accounts/{id}/freeze`
- **Actor**: `ADMIN`
- **Request**: `{ "reason": "Suspected unauthorized access under investigation" }`
- **Response**: `200 OK` `{ "accountId": "...", "status": "FROZEN", "auditLogId": "..." }`

#### `POST /api/v1/admin/accounts/{id}/unfreeze`
- **Actor**: `ADMIN`
- **Request**: `{ "reason": "Customer identity verified; security clearance granted" }`
- **Response**: `200 OK` `{ "accountId": "...", "status": "ACTIVE", "auditLogId": "..." }`

#### `GET /api/v1/admin/audit-logs`
- **Actor**: `ADMIN`
- **Query Params**: `entityType=ACCOUNT&entityId=...&page=0&size=50`
- **Response**: `200 OK` (Paginated list of immutable audit trails).

---

### 3.6 Reconciliation (`/api/v1/reconciliation`)

#### `POST /api/v1/reconciliation/runs`
- **Actor**: `ADMIN`, `SYSTEM`
- **Request**:
```json
{
  "providerName": "STRIPE",
  "windowStart": "2026-09-22T00:00:00.000Z",
  "windowEnd": "2026-09-22T23:59:59.999Z"
}
```
- **Response**: `202 Accepted` `{ "runId": "...", "status": "RUNNING" }`

#### `GET /api/v1/reconciliation/differences`
- **Actor**: `ADMIN`
- **Query Params**: `runId=...&status=OPEN`
- **Response**: `200 OK` (List of categorized discrepancies).

#### `POST /api/v1/reconciliation/differences/{id}/resolve`
- **Actor**: `ADMIN`
- **Request**:
```json
{
  "reason": "Settlement fee adjustment from provider",
  "resolutionType": "POST_COMPENSATING_ADJUSTMENT",
  "counterpartyAccountId": "9c8b7a6f-5e4d-3c2b-1a0f-e9d8c7b6a5f4"
}
```
- **Response**: `200 OK` `{ "differenceId": "...", "status": "RESOLVED", "compensatingTransactionId": "..." }`
- **Invariant**: Posts an explicit `SYSTEM_ADJUSTMENT` double-entry ledger transaction. Does NOT mutate or delete historical records.
