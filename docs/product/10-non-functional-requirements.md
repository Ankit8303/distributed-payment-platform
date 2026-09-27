# Non-Functional Requirements (NFR) Specification

## 1. Principles & Measurement Philosophy
Non-Functional Requirements define the operational, performance, resilience, and observability standards for the platform.
- **Evidence-Based Engineering**: No performance, latency, or throughput claim is treated as authoritative until proven by empirical load testing (Phase 19).
- **Prohibition of Unfounded Guarantees**: Where operational guarantees depend on physical infrastructure architecture (e.g. storage replication, disaster-recovery topology), requirements are explicitly marked: **TBD — requires load testing and infrastructure benchmark**.

---

## 2. Durability, Integrity & Recovery Targets

### 2.1 Financial Data Integrity
- **Mandatory Requirement**: No committed financial transaction may be silently lost, truncated, or duplicated.
- **Durability Guarantee**: PostgreSQL write-ahead logging (WAL) configured with `synchronous_commit = on` for all financial write operations.

### 2.2 Recovery Point Objective (RPO)
- **Infrastructure RPO**: **Target RPO: TBD based on the selected PostgreSQL durability, backup, replication, and disaster-recovery architecture.**
- Operational requirement: Continuous WAL archiving combined with automated point-in-time recovery (PITR) testing in Phase 17/20.

### 2.3 Recovery Time Objective (RTO)
- **Target RTO**: **Target RTO: TBD based on failover infrastructure architecture.** (Initial target: $< 15$ minutes for automated standby database failover).

### 2.4 Data Retention Policy
- **Configurable Retention Rule**: Production retention periods must be determined according to applicable jurisdiction, regulatory requirements, contractual requirements, business policy, and provider requirements.
- The engineering platform provides configurable data retention hooks:
  - Financial ledger entries and transactions: retained indefinitely by default; archival partitioning supported.
  - Idempotency records: configurable TTL (default 30 days) after which expired records may be purged.
  - Outbox published events: configurable TTL (default 7 days after `published_at`) to control table bloat.
  - Audit logs: retained in append-only storage according to organizational governance policy.

---

## 3. Availability & Reliability

| Component / Layer | Availability Target | Measurement Method |
| :--- | :--- | :--- |
| Core Payment & Account APIs | 99.9% uptime (excluding scheduled maintenance) | Synthetic health checks via `/actuator/health` and Prometheus uptime probes. |
| Double-Entry Ledger Engine | 99.99% write integrity | Automated continuous verification of balanced ledger transactions. |
| Asynchronous Outbox Relay | Eventual delivery for 100% of committed outbox records | Monitoring `outbox_events` pending queue depth and lag metrics. |
| External Gateway Degraded Mode | Graceful transition to `PENDING_RECONCILIATION` | Zero dropped transactions during simulated network partition. |

---

## 4. Latency & Throughput Targets (Preliminary)

> [!NOTE]
> All latency and throughput figures below represent target design envelopes. Formal baselines will be established during **Phase 19 (Performance & Load Testing)**.

| Metric | Preliminary Target | Status | Measurement Method |
| :--- | :--- | :--- | :--- |
| Payment Initiation API (`POST /payments`) | p50 $< 100$ms, p95 $< 350$ms, p99 $< 1000$ms (excluding external provider latency) | **TBD — requires load testing** | Micrometer `http.server.requests` timer histogram. |
| Balance Query API (`GET /accounts/{id}/balance`) | p50 $< 10$ms, p95 $< 50$ms, p99 $< 100$ms | **TBD — requires load testing** | HTTP server timer metrics. |
| Transaction History Query | p95 $< 200$ms (page size $\le 50$) | **TBD — requires load testing** | PostgreSQL query execution plan profiling + HTTP timer. |
| Peak Payment Throughput | Initial target: $\ge 250$ TPS on baseline container spec | **TBD — requires load testing** | Gatling / k6 load test execution. |
| Outbox Relay Lag | Time from DB commit to Kafka publish $< 1000$ms (p95) | **TBD — requires load testing** | Gauge metric: `outbox.dispatch.lag.ms`. |

---

## 5. Concurrency & Resource Sizing

### 5.1 Connection Pool Architecture (HikariCP)
- PostgreSQL connection pool sizing must balance throughput against database context-switching overhead:
  $$\text{Pool Size} = (2 \times \text{CPU Cores}) + \text{Effective Spindle Count}$$
- Initial pool size: 30 connections with `leak-detection-threshold = 2000ms`, `connection-timeout = 5000ms`.
- Thread pool sizing for Kafka listeners: isolated concurrency per topic listener to prevent notification workers from starving financial listeners.

### 5.2 Row Locking & Contention Management
- Account balance updates enforce pessimistic locks (`SELECT FOR UPDATE`) strictly in order of account ID to eliminate relational deadlocks when multiple transactions interact with the same account pair.

---

## 6. Observability & Telemetry

### 6.1 Structured JSON Logging
- All logs formatted in structured JSON via Logback.
- Mapped Diagnostic Context (MDC) automatically populated at controller entry:
  - `correlationId`: Distributed trace ID across HTTP, DB, and Kafka.
  - `actorId`: Authenticated user identity (if logged in).
  - `accountNumber`: Target account (if applicable).
  - `operation`: Current use case action name.
- Strict masking rules: Zero raw cardholder data, CVVs, passwords, or JWT secrets in logs.

### 6.2 Metrics & Alerting (Micrometer + Prometheus)
- Core published metrics:
  - `financial.transactions.posted.total`: Counter tagged by `transaction_type` and `currency`.
  - `financial.ledger.unbalanced.total`: Counter alerting immediately if any transaction fails balance invariant ($> 0$ triggers P1 alert).
  - `idempotency.conflicts.total`: Counter tagged by `conflict_type` (`MISMATCH`, `CONCURRENT`).
  - `outbox.pending.count`: Gauge measuring backlog of un-published outbox records.
  - `reconciliation.discrepancies.total`: Counter tagged by `discrepancy_type`.
  - `jvm.*`, `hikaricp.*`, `process.*`: Standard runtime metrics.

### 6.3 Health & Readiness Probes
- `/actuator/health/liveness`: Returns `200 OK` if JVM process is alive.
- `/actuator/health/readiness`: Returns `200 OK` only when PostgreSQL database connectivity and Kafka broker metadata are validated.
