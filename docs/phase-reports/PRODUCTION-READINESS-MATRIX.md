# Production Readiness Matrix

## Decision Boundary

**Status: READY_FOR_CONTROLLED_ROLLOUT**

This status means the repository has an executable production-readiness gate and the required engineering evidence is present for a controlled staging/canary rollout. It does **not** mean unrestricted production traffic is automatically authorized.

## Evidence Matrix

| Area | Evidence |
|---|---|
| Security | Secret scanning, dependency scanning, container scanning, and Semgrep are mandatory CI gates. |
| Financial Integrity | Database-backed double-entry ledger and Phase 20 invariant tests verify persisted transactions balance. |
| Idempotency & Concurrency | Durable idempotency and deterministic account-lock ordering are covered by integration tests. |
| Messaging | Kafka, transactional outbox, consumer deduplication, and failure-path tests are exercised in integration tests. |
| Observability | Actuator, Micrometer, Prometheus metrics, health probes, dashboards, alerts, and runbooks are present. |
| Operations | Backup/restore, disaster recovery, incident response, controlled rollout, and resilience documentation are present. |
| Supply Chain | Reproducible build checks, container scanning, SBOM generation, and signed artifact/container attestations are defined for release. |
| Deployment | The production release workflow publishes a versioned container to GHCR; deployment remains environment-specific. |
| Performance | Phase 19 records 185 TPS sustainable, 235 TPS observed peak, and 220–240 TPS saturation on the reference environment. |

## Mandatory Pre-Production Conditions

1. All CI and security checks pass on the release commit.
2. The release container is scanned without blocking HIGH/CRITICAL findings.
3. The release artifact and container attestations are generated successfully.
4. Production secrets are supplied by a secret manager or equivalent protected environment.
5. PostgreSQL backup and restore have been validated against the target environment.
6. Database migrations have been reviewed for forward/rollback compatibility.
7. Health/readiness probes are verified after deployment.
8. Financial reconciliation is verified after controlled rollout.
9. The target environment has an explicit rollback procedure.
10. GitHub branch governance and required status checks are enabled before allowing unrestricted merges to `main`.

## Auditor Boundary

This matrix is an engineering release gate, not a regulatory certification, PCI DSS assessment, SOC 2 report, or financial-institution production authorization.
