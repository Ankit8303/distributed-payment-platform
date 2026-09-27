# Authorization Matrix & Access Control Policy

**Repository:** `Ankit8303/distributed-payment-platform`  
**Security Standard:** OWASP Top 10 API Security (BOLA/BFLA Prevention)  
**Authentication Standard:** Stateless JWT (Bearer RFC 6750)  
**Role Hierarchy:** `ADMIN`, `SYSTEM`, `MERCHANT`, `CUSTOMER`

---

## 1. Role Definitions

| Role | Description | Principle of Least Privilege Boundary |
| :--- | :--- | :--- |
| **ANONYMOUS** | Unauthenticated callers | Only `/api/v1/auth/*` and non-sensitive health probes (`/actuator/health`) |
| **CUSTOMER** | Retail individual account owners | Access restricted strictly to owned accounts, initiated payments, and authorized payouts |
| **MERCHANT** | Commercial entities | Access to business accounts, payment reception, refund issuance, webhook setup |
| **ADMIN** | Human operators | Operational oversight, account freezing, manual reconciliation, dispute handling |
| **SYSTEM** | Automated daemons & schedulers | Outbox relay, reconciliation worker, asynchronous notification dispatch |

---

## 2. Comprehensive Endpoint Authorization Matrix

| Endpoint | Method | Path | CUSTOMER | MERCHANT | ADMIN | SYSTEM | Access Rationale & Enforcement Mechanism |
| :--- | :--- | :--- | :---: | :---: | :---: | :---: | :--- |
| **Register** | POST | `/api/v1/auth/register` | ALLOW | ALLOW | ALLOW | ALLOW | Public user registration |
| **Login** | POST | `/api/v1/auth/login` | ALLOW | ALLOW | ALLOW | ALLOW | Public credential authentication |
| **Refresh Token** | POST | `/api/v1/auth/refresh` | ALLOW | ALLOW | ALLOW | ALLOW | Public token rotation with cryptographically verified refresh token |
| **Create Account** | POST | `/api/v1/accounts` | ALLOW | ALLOW | DENY | DENY | Principal-bound owner assignment; admins use admin creation endpoint |
| **Get Account** | GET | `/api/v1/accounts/{id}` | OWN ONLY | OWN ONLY | ALLOW (Admin API) | ALLOW (Admin API) | **IDOR Guard:** Ownership check: `account.getOwnerId().equals(callerId)`. Non-owners receive HTTP 404/403. |
| **Get Account Balance** | GET | `/api/v1/accounts/{id}/balance` | OWN ONLY | OWN ONLY | ALLOW (Admin API) | ALLOW (Admin API) | **IDOR Guard:** Verifies authenticated principal matches account owner. |
| **List My Accounts** | GET | `/api/v1/accounts` | ALLOW | ALLOW | ALLOW | ALLOW | Scoped strictly to authenticated caller: `findByOwnerId(callerId)`. |
| **Create Payment** | POST | `/api/v1/payments` | ALLOW | ALLOW | DENY | DENY | Origin account must belong to authenticated user; idempotency enforced. |
| **Get Payment** | GET | `/api/v1/payments/{id}` | OWN ONLY | OWN ONLY | ALLOW (Admin API) | ALLOW (Admin API) | **IDOR Guard:** Caller must be owner of payer account OR payee account. |
| **Create Payout** | POST | `/api/v1/payouts` | ALLOW | ALLOW | DENY | DENY | Origin account must belong to authenticated caller; active reservation deducted. |
| **Get Payout** | GET | `/api/v1/payouts/{id}` | OWN ONLY | OWN ONLY | ALLOW (Admin API) | ALLOW (Admin API) | **IDOR Guard:** Payout origin account owner verification. |
| **List My Payouts** | GET | `/api/v1/payouts` | ALLOW | ALLOW | ALLOW | ALLOW | Scoped query by authenticated caller's account ID. |
| **Create Refund** | POST | `/api/v1/refunds` | DENY | OWN ONLY | ALLOW (Admin API) | DENY | Only payee merchant can initiate refund on settled payment. |
| **Get Refund** | GET | `/api/v1/refunds/{id}` | OWN ONLY | OWN ONLY | ALLOW (Admin API) | ALLOW (Admin API) | Restricted to original transaction participants. |
| **Create Reversal** | POST | `/api/v1/reversals` | DENY | DENY | ALLOW (Admin API) | ALLOW | Systemic or administrative transaction reversals. |
| **Subscribe Webhook** | POST | `/api/v1/webhooks` | ALLOW | ALLOW | ALLOW | ALLOW | Webhook subscription tied to principal. |
| **Admin Accounts** | ALL | `/api/v1/admin/accounts/**` | DENY | DENY | ALLOW | ALLOW | `@PreAuthorize("hasAnyRole('ADMIN', 'SYSTEM')")` |
| **Admin Freeze** | POST | `/api/v1/admin/accounts/{id}/freeze` | DENY | DENY | ALLOW | ALLOW | `@PreAuthorize("hasAnyRole('ADMIN', 'SYSTEM')")` |
| **Admin Ledger** | ALL | `/api/v1/admin/ledger/**` | DENY | DENY | ALLOW | ALLOW | `@PreAuthorize("hasAnyRole('ADMIN', 'SYSTEM')")` |
| **Admin Adjustments** | POST | `/api/v1/admin/adjustments` | DENY | DENY | ALLOW | ALLOW | `@PreAuthorize("hasAnyRole('ADMIN', 'SYSTEM')")` |
| **Admin Reconcile** | ALL | `/api/v1/admin/reconciliation/**` | DENY | DENY | ALLOW | ALLOW | `@PreAuthorize("hasAnyRole('ADMIN', 'SYSTEM')")` |
| **Admin Audit** | ALL | `/api/v1/admin/audit/**` | DENY | DENY | ALLOW | ALLOW | `@PreAuthorize("hasAnyRole('ADMIN', 'SYSTEM')")` |
| **Actuator Health** | GET | `/actuator/health` | ALLOW | ALLOW | ALLOW | ALLOW | Public liveness probe |
| **Actuator Metrics** | GET | `/actuator/metrics/**` | DENY | DENY | ALLOW | ALLOW | Requires authenticated administrative credentials |
| **Prometheus Metrics**| GET | `/actuator/prometheus` | DENY | DENY | ALLOW | ALLOW | Scrape endpoint restricted to internal network / admin role |

---

## 3. IDOR / BOLA Prevention Architecture

1. **No Client-Supplied Principal Authority:**  
   The application never accepts `userId`, `ownerId`, or `role` from HTTP request bodies or query parameters. Identity is derived strictly from the cryptographically validated JWT `SecurityContextHolder.getContext().getAuthentication().getName()`.
2. **Double-Entity Ownership Verification:**  
   For multi-party resources (such as payments), both payer and payee entities are checked:
   ```java
   boolean isOwner = (payer != null && payer.getOwnerId().equals(callerId)) ||
                     (payee != null && payee.getOwnerId().equals(callerId));
   if (!isOwner) {
       throw new PaymentNotFoundException("Payment not found or access denied");
   }
   ```
3. **Information Disclosure Prevention:**  
   When an unauthorized actor attempts to query a resource belonging to another entity, the API returns HTTP 404 (or HTTP 403 where applicable) to prevent ID enumeration.
