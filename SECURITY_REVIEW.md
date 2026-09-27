# Comprehensive Security Review & Threat Model

**Repository:** `Ankit8303/distributed-payment-platform`  
**Security Standard:** OWASP Top 10 API Security & CWE Top 25  
**Review Type:** Staff Engineering Architecture & Static Analysis Audit  
**Date:** September 2026

---

## 1. Executive Summary

A comprehensive security audit of the backend application was conducted across all controllers, service boundaries, database access paths, and authentication/authorization filters. Special emphasis was placed on OWASP API Security Top 10 threats, specifically Broken Object Level Authorization (BOLA/IDOR), Broken Function Level Authorization (BFLA), Unrestricted Resource Consumption, and Credential/Token exposure.

All critical attack scenarios have verified automated integration tests in the test suite (`SecurityIntegrationTest`, `Phase6HardeningIntegrationTest`, `Phase7HardeningIntegrationTest`, `Phase11FinancialOperationsIntegrationTest`).

---

## 2. OWASP API Security Top 10 Evaluation & Findings

### API1:2023 - Broken Object Level Authorization (BOLA / IDOR)
- **Status:** **PASS / MITIGATED**
- **Affected Surface:** `/api/v1/accounts/{id}`, `/api/v1/payments/{id}`, `/api/v1/payouts/{id}`, `/api/v1/refunds/{id}`
- **Attack Scenario:** Attacker authenticates as Customer A and issues `GET /api/v1/accounts/{id_of_customer_b}` or `GET /api/v1/payouts/{id_of_customer_b}` attempting to leak financial balances or beneficiary banking coordinates.
- **Engineered Control:** Every lookup derives caller identity from `SecurityContextHolder` JWT principal and compares against `account.getOwnerId()` or payment counterparty IDs (`payer.getOwnerId()` / `payee.getOwnerId()`). If mismatch occurs, access is rejected with HTTP 404/403.
- **Verification Evidence:** `Phase6HardeningIntegrationTest` and `SecurityIntegrationTest` explicitly execute cross-user queries and assert access is denied.

### API2:2023 - Broken Authentication
- **Status:** **PASS / MITIGATED**
- **Affected Surface:** `/api/v1/auth/login`, `/api/v1/auth/refresh`, JWT filter chain
- **Attack Scenario:** Attacker submits expired tokens, forged signature JWTs, or brute-forces user passwords.
- **Engineered Control:**
  - Passwords hashed using standard BCrypt algorithm with secure salt.
  - Stateless HMAC-SHA256 JWT validation checking expiration, subject, and issuer.
  - Refresh tokens are cryptographically generated and stored with revocable status.
  - Sensitive credentials never written to log files (`@ToString.Exclude` and log sanitization).
- **Verification Evidence:** `SecurityIntegrationTest` validates expired token rejection, tampered signature rejection, and authentication failure codes.

### API3:2023 - Broken Object Property Level Authorization
- **Status:** **PASS / MITIGATED**
- **Affected Surface:** Account and Payment Creation DTOs (`CreatePaymentRequest`, `CreatePayoutRequest`)
- **Attack Scenario:** Attacker injects unauthorized fields (e.g. `balance`, `role`, `status: 'SETTLED'`) into JSON payload to artificially credit funds.
- **Engineered Control:** Strict DTO segregation. Entity models are never directly bound from HTTP bodies. Jackson rejects unknown fields; internal financial states (`SETTLED`, `ACTIVE`) can only be set by transactional service logic.
- **Verification Evidence:** Unit tests for request DTO deserialization and validation.

### API4:2023 - Unrestricted Resource Consumption
- **Status:** **PASS / MITIGATED**
- **Affected Surface:** Financial endpoints, collection pagination, body size
- **Attack Scenario:** Attacker floods `/api/v1/payments` with 10,000 requests/sec or requests `?pageSize=10000000` to exhaust JVM memory or connection pool.
- **Engineered Control:**
  - Redis sliding-window distributed rate limiting (`RedisRateLimitingFilter`).
  - Strict pagination boundaries: `PageUtils` enforces maximum page size cap (`MAX_PAGE_SIZE = 100`).
  - Spring Boot Tomcat maximum request payload size enforced (10MB limit).
- **Verification Evidence:** `RedisAuxiliaryIntegrationTest` and `PageUtilsTest`.

### API5:2023 - Broken Function Level Authorization (BFLA)
- **Status:** **PASS / MITIGATED**
- **Affected Surface:** Administrative endpoints `/api/v1/admin/**`
- **Attack Scenario:** Regular customer calls `POST /api/v1/admin/accounts/{id}/freeze` or `POST /api/v1/admin/adjustments` to manipulate platform ledger.
- **Engineered Control:** Spring Method Security (`@EnableMethodSecurity`) enforces `@PreAuthorize("hasAnyRole('ADMIN', 'SYSTEM')")` on all admin controller classes and methods. Non-admin tokens yield HTTP 403 Forbidden.
- **Verification Evidence:** `SecurityIntegrationTest` asserts Customer and Merchant roles receive 403 on all `/admin/*` paths.

### API6:2023 - Unrestricted Access to Sensitive Business Flows
- **Status:** **PASS / MITIGATED**
- **Affected Surface:** Money movement endpoints (Payments, Payouts, Refunds)
- **Attack Scenario:** Automated bot scripts rapid-fire duplicate payout requests to exploit race conditions before ledger balances settle.
- **Engineered Control:**
  - Mandatory `Idempotency-Key` header with database-backed atomic reservations (`uk_idempotency_key_scope`).
  - Payout reservation pattern (B5.1) deducts active reservations immediately in DB transaction 1.
  - Lexicographical account locking prevents deadlock and double-spend concurrency.
- **Verification Evidence:** Concurrency tests in `Phase11FinancialOperationsIntegrationTest` and `Phase15ComprehensiveVerificationIntegrationTest`.

### API7:2023 - Server Side Request Forgery (SSRF)
- **Status:** **PASS / MITIGATED**
- **Affected Surface:** External provider integration and Webhook dispatch
- **Attack Scenario:** Attacker specifies an internal IP (e.g., `http://169.254.169.254` or `http://localhost:5432`) as a webhook destination or gateway URL to probe internal network infrastructure.
- **Engineered Control:** Provider URLs are hardcoded in application configuration (`application.yml`) and cannot be overridden by client requests. Webhook URLs are validated against localhost and private IPv4 ranges (RFC 1918).
- **Verification Evidence:** Webhook URL validator unit and integration tests.

### API8:2023 - Security Misconfiguration
- **Status:** **PASS / MITIGATED**
- **Affected Surface:** Actuator endpoints, error responses, HTTP security headers
- **Attack Scenario:** Attacker probes `/actuator/env` to extract database credentials or triggers 500 error to inspect raw stack traces and SQL queries.
- **Engineered Control:**
  - Sensitive actuator endpoints disabled or restricted to `ADMIN`.
  - Global exception handler maps exceptions to sanitized `ApiErrorResponse` without exposing stack traces or database table structures.
  - Security headers configured: `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Strict-Transport-Security`.
- **Verification Evidence:** `verify-ci.ps1` Gate 4 and Actuator security tests.

---

## 3. Secret Detection & Static Code Analysis Findings

| Gate | Target Scanned | Findings | Status |
| :--- | :--- | :--- | :---: |
| **Gate B: Secret Detection** | Entire repository git history & files | 0 hardcoded credentials, 0 private keys, 0 production API tokens | **PASS** |
| **Dependency CVE Scan** | Maven `pom.xml` dependencies | Spring Boot 3.3.4, Java 21, JJWT 0.12.6, Testcontainers 1.20.1 | **PASS** |
| **Database Constraints** | Flyway migrations `V1` to `V12` | Schema-level checks, immutable ledger trigger, strict foreign keys | **PASS** |

---

## 4. Verification Conclusion

The platform complies with standard financial backend security requirements and OWASP API Security Top 10 recommendations. No critical or high security blockers exist in the codebase.
