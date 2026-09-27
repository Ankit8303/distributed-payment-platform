# Production Release Verification Checklist

**Governing Phase**: Phase 20 — Complete Production-Readiness Verification  
**Target Milestone**: Controlled Production Rollout  
**Status**: VERIFIED RELEASE GATES  

---

## 1. Release Gate Checklist

| Category | Verification Item | Evidence Reference | Verified |
|---|---|---|:---:|
| **Security** | Automated secret scan shows 0 credential or private key leaks. | `scripts/scan-secrets.ps1` | [X] |
| **Security** | BCrypt strength 12 enforced for password hashing. | `SecurityConfig.java` / Phase 3 | [X] |
| **Security** | Refresh token rotation and single-use replay protection active. | `RefreshTokenService.java` / Phase 3 | [X] |
| **Security** | Webhook validator strictly blocks private, loopback, link-local, and cloud metadata IPs. | `WebhookSecurityValidator.java` / Phase 17 | [X] |
| **Security** | Sensitive actuator endpoints (`env`, `beans`, `shutdown`) disabled in production. | `application-prod.yml` | [X] |
| **Database** | PostgreSQL 16 schema migrations (`V1`..`V12`) validated on startup. | `application-prod.yml` / `flyway-database-postgresql` | [X] |
| **Database** | Negative balance check constraints active on non-overdraft accounts. | PostgreSQL schema `V3__account_management.sql` | [X] |
| **Database** | Covering and composite B-tree indexes verified; 0 sequential scans on critical queries. | `docs/performance/QUERY-PROFILING.md` | [X] |
| **Backup** | Logical PostgreSQL backup SOP documented with step-by-step commands. | `docs/operations/backup-restore.md` | [X] |
| **Backup** | Daily backup schedule and retention policy (30d daily / 7y monthly) defined. | `docs/operations/backup-restore.md` §2 | [X] |
| **Restore** | Controlled restore drill executed successfully with synthetic financial data. | `docs/operations/backup-restore.md` §5 | [X] |
| **Restore** | Post-restore financial audit confirms 100% balanced ledger transactions. | `docs/operations/backup-restore.md` §4 | [X] |
| **Configuration** | All production secrets (`DB_PASSWORD`, `JWT_SECRET`) strictly externalized to environment. | `docs/operations/configuration-reference.md` | [X] |
| **Configuration** | Hibernate SQL logging disabled (`show-sql: false`) in production profile. | `application-prod.yml` | [X] |
| **Container** | Dockerfile creates non-root user (`appuser:10001`) with read-only root filesystems where possible. | `docker/Dockerfile` | [X] |
| **Container** | Multi-stage build with JVM container support (`-XX:MaxRAMPercentage=75.0`). | `docker/Dockerfile` / Phase 17 | [X] |
| **CI/CD** | Master build passes 100% of unit, integration, and security tests (0 failures). | Maven build verification (439 tests) | [X] |
| **CI/CD** | Reproducible build verified (`project.build.outputTimestamp=2026-09-25T00:00:00Z`). | `pom.xml` / Phase 18 | [X] |
| **Observability** | Prometheus endpoint active (`/actuator/prometheus`) with Micrometer meters. | `application-prod.yml` / Phase 16 | [X] |
| **Observability** | Structured JSON logging with correlation IDs and sensitive data masking active. | `logback-spring.xml` / Phase 16 | [X] |
| **Alerts** | Critical alerting rules defined for ledger imbalance, Kafka lag, and DB pool saturation. | `docs/operations/incident-response.md` | [X] |
| **Runbooks** | Operational runbooks documented for 13 incident scenarios. | `docs/operations/incident-response.md` §4 | [X] |
| **Performance** | Sustainable capacity certified at 185 TPS (100 VUs) with ~482% headroom over 31.8 TPS peak demand. | `docs/phase-reports/PHASE-19.md` | [X] |
| **Performance** | HikariCP pool calibrated to 20 connections as preferred measured operating point. | `docs/performance/LOAD-TESTING.md` | [X] |
| **Reconciliation**| Discrepancy case discovery and compensating ledger adjustments verified. | `ReconciliationEngine.java` / Phase 12 | [X] |
| **Financial** | Double-entry ledger invariant `SUM(debits) == SUM(credits)` verified across all records. | `LedgerPostingEngine.java` / Phase 6 | [X] |
| **Financial** | Deterministic two-party row locking (`UUID.compareTo`) prevents deadlocks under concurrency. | `docs/performance/LOAD-TESTING.md` | [X] |
| **Rollback** | Controlled rollback procedure documented for container and forward-compatible schema. | `docs/operations/controlled-rollout.md` §4 | [X] |
| **Deployment** | Pre-deployment gates and graceful shutdown (20s drain) configured. | `docs/operations/controlled-rollout.md` §2 | [X] |
| **Post-Deploy** | 30-minute observation window for error budget and latency monitoring established. | `docs/operations/controlled-rollout.md` §3 | [X] |
