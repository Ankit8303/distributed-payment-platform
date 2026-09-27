# Security Architecture & Implemented Controls Summary

**Governing Phase**: Phase 21 — Flagship Portfolio Packaging & GitHub Release  
**Domain**: Application Security, DevSecOps & Defensive Architecture  
**Status**: VERIFIED IMPLEMENTED CONTROLS  

---

## 1. Security Philosophy & Threat Model

The Distributed Payment & Ledger Platform processes financial transactions and sensitive customer metadata. The security architecture enforces **defense-in-depth**:
- Zero trust between layers: authentication is verified on every request, method-level security enforces RBAC, and resource ownership checks prevent IDOR.
- Zero secrets committed: all credentials must be injected at runtime via environment variables.
- Zero unconstrained inputs: all payloads are validated with strict Bean Validation and request body size limits.

---

## 2. Implemented Security Controls Catalog

### 2.1 Authentication & Token Lifecycle
- **Password Hashing**: BCrypt strength 12 (`BCryptPasswordEncoder(12)`) protects user passwords against offline brute-force attacks.
- **Stateless Access Tokens**: Short-lived JSON Web Tokens (HMAC-SHA256, 15-minute lifespan) contain user identity, roles, and account permissions.
- **Refresh Token Rotation & Replay Protection**:
  - Refresh tokens are single-use.
  - Stored strictly as SHA-256 hashes in PostgreSQL (`refresh_tokens.token_hash`), preventing token exposure in database breaches.
  - If a consumed or revoked refresh token is re-submitted (replay attack), the service immediately revokes the **entire token family** for that user.

### 2.2 Authorization & Tenant Isolation
- **Role-Based Access Control (RBAC)**: Separates roles:
  - `ROLE_CUSTOMER`: Account read, initiate payment, view personal transaction history.
  - `ROLE_MERCHANT`: Settlement accounts, payment capture, payout initiation.
  - `ROLE_ADMIN`: Freeze/unfreeze accounts, trigger reconciliation, investigate ledger entries.
  - `ROLE_SYSTEM`: Internal service-to-service communication.
- **Insecure Direct Object Reference (IDOR) Mitigation**:
  Every account, payment, and ledger query verifies that the authenticated user owns the referenced resource, preventing cross-tenant data access.

### 2.3 SSRF Defense on Webhooks
- **The Threat**: Malicious users supply webhook URLs pointing to internal infrastructure (`http://169.254.169.254/latest/meta-data` or `http://localhost:5432`).
- **Implemented Mitigation (`WebhookSecurityValidator.java`)**:
  - Resolves target IP addresses before socket connection.
  - Strictly blocks loopback addresses (`127.0.0.0/8`, `::1`).
  - Strictly blocks RFC 1918 private subnets (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`).
  - Strictly blocks link-local and cloud metadata endpoints (`169.254.0.0/16`).
  - Restricts schemes strictly to `http` and `https`.

### 2.4 SQL & JPQL Injection Immunity
- All database queries are executed via Spring Data JPA repositories using parameterized criteria queries or statically compiled JPQL/named SQL queries.
- Zero string concatenation or dynamic SQL assembly is used in the repository layer.

### 2.5 Request Bounding & DoS Mitigation
- **Request Body Limits**: Tomcat maximum post size bounded to **2MB** in `application-prod.yml`.
- **Multipart Uploads Disabled**: `spring.servlet.multipart.enabled: false` eliminates file upload vulnerabilities.
- **Rate Limiting**: Distributed token-bucket rate limiter enforced on sensitive endpoints (e.g. max 5 login attempts per minute).

### 2.6 Actuator & Telemetry Hardening
- Only safe operational endpoints are exposed: `health,info,metrics,prometheus`.
- Sensitive operational endpoints (`env`, `beans`, `heapdump`, `shutdown`) are **disabled**.
- `management.endpoint.health.show-details: when_authorized` hides database internal connection strings from anonymous health probes.

### 2.7 Container & Runtime Hardening
- **Non-Root Execution**: Docker container runs as non-root user `appuser` (UID: 10001, GID: 10001).
- **JVM Container Memory Awareness**: `-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0` ensures the JVM respects container cgroup memory limits.
- **Fail-Fast Configuration**: Fails immediately on missing secrets (`${DB_PASSWORD}`, `${JWT_SECRET}`).

---

## 3. Automated DevSecOps CI Quality Gates

Automated security verification runs across every commit in `.github/workflows/security.yml`:
1. **Secret Scanning** (`scripts/scan-secrets.ps1`): Regex scan for AWS keys, private PEM keys, Stripe keys, GitHub tokens, and Slack webhooks (**0 secrets detected**).
2. **Dependency Vulnerability Scanning**: Automated checking against public CVE databases.
3. **Reproducible Builds**: Build timestamp locked in `pom.xml` (`2026-09-25T00:00:00Z`).

*Note*: The platform adheres to strict engineering verification; it does not claim formal third-party SOC 2 or PCI-DSS certifications as it is an open-source reference platform.
