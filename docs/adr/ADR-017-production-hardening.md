# ADR-017: Production Hardening Architecture and Resilience Controls

**Status**: APPROVED  
**Date**: 2026-09-25  
**Deciders**: Lead Production Engineer, Platform Architecture Team  
**Consulted**: Security, Operations, Financial Compliance  

---

## 1. Context and Problem Statement

The Distributed Payment & Ledger Platform completed functional domain capabilities in Phases 0 through 16. To operate securely and reliably in production environments, the platform requires systematic hardening across configuration, credential isolation, authentication, authorization, IDOR boundaries, timeout budgets, failure degradation, database connection pooling, graceful shutdown, and containerization.

Phases 0 through 16 are **FROZEN**. Production hardening must not redesign the ledger, alter financial source-of-truth invariants, introduce speculative microservices or external service meshes, or weaken existing security controls.

---

## 2. Decision Drivers

1. **Financial Invariant Preservation**: PostgreSQL remains the sole authoritative financial source of truth. Redis and Kafka remain auxiliary and transport mechanisms.
2. **Double-Entry Integrity**: Every posted ledger entry must balance (`SUM(debit) == SUM(credit)`).
3. **Defense-in-Depth**: Strict separation of configuration profiles, elimination of hardcoded secrets, cryptographic key entropy validation, and zero unauthenticated access to sensitive resources.
4. **Resilient Failure Recovery**: Deterministic handling of provider timeouts, Kafka broker partitions, Redis restarts, and database failovers without data corruption or partial transactions.
5. **Operational Observability**: Preservation of Phase 16 Micrometer metrics, Prometheus scrapers, Grafana dashboards, and correlation ID tracking.

---

## 3. Considered Options

- **Option A (Rejected)**: Introduce Kubernetes-specific sidecars, service meshes (Istio/Linkerd), and external vault agents.
  - *Reason for rejection*: Violates Phase 17 scope boundaries and freeze rules. Introduces operational complexity not justified for the current modular monolith architecture.
- **Option B (Accepted)**: Native Spring Boot 3 / PostgreSQL / HikariCP hardening with multi-stage non-root container packaging, strict configuration profile separation, automated secret scanning, robust IDOR enforcement, bounded retry loops, and fail-safe auxiliary degradation.

---

## 4. Architectural Decisions

### 4.1 Configuration Profile Separation
- Dedicated `application-prod.yml` profile established with production-hardened defaults (SQL formatting and debug logging disabled, Tomcat request size bounded to 2MB, Hikari connection pool bounded to 20 connections, graceful shutdown enabled with 20s timeout phase).
- Mandatory credentials (`DB_PASSWORD`, `JWT_SECRET`) injected via environment variables with zero default fallback in production.

### 4.2 Authentication and Refresh Token Hardening
- Enforce minimum 256-bit (32 character) secret key entropy for HMAC-SHA256 JWT tokens.
- Refresh tokens generated as cryptographically secure random UUIDs and persisted strictly as SHA-256 hashes in PostgreSQL.
- Atomic refresh token rotation via conditional database update (`revokeByTokenHashIfNotRevoked`) preventing token replay attacks and concurrent refresh races.

### 4.3 Authorization & IDOR Boundary Enforcement
- All sensitive domain resources (accounts, payments, refunds, payouts, webhooks) enforce ownership matching between caller `userId` and resource `ownerId`/`payerId` in the domain service layer.
- Admin endpoints enforce `@PreAuthorize("hasAnyRole('ADMIN', 'SYSTEM')")`.
- HTTP Security headers (`X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `HSTS`, `Cache-Control`) explicitly configured in `SecurityConfig`.
- Production CORS disallows wildcard origins, restricting access to trusted organizational domains.

### 4.4 Bounded Network Operations & Timeouts
- HikariCP: `connection-timeout: 30000ms`, `max-lifetime: 1800000ms`.
- Redis: `connect-timeout: 2000ms`, `timeout: 2000ms`.
- Kafka Producer: `acks: all`, `retries: 3`, `enable.idempotence: true`.
- Kafka Consumer: `max.poll.records: 50`, `auto.offset.reset: earliest`.
- Outbox Relay: `batch-size: 50`, `lease-seconds: 30`, `max-retries: 5`.

### 4.5 Containerization & Graceful Shutdown
- Multi-stage Docker build utilizing Alpine Linux base and unprivileged execution under UID 10001 (`appuser`).
- JVM container support enabled (`-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError`).
- Graceful shutdown draining HTTP requests and worker batches up to 20s before connection pool disposal.

---

## 5. Consequences

### Positive
- Platform is resilient against external provider network failures, Redis outages, and Kafka partitions without risking ledger inconsistency.
- Strict IDOR and role enforcement guarantees multi-tenant security isolation.
- Sensitive credentials, customer PANs, and tokens are eliminated from logs, exceptions, and version control.
- Clear, verified disaster recovery runbooks provide actionable recovery procedures for production operations.

### Negative / Trade-offs
- Setting strict request limits (2MB body) requires clients uploading oversized webhook or batch requests to stream or chunk data.
- Enforcing strong JWT secret entropy requires operators to manage high-entropy keys across all deployment environments.
