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
| **CI/CD** | Master build passes unit, integration, and security tests with 0 failures. | Maven build verification | [X] |
| **CI/CD** | Reproducible build verified. | `pom.xml` / Phase 18 | [X] |
| **Observability** | Prometheus endpoint active (`/actuator/prometheus`) with Micrometer meters. | `application-prod.yml` / Phase 16 | [X] |
| **Observability** | Structured JSON logging with correlation IDs and sensitive data masking active. | `logback-spring.xml` / Phase 16 | [X] |
| **Alerts** | Critical alerting rules defined for ledger imbalance, Kafka lag, and DB pool saturation. | `docs/operations/incident-response.md` | [X] |
| **Runbooks** | Operational runbooks documented for 13 incident scenarios. | `docs/operations/incident-response.md` §4 | [X] |
| **Performance** | Sustainable capacity evidence documented for the reference environment. | `docs/phase-reports/PHASE-19.md` | [X] |
| **Performance** | HikariCP pool calibrated to the measured reference workload. | `docs/performance/LOAD-TESTING.md` | [X] |
| **Reconciliation** | Discrepancy discovery and compensating ledger adjustments verified. | `ReconciliationEngine.java` / Phase 12 | [X] |
| **Financial** | Double-entry ledger invariant `SUM(debits) == SUM(credits)` verified. | `LedgerPostingEngine.java` / Phase 6 | [X] |
| **Financial** | Deterministic two-party row locking verified under concurrency. | `docs/performance/LOAD-TESTING.md` | [X] |
| **Rollback** | Controlled rollback procedure documented for container and forward-compatible schema. | `docs/operations/controlled-rollout.md` §4 | [X] |
| **Deployment** | Pre-deployment gates and graceful shutdown (20s drain) configured. | `docs/operations/controlled-rollout.md` §2 | [X] |
| **Post-Deploy** | 30-minute observation window for error budget and latency monitoring established. | `docs/operations/controlled-rollout.md` §3 | [X] |

---

## 2. Target-Environment Security Acceptance Gate

These are mandatory deployment acceptance criteria. They are target-environment controls, not claims that the application repository itself implements them.

| Control | Required evidence | Verified |
|---|---|:---:|
| **Ingress TLS** | HTTPS termination, valid certificate chain, and HTTP-to-HTTPS behavior verified. | [ ] |
| **PostgreSQL TLS** | Production database connections use TLS with certificate/hostname verification; plaintext production connections prohibited. | [ ] |
| **Kafka transport security** | Broker/client traffic uses TLS; production authentication (SASL or equivalent) is enabled; plaintext external listeners prohibited. | [ ] |
| **Redis transport security** | Production Redis connections use TLS with certificate verification enabled. | [ ] |
| **Redis authentication** | Production Redis requires authentication/ACLs with least-privilege credentials. | [ ] |
| **Secret manager** | Production secrets are delivered through an approved secret-management/KMS mechanism and are not baked into images or manifests. | [ ] |
| **Network isolation** | PostgreSQL, Kafka, and Redis are not publicly reachable; network policies/security groups restrict traffic to required identities. | [ ] |
| **WAF/API gateway** | Public API traffic passes through an approved edge control providing TLS, request filtering, abuse protection, and appropriate rate limiting. | [ ] |
| **IAM** | Application and deployment identities use least privilege; production write/admin permissions are explicitly scoped. | [ ] |
| **Audit evidence** | Environment-specific evidence for each applicable control is attached to the release/change record before unrestricted production traffic. | [ ] |

### Acceptance rule

A release may be marked **READY_FOR_CONTROLLED_ROLLOUT** after repository gates pass, but **unrestricted production traffic requires all applicable target-environment controls above to be verified**.

These controls must not be marked [X] from repository configuration alone. Release engineering/SRE must provide evidence from the actual deployment environment.

---

## 3. Scope Boundary

The local `docker-compose.yml` is a development topology. Its PostgreSQL, Kafka, and Redis listeners are not evidence of production transport security.

Likewise, `application-prod.yml` externalizes connection endpoints and credentials but does not by itself prove that remote services require TLS, authentication, private networking, WAF protection, or cloud IAM.

The target deployment environment therefore owns final verification of these controls.

---

## 4. Release Sign-Off

Repository verification and target-environment verification are separate gates:

- **Repository gate:** automated CI, security, and reproducible-build checks.
- **Environment gate:** TLS, authentication, secret management, network isolation, WAF/API gateway, IAM, and evidence verification.
- **Production authorization:** requires both gates plus applicable backup/restore, migration, smoke-test, reconciliation, health/readiness, and controlled-traffic checks.
