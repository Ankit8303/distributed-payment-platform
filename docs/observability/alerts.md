# Alert Rules Reference — Payment & Ledger Platform

> Phase 16 Observability — Prometheus alert rule inventory and justifications.
>
> Source: [`prometheus/alert_rules.yml`](../../prometheus/alert_rules.yml)
>
> For remediation steps, see: [`runbooks.md`](./runbooks.md)

---

## Alert Philosophy

| Principle | Implementation |
|---|---|
| **Financial integrity alerts fire immediately** | `LedgerUnbalancedTransaction`, `LedgerInvariantFailure`, `KafkaDltEventsDetected`: `for: 0m` |
| **Anti-flapping for transient conditions** | All non-financial alerts use `for: 2m` to `for: 5m` |
| **Every alert has a runbook** | All 22 alerts link to `docs/observability/runbooks.md` |
| **Severity is bounded** | Only `critical` and `warning` — no ambiguous `info` alerts |
| **Cardinality-safe PromQL** | No alert expression references per-payment or per-user labels |

---

## Alert Inventory

### Group: http_api

| Alert | Expr | For | Severity | Justification |
|---|---|---|---|---|
| `HighHttpErrorRate` | HTTP 5xx rate > 5% | 2m | critical | Platform-wide error signal; 5% sustained indicates systemic failure |
| `HighHttpP99Latency` | p99 > 2s | 3m | warning | 2s is the SLO boundary for payment capture |

### Group: payments

| Alert | Expr | For | Severity | Justification |
|---|---|---|---|---|
| `HighPaymentFailureRate` | failure rate > 10% | 3m | critical | 10% failure rate is far above normal provider decline rates |
| `PaymentPendingReconciliationSpike` | > 0.1/s | 2m | critical | Rapid PENDING_RECONCILIATION indicates provider or DB connectivity failure |

### Group: ledger

| Alert | Expr | For | Severity | Justification |
|---|---|---|---|---|
| `LedgerUnbalancedTransaction` | any increase | **0m** | critical | Zero tolerance — any unbalanced transaction is an immediate financial integrity event |
| `LedgerInvariantFailure` | any increase | **0m** | critical | Zero tolerance — invariant violation requires immediate investigation |
| `HighLedgerTransactionFailureRate` | > 5% | 3m | warning | 5% ledger failure rate indicates infrastructure issues |

> **Zero-Duration Justification**: `for: 0m` alerts on `LedgerUnbalancedTransaction` and
> `LedgerInvariantFailure` are deliberately configured to fire on the first data point.
> A single occurrence of financial ledger corruption is a P1 incident by platform policy.
> There is no acceptable "transient" financial unbalance state.

### Group: outbox

| Alert | Expr | For | Severity | Justification |
|---|---|---|---|---|
| `OutboxPendingBacklog` | > 500 | 5m | warning | 500 events indicates relay is degraded |
| `OutboxCriticalBacklog` | > 2000 | 3m | critical | 2000 events indicates relay failure — consumers are not receiving events |
| `OutboxOldestEventAge` | > 300s | 2m | warning | Events > 5 min old indicate the relay is stuck |
| `OutboxHighFailureRate` | > 10% | 3m | critical | 10% relay failure means Kafka is unreachable or schema mismatched |

### Group: kafka

| Alert | Expr | For | Severity | Justification |
|---|---|---|---|---|
| `KafkaConsumerLagHigh` | > 1000 | 5m | warning | 1000 message lag in 5 min indicates processing is falling behind |
| `KafkaConsumerLagCritical` | > 5000 | 3m | critical | 5000 lag indicates consumer is not processing events |
| `KafkaDltEventsDetected` | any | **0m** | warning | Any DLT event indicates message loss or schema incompatibility |

### Group: database

| Alert | Expr | For | Severity | Justification |
|---|---|---|---|---|
| `HikariConnectionPoolSaturation` | > 90% | 3m | warning | 90% pool saturation leads to timeout errors |
| `HikariConnectionTimeout` | > 5 in 5m | 1m | critical | Connection timeouts indicate DB is unreachable |

### Group: redis

| Alert | Expr | For | Severity | Justification |
|---|---|---|---|---|
| `RedisOperationFailureRate` | > 0.5/s | 3m | warning | Redis failures degrade idempotency and rate limiting |

### Group: jvm

| Alert | Expr | For | Severity | Justification |
|---|---|---|---|---|
| `JvmHeapMemoryPressure` | > 85% | 5m | warning | 85% heap is the pre-OOM warning threshold |
| `JvmGcPauseDurationHigh` | avg > 500ms | 3m | warning | 500ms GC pauses cause visible latency spikes |

### Group: reconciliation

| Alert | Expr | For | Severity | Justification |
|---|---|---|---|---|
| `ReconciliationManualReviewRequired` | any rate | 1m | warning | Items needing manual review require operational awareness |

### Group: notifications

| Alert | Expr | For | Severity | Justification |
|---|---|---|---|---|
| `NotificationHighFailureRate` | > 15% | 5m | warning | Notification failures don't affect financial correctness but degrade UX |

### Group: application

| Alert | Expr | For | Severity | Justification |
|---|---|---|---|---|
| `ApplicationDown` | target unreachable | 1m | critical | The application is not reachable for scraping |
| `ReadinessProbeFailure` | probe != 1 | 2m | critical | Application cannot serve traffic |

---

## Alert Count Summary

| Group | Count | Max Severity |
|---|---|---|
| http_api | 2 | critical |
| payments | 2 | critical |
| ledger | 3 | critical |
| outbox | 4 | critical |
| kafka | 3 | critical |
| database | 2 | critical |
| redis | 1 | warning |
| jvm | 2 | warning |
| reconciliation | 1 | warning |
| notifications | 1 | warning |
| application | 2 | critical |
| **Total** | **23** | — |

> Note: The alert_rules.yml file contains 23 alert definitions (22 top-level + 1 ReadinessProbeFailure).
> All alerts have: severity label, runbook annotation, summary annotation, description annotation, and
> appropriate `for` duration.
