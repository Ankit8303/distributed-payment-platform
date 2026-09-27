# SLO / SLI Contracts — Payment & Ledger Platform

> Phase 16 Observability — Quantified service level objectives with error budgets.

---

## SLO Contract Summary

| SLO | SLI Metric | Target | Error Budget (30d) |
|---|---|---|---|
| **API Availability** | HTTP 5xx error rate | 99.9% success | 43.2 min/month |
| **Payment Latency p99** | HTTP p99 response time | < 1s | Max 0.1% of requests > 1s |
| **Payment Success Rate** | payment.settled / payment.created | 99.5% | 0.5% failure budget |
| **Ledger Integrity** | ledger.unbalanced.transaction | 100% (zero violations) | Zero tolerance |
| **Outbox Delivery** | outbox.published / (outbox.published + outbox.failed) | 99.9% | 0.1% failure budget |
| **Event Delivery Lag** | outbox.oldest.age | < 60 seconds P95 | < 5 min maximum |
| **Reconciliation Success** | reconciliation.success / reconciliation.attempts | 99% | 1% budget |

---

## Detailed SLO Definitions

### SLO-1: API Availability
- **SLI**: `1 - (http_5xx_count / http_total_count)`
- **Target**: 99.9% (three nines)
- **Measurement Window**: 30-day rolling
- **Error Budget**: 43.2 minutes/month
- **Burn Rate Alert**: Fire if 5xx rate > 5% for 2 minutes (fast burn)

### SLO-2: Payment Capture Latency
- **SLI**: `P99(payment.capture.duration) < 2s`
- **Target**: 99th percentile < 2 seconds
- **Measurement Window**: 5-minute rolling windows
- **Exclusions**: Payment provider timeouts (provider SLA governed separately)

### SLO-3: Payment Success Rate
- **SLI**: `payment.settled / (payment.settled + payment.failed)`
- **Target**: 99.5% of non-declined payments succeed
- **Note**: Provider-declined payments (INSUFFICIENT_FUNDS, ACCOUNT_FROZEN) are excluded from denominator

### SLO-4: Ledger Financial Integrity (Zero Tolerance)
- **SLI**: `ledger.unbalanced.transaction.total == 0`
- **Target**: 100% — ZERO unbalanced transactions
- **Measurement Window**: All time
- **Escalation**: Immediate page on any violation

### SLO-5: Outbox Event Delivery Rate
- **SLI**: `outbox.published / (outbox.published + outbox.failed)`
- **Target**: 99.9% of events successfully delivered to Kafka
- **Retry Policy**: 5 retries with exponential backoff (max 60s)

### SLO-6: Outbox Event Delivery Latency
- **SLI**: `outbox.oldest.age < 60s` (P95)
- **Target**: 95th percentile of pending outbox events aged < 60 seconds
- **Maximum**: No event should remain undelivered > 5 minutes under normal conditions

### SLO-7: Reconciliation Effectiveness
- **SLI**: `reconciliation.success / reconciliation.attempts`
- **Target**: 99% of PENDING_RECONCILIATION payments resolved automatically
- **Manual Review SLO**: Items flagged for manual review acknowledged within 4 business hours

---

## Error Budget Policy

| Remaining Budget | Action |
|---|---|
| 100% — 75% | Normal operations, no restrictions |
| 75% — 50% | Engineering awareness, review recent changes |
| 50% — 25% | Freeze non-critical feature deployments |
| 25% — 0% | Emergency response, all hands on deck |
| 0% (exhausted) | Halt all non-emergency deployments, incident declared |

---

## Prometheus Recording Rules (SLO Tracking)

Add to `prometheus.yml` for efficient SLO computation:

```yaml
# SLO recording rules
groups:
  - name: slo_recording
    interval: 30s
    rules:
      - record: slo:http_availability:ratio_rate5m
        expr: >
          1 - (
            sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m]))
            /
            sum(rate(http_server_requests_seconds_count[5m]))
          )

      - record: slo:payment_success:ratio_rate5m
        expr: >
          sum(rate(payment_settled_total[5m]))
          /
          (sum(rate(payment_settled_total[5m])) + sum(rate(payment_failed_total[5m])) + 0.001)

      - record: slo:outbox_delivery:ratio_rate5m
        expr: >
          sum(rate(outbox_published_total[5m]))
          /
          (sum(rate(outbox_published_total[5m])) + sum(rate(outbox_failed_total[5m])) + 0.001)
```
