# Portfolio Project Summary: Distributed Payment & Ledger Platform

**Role Demonstrated**: Java Backend Engineer / SDE-1 / Systems Engineer  
**Architecture**: Event-Driven Modular Monolith  
**Release Readiness**: `READY_FOR_CONTROLLED_ROLLOUT` (Phase 20 Gate Sign-Off)  
**License Status**: `LICENSE REVIEW REQUIRED`  

---

## 1. Executive Summary & Problem Solved

Financial software demands deterministic correctness that standard web application patterns cannot provide. In distributed commerce, typical microservice or CRUD approaches suffer from:
1. **Silent balance drift** caused by floating-point calculation errors and partial database writes.
2. **Duplicate transaction execution** when client network drops trigger uncoordinated retries.
3. **Dual-write data corruption** when writing to an operational database and emitting message queue events independently.
4. **Deadlocks and race conditions** under concurrent cross-transfers between identical account pairs.
5. **Dangling unverified payments** when external payment gateways time out without response.

This project delivers a **production-grade Distributed Payment & Ledger Platform** built with **Java 21**, **Spring Boot 3.3.4**, **PostgreSQL 16**, **Apache Kafka (KRaft)**, and **Redis 7**. The platform establishes **PostgreSQL as the authoritative financial source of truth**, enforcing mathematical double-entry bookkeeping ($\sum \text{Debits} == \sum \text{Credits}$), database-enforced idempotency, deadlock-free concurrency control, and transactional outbox event delivery.

---

## 2. Core Engineering Capabilities Demonstrated

| Domain | Implemented Engineering Solution | Underlying Technologies |
| :--- | :--- | :--- |
| **Financial Accounting** | Immutable double-entry ledger; integer minor-unit money arithmetic (64-bit cents); debit/credit equality invariant enforcement; compensating reversal transactions. | Java 21, Spring Data JPA, PostgreSQL 16, Check Constraints |
| **Concurrency & Locking** | Deterministic UUID lexicographical locking of source and destination accounts (`ORDER BY id FOR UPDATE`); elimination of database deadlocks during bidirectional transfers. | PostgreSQL Row-Level Locks, Spring `@Transactional` |
| **Idempotency** | Durable database-level idempotency record table with unique constraint on `(actor_id, operation, idempotency_key)`; duplicate request detection and payload caching. | PostgreSQL Unique Index, Spring Handler Interceptors |
| **Reliable Messaging** | Transactional outbox pattern guaranteeing atomic business state and event persistence; background outbox poller using `FOR UPDATE SKIP LOCKED`; Kafka partitioning by `accountId`. | Spring Kafka, Apache Kafka 7.6 (KRaft), PostgreSQL Outbox |
| **Consumer Resilience** | Consumer-side idempotency tracking table (`processed_messages`); at-least-once message handling with strict deduplication; dead-letter queue routing. | Spring Kafka DeadLetterPublishingRecoverer, PostgreSQL |
| **Reconciliation Engine** | Automated background reconciliation scanning for stale pending provider operations; external provider verification API checks; automated resolution or compensation. | Spring Scheduled Tasks, REST Client, State Machine |
| **Auxiliary Caching** | Non-authoritative Redis caching and distributed token-bucket rate limiting; graceful fallback to PostgreSQL and local rate-limiting on Redis outage. | Redis 7, Lettuce, Spring Data Redis |
| **Security & Auth** | Stateless JWT authentication (HMAC-SHA256); refresh token rotation with family revocation; BCrypt password hashing; IDOR verification; strict SSRF webhook validation. | Spring Security 6.3, Jakarta Bean Validation, InetAddress |
| **Observability** | Prometheus metric instrumentation; JVM & connection pool monitoring; health check endpoints; distributed trace correlation via `X-Correlation-ID` and Logback MDC. | Spring Boot Actuator, Micrometer Prometheus, SLF4J MDC |
| **DevOps & Testing** | 452 automated unit/integration tests with Testcontainers; multi-stage Docker build; unprivileged non-root container; GitHub Actions CI quality gates and secret scanning. | JUnit 5, Mockito, AssertJ, Docker, GitHub Actions |

---

## 3. Key Architectural & Financial Guarantees

1. **PostgreSQL Authority**: PostgreSQL holds all financial truth (accounts, balances, journal entries, idempotency records, outbox queue). Redis is never authoritative.
2. **Double-Entry Invariant**: Every transaction records equal and opposite debits and credits:
   $$\sum \text{Debits} - \sum \text{Credits} = 0$$
3. **Immutability**: Ledger journal entries cannot be updated or deleted. Database-level triggers restrict `UPDATE` and `DELETE` operations on ledger tables.
4. **Deadlock-Free Transfers**: When moving money between account $A$ and account $B$, locks are always acquired in order:
   $$\text{Lock}(\min(A, B)) \implies \text{Lock}(\max(A, B))$$
5. **No Floating-Point Math**: All values are stored and calculated as 64-bit integers (`Long amountMinor`), avoiding IEEE 754 precision leakage.
6. **Zero Dual-Write Loss**: State updates and outbox events commit together in PostgreSQL. If the process crashes before Kafka dispatch, the outbox worker reclaims and publishes the event upon restart.

---

## 4. Empirical Performance & Capacity Baseline

All performance characteristics were measured during Phase 19 load and capacity tests on single-node test hardware:

- **185 TPS Sustainable Operating Ceiling**: Measured under 100 Virtual Users (VUs) with P95 latency of **142ms**, zero errors, and HikariCP connection pool utilization stable at 65–75%.
- **235 TPS Observed Peak Test Load**: Measured under 200 VUs with P95 latency of **312ms**, zero errors, and connection pool utilization reaching 95–100%.
- **220–240 TPS Saturation Region**: Identified under 400 VUs as the single-node physical bottleneck where connection wait queues form and tail latency spikes.
- **210 events/sec Outbox Relay**: Sustained asynchronous publishing rate from PostgreSQL outbox to Kafka brokers.
- **Capacity Headroom**: In a modeled 50,000 DAU business workload (average 5.8 TPS, peak 29 TPS), the single-node deployment provides **6.4x to 31x capacity margin**.

---

## 5. Security & DevSecOps Profile

- **Zero Secrets Policy**: Validated by automated TruffleHog CI scanning across repository history.
- **SSRF Protection**: Outbound webhook dispatcher blocks internal IPv4/IPv6 ranges (RFC 1918, RFC 3927, loopback, link-local) via DNS resolution verification.
- **IDOR Protection**: Every request verifying account ownership cross-references the authenticated JWT user identity before executing read or write operations.
- **Container Hardening**: Dockerfile executes as `appuser` (UID 10001, GID 10001) on an unprivileged, minimal Eclipse Temurin JRE base image.
- **Actuator Security**: `/actuator/health` and `/actuator/prometheus` are strictly isolated from credentials and environment variables.

---

## 6. Operational Readiness & Disaster Recovery

- **Release Status**: `READY_FOR_CONTROLLED_ROLLOUT`
- **Automated Tests**: 452 passing automated tests (0 failures, 0 errors, 0 skipped).
- **Synthetic Backup & Restore**: Verified at **3.85 seconds** restore duration for database snapshots.
- **Operational Runbooks**: Fully documented in `docs/operations/`:
  - `backup-restore.md`: Snapshot creation, verification, and restoration drills.
  - `disaster-recovery.md`: Scenarios for database corruption, broker outages, and failovers.
  - `incident-response.md`: Severity matrix, triage checklists, and escalation paths.
  - `controlled-rollout.md`: Blue/green deployment canary stages and rollback gates.
  - `resilience-matrix.md`: Failure modes across all external dependencies.

---

## 7. Documented Limitations & Scope

1. **Single-Node Monolith Scope**: The platform is deployed and verified as a single-node modular monolith. Multi-node cluster deployment with distributed lock managers is reserved for post-release scaling.
2. **Short-Duration Soak Test**: Performance testing verified stability during a 10-minute short soak test. Long-term multi-day soak testing under continuous traffic is deferred to staging deployment.
3. **Containerized Drills**: Disaster recovery and backup restoration drills were measured on containerized test infrastructure.
