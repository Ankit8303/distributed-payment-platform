# Security Model & Threat Model Specification

## 1. Security Architecture Principles
The security model enforces defense-in-depth across transport, network, authentication, authorization, data persistence, and administrative boundaries. Zero trust is assumed at all network layers.

Core security rules:
- Authentication and authorization are validated at every protected boundary; frontend claims or roles are never trusted.
- Resource-level ownership checks (IDOR defense) are mandatory for all customer and merchant data access.
- Administrators possess operational oversight but CANNOT arbitrarily edit account balances or delete financial history.
- Passwords, cryptographic keys, card data, and raw authentication tokens are never logged or stored in Git.

---

## 2. Authentication & Credential Management
- **Password Security**: Passwords hashed using Argon2id (memory: 64MB, iterations: 3, parallelism: 1) or BCrypt (cost factor $\ge 12$). Minimum password length: 12 characters with complexity rules.
- **JWT Architecture**:
  - Stateless Access Token: Signed via asymmetric RSA (RS256) or HMAC-SHA256 with high-entropy secret. Short-lived (15 minutes). Contains `sub` (userId), `role`, `iat`, `exp`.
  - Refresh Token: High-entropy cryptographically random UUID stored in database with hashed token value. 7-day TTL with single-use rotation.
- **Secret Management**:
  - Secrets injected strictly via container environment variables or external secrets managers. Zero hardcoded secrets in codebase or configuration files.

---

## 3. Role-Based Access Control (RBAC) & Ownership Matrix

| Endpoint / Action | `CUSTOMER` | `MERCHANT` | `ADMIN` | Authorization Logic |
| :--- | :--- | :--- | :--- | :--- |
| `POST /api/v1/payments` | **ALLOWED** | Forbidden | Forbidden | Caller must be owner of `payerAccountId`. |
| `GET /api/v1/accounts/{id}` | Own Account Only | Own Account Only | **ALL** | Verifies `account.ownerId == currentUser.id` (or `ROLE_ADMIN`). |
| `GET /api/v1/accounts/{id}/balance` | Own Account Only | Own Account Only | **ALL** | Verifies ownership. Returns ledger-derived balance. |
| `POST /api/v1/payments/{id}/refunds` | Forbidden | Own Payee Only | Forbidden | Verifies `payment.payeeAccountId.ownerId == currentUser.id`. |
| `POST /api/v1/admin/accounts/{id}/freeze` | Forbidden | Forbidden | **ALLOWED** | Requires `ROLE_ADMIN` + mandatory reason string. |
| `POST /api/v1/admin/accounts/{id}/unfreeze`| Forbidden | Forbidden | **ALLOWED** | Requires `ROLE_ADMIN` + mandatory reason string. |
| `PUT /api/v1/accounts/{id}/balance` | **FORBIDDEN** | **FORBIDDEN** | **FORBIDDEN** | **Endpoint does NOT exist**. Direct balance mutation barred. |
| `POST /api/v1/reconciliation/runs` | Forbidden | Forbidden | **ALLOWED** | Requires `ROLE_ADMIN`. |
| `GET /api/v1/admin/audit-logs` | Forbidden | Forbidden | **ALLOWED** | Requires `ROLE_ADMIN`. |

---

## 4. Rate Limiting & Webhook Verification
- **Rate Limiting**:
  - Implemented via Redis Token Bucket / Sliding Window.
  - Per-IP rate limiting on public endpoints: 10 req/min on `/auth/login` and `/auth/register`.
  - Per-User rate limiting on `/payments`: 60 req/min.
- **Webhook Cryptographic Verification**:
  - Inbound webhooks must supply an HMAC-SHA256 signature in HTTP headers (`X-Signature-SHA256`).
  - Signature computed over raw request body + timestamp header using shared secret.
  - Constant-time comparison (`MessageDigest.isEqual`) used to defeat timing attacks.
  - Timestamp freshness check: requests with timestamps drifting $> 300$ seconds from server clock are rejected to prevent replay attacks.

---

## 5. Comprehensive Threat Model

### Threat 1: Credential Stuffing & Brute Force
- **Attack**: Automated botnet submits thousands of breached username/password combinations.
- **Impact**: Account takeover, unauthorized payment initiation.
- **Technical Mitigation**: IP-based and account-based rate limiting via Redis; account lockout after 5 consecutive failed attempts; CAPTCHA integration hook.
- **Detection**: Metric alert on sudden spike in HTTP 401 responses.
- **Verification Test**: Automated test firing 20 bad logins within 10 seconds asserts `429 Too Many Requests` after threshold.

### Threat 2: Insecure Direct Object References (IDOR)
- **Attack**: Authenticated Customer A calls `GET /api/v1/accounts/{accountB_id}` or `GET /api/v1/payments/{paymentB_id}`.
- **Impact**: Unauthorized data disclosure of competing customer balances or transaction histories.
- **Technical Mitigation**: Method-level security annotating controllers with `@PreAuthorize("@accountSecurity.isOwner(#id)")`; repository queries filter by `WHERE id = :id AND owner_id = :currentUserId`.
- **Detection**: Log warning on security authorization rejection.
- **Verification Test**: Security test asserting Customer A receives `403 Forbidden` or `404 Not Found` when requesting Customer B's account ID.

### Threat 3: Privilege Escalation
- **Attack**: Attacker tampers with registration payload or JWT to inject `ROLE_ADMIN` or modify user status.
- **Impact**: Unauthorized access to administrative freezes, reconciliation overrides, and audit trails.
- **Technical Mitigation**: Registration endpoint strictly accepts role enumeration for non-privileged roles (`CUSTOMER`, `MERCHANT`); `ADMIN` accounts can only be provisioned via database seed scripts. JWT signatures strictly validated using server-side secret.
- **Detection**: Audit log trigger on new admin creation attempts.
- **Verification Test**: Submitting `{"role": "ADMIN"}` on `/auth/register` throws `400 Bad Request` or sets default role.

### Threat 4: Replay Attacks (Payment & Webhooks)
- **Attack**: Attacker intercepts a valid payment or webhook POST and replays it over the network.
- **Impact**: Duplicate payment deduction or repeated event processing.
- **Technical Mitigation**: Mandatory `Idempotency-Key` scoped to actor; webhooks enforce timestamp freshness window ($\le 300$s) and HMAC signatures.
- **Detection**: Metric tracking duplicate idempotency key hits.
- **Verification Test**: Replaying exact raw HTTP payload returns cached response (payments) or 400 Expired (webhooks).

### Threat 5: Duplicate Payments via Rapid Double-Clicking
- **Attack**: User double-clicks checkout button, sending identical simultaneous requests.
- **Impact**: Duplicate charges to user card; multi-credit ledger entries.
- **Technical Mitigation**: PostgreSQL unique constraint on `(actor_id, operation, idempotency_key)`. Concurrent thread rejected with `409 Conflict`.
- **Detection**: Log message on `IdempotencyConcurrentException`.
- **Verification Test**: Multi-threaded concurrency test with 50 threads asserting exactly 1 payment created.

### Threat 6: Refund Abuse (Over-Refunding)
- **Attack**: Merchant submits multiple concurrent partial refunds exceeding original payment total.
- **Impact**: Capital loss; merchant drains platform clearing funds.
- **Technical Mitigation**: Pessimistic row locking on parent `Payment` row; cumulative refund check $\sum \text{Refunds} \le \text{payment.amountMinor}$.
- **Detection**: Alert on `RefundAmountExceedsPaymentException`.
- **Verification Test**: Concurrency test attempting two $60 refunds on a $100 payment simultaneously. Exactly one succeeds; second fails.

### Threat 7: Webhook Spoofing
- **Attack**: Attacker sends fake HTTP POST to `/api/v1/webhooks/stripe` claiming a payment succeeded.
- **Impact**: False settlement of unpaid orders in the ledger.
- **Technical Mitigation**: Cryptographic HMAC-SHA256 signature verification over raw request bytes; verify against secret key; check provider event deduplication table.
- **Detection**: Security log on signature mismatch.
- **Verification Test**: Webhook call with forged signature returns `401 Unauthorized`.

### Threat 8: Event Replay in Kafka
- **Attack**: Kafka consumer replays previously processed partitions after rebalance.
- **Impact**: Duplicate notifications or double-processing of downstream side effects.
- **Technical Mitigation**: Idempotent consumers with database-backed `consumed_messages` deduplication table.
- **Detection**: Metric counter for skipped duplicate events.
- **Verification Test**: Consumer test injecting identical Kafka message twice verifies business logic executes once.

### Threat 9: Event Poisoning / Tampering
- **Attack**: Attacker attempts to publish malicious or malformed JSON directly to Kafka topics.
- **Impact**: Consumer crashes, data corruption, buffer overflows.
- **Technical Mitigation**: Kafka broker ACLs restricting publish permissions to outbox relay service; JSON schema validation in consumer deserializers; poison messages routed to DLQ.
- **Detection**: Alert on DLQ message count $> 0$.
- **Verification Test**: Invalid JSON sent to Kafka topic is safely moved to DLQ without crashing consumer loop.

### Threat 10: Secret & Credential Leakage
- **Attack**: Database credentials, JWT secrets, or provider API keys committed to source control or dumped in heap dumps.
- **Impact**: Complete infrastructure compromise.
- **Technical Mitigation**: Environment variable injection; `.gitignore` enforcement; CI scanning via GitGuardian/TruffleHog.
- **Detection**: CI automated secret detection step fails build.
- **Verification Test**: CI pipeline verifies no tracked file contains API keys or passwords.

### Threat 11: Sensitive Log Leakage
- **Attack**: Card numbers, security codes, JWT tokens, or passwords printed in application logs.
- **Impact**: Violation of privacy regulations, credential theft via log aggregators.
- **Technical Mitigation**: Logback regex masking filters for `password`, `token`, `cardNumber`, `cvv`; DTOs implement custom `toString()` omitting sensitive fields.
- **Detection**: Automated log scanning in CI.
- **Verification Test**: Logging a payment request verifies `token` or `password` appears as `***MASKED***`.

### Threat 12: Rate-Limit Bypass via Header Spoofing
- **Attack**: Attacker rotates `X-Forwarded-For` header to bypass IP rate limits.
- **Impact**: DoS on authentication endpoints, unhindered brute force.
- **Technical Mitigation**: Configure Spring / reverse proxy to trust only designated upstream proxy IP headers; fallback to socket remote address.
- **Detection**: Alert on rapid IP rotation from single subnet.
- **Verification Test**: Sending spoofed `X-Forwarded-For` from untrusted proxy is ignored.

### Threat 13: Malicious Payloads & SQL Injection
- **Attack**: Attacker injects SQL fragments or script tags into description, account number, or reason fields.
- **Impact**: Database compromise, data corruption.
- **Technical Mitigation**: Exclusively parameterized SQL queries via Spring Data JPA / Hibernate; Jakarta validation annotations (`@Pattern`, `@Size`) on all inputs.
- **Detection**: Database syntax error logs.
- **Verification Test**: Passing `' OR 1=1; --` in account description stores literal string without SQL injection.

### Threat 14: Concurrent Double-Spend Race Condition
- **Attack**: Customer initiates two simultaneous payments of $100 when their balance is only $100.
- **Impact**: Customer balance drops to -$100; negative balance invariant violated.
- **Technical Mitigation**: Pessimistic row locking (`SELECT ... FOR UPDATE`) on the debtor `accounts` row before checking balance and creating ledger entries.
- **Detection**: Log trace on row lock acquisition.
- **Verification Test**: Concurrency test with 10 simultaneous threads attempting to spend $100 against a $100 balance. Exactly one succeeds; nine fail with `INSUFFICIENT_FUNDS`.
