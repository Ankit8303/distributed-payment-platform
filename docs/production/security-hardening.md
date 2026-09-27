# Production Security Hardening Specification

**Document Version**: 1.0  
**Phase**: Phase 17 — Production Hardening  

---

## 1. Overview & Threat Model

The Distributed Payment & Ledger Platform processes financial transactions, account creations, authorizations, captures, refunds, and payouts. Security hardening must guarantee zero unauthenticated access to financial operations, zero credential exposure in Git, logs, or metrics, strict authorization and IDOR isolation, and resilient input filtering.

---

## 2. Authentication & JWT Architecture

- **Token Standard**: JSON Web Token (JWT) using HMAC-SHA256 (`HS256`).
- **Secret Entropy**: Cryptographic key requirement of at least 256 bits (32 bytes). Verified on bean initialization. In production, supplied via environment variable `JWT_SECRET`.
- **Token Expiry**:
  - Access Token: 15 minutes (`900000 ms`). Short-lived to minimize damage from token exfiltration.
  - Refresh Token: 7 days (`604800000 ms`).
- **Refresh Token Storage**: Refresh tokens are cryptographically random UUID strings stored as SHA-256 hashes in PostgreSQL. Raw tokens are never stored in the database.
- **Atomic Refresh Token Rotation**: When a refresh token is used, it is atomically revoked (`revokeByTokenHashIfNotRevoked(tokenHash) == 1`). Replaying an already-consumed refresh token immediately fails with 401 Unauthorized.

---

## 3. Authorization & RBAC Matrix

The platform enforces Role-Based Access Control (RBAC) across four roles:
- `CUSTOMER`: Can create payments, initiate refunds/reversals on owned transactions, view owned accounts and transactions.
- `MERCHANT`: Can receive payments, initiate customer refunds, request payouts from merchant account.
- `ADMIN`: Can access administrative investigation dashboards, freeze/unfreeze accounts with mandatory audit logging, trigger manual reconciliation. Cannot arbitrarily mutate ledger balances.
- `SYSTEM`: Internal automation actor (outbox relay, scheduled reconciliation worker).

### Insecure Direct Object Reference (IDOR) Enforcement
- Endpoints verify resource ownership explicitly in the domain service layer:
  - `PaymentService.getPayment(id, callerUserId, role)` asserts caller is payer, payee, or ADMIN.
  - `AccountService.getAccount(id, ownerId, role)` asserts account belongs to caller or caller is ADMIN/SYSTEM.
  - `RefundService.getRefund(id, callerUserId)` asserts caller is the transaction owner.
  - `WebhookSubscriptionController` enforces `userId == callerUserId`.

---

## 4. HTTP Security Headers & CORS Policy

### 4.1 Security Headers
The following headers are enforced on all HTTP responses via `SecurityConfig`:
- `X-Content-Type-Options: nosniff`: Prevents MIME-type sniffing attacks.
- `X-Frame-Options: DENY`: Prevents clickjacking.
- `Strict-Transport-Security: max-age=31536000; includeSubDomains`: Enforces HTTPS for 1 year.
- `Cache-Control: no-cache, no-store, max-age=0, must-revalidate`: Enforces strict zero-cache policy on sensitive API endpoints.

### 4.2 Cross-Origin Resource Sharing (CORS)
- Production CORS disallows wildcard `*` origins.
- Configured via `CorsConfigurationSource` to accept trusted origin patterns (`https://*.paymentledger.com`, `http://localhost:[*]` in test/dev).
- Permitted methods: `GET, POST, PUT, DELETE, OPTIONS`.
- Permitted headers: `Authorization, Content-Type, Idempotency-Key, X-Correlation-ID, X-Request-ID`.
- Exposed headers: `X-Correlation-ID, X-Request-ID, Retry-After, X-RateLimit-*`.

---

## 5. Input Validation & Request Size Controls

- **Authoritative Currency**: ISO 4217 3-character codes (`USD`, `EUR`, etc.).
- **Authoritative Amounts**: Integer minor units strictly > 0 (`@Min(1)`). Negative or zero amounts rejected with 400 Bad Request.
- **Request Size Quotas**:
  - Multipart uploads: disabled (`spring.servlet.multipart.enabled: false`).
  - Max body size: 2MB (`server.tomcat.max-swallow-size: 2MB`).
  - Max form post size: 2MB (`server.tomcat.max-http-form-post-size: 2MB`).
- **Pagination Protection**: Admin query pages are clamped to `size <= 100` via `PageUtils.clamp(pageable)` to prevent memory exhaustion.

---

## 6. Webhook Destination Security (SSRF Protection)

The `WebhookSecurityValidator` component validates all outbound webhook destination URLs:
- Schemes: Only `http` and `https` permitted. Non-standard schemes (`file://`, `gopher://`, `ftp://`) rejected.
- Host resolution: Resolves all DNS `A`/`AAAA` records before dispatch.
- Blocked ranges:
  - Loopback (`127.0.0.0/8`, `::1`)
  - Cloud Instance Metadata (`169.254.169.254`, `metadata.google.internal`)
  - RFC 1918 Private ranges (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`)
  - Carrier-grade NAT (`100.64.0.0/10`)
  - Link-local (`169.254.0.0/16`)
  - Multicast and broadcast addresses.

---

## 7. Sensitive Data Masking & Error Redaction

- **Log Scrubbing**: Logback pipeline uses `LogMaskingConverter` (composite converter) to sanitize credit card PANs, CVVs, passwords, JWT tokens, refresh tokens, and API keys.
- **RFC 7807 Error Sanitization**:
  - Unhandled exceptions return generic message: `"An unexpected internal server error occurred"`.
  - Database schema details, Hibernate queries, SQL syntax errors, and stack traces are suppressed from client responses.
  - Correlation ID is injected for internal tracing.
