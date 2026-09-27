# Grafana Dashboards — Payment & Ledger Platform

> Phase 16 Observability — Grafana dashboard inventory, access, and panel reference.
>
> **Dashboard count**: 4 verified dashboards.
>
> Note: The Phase 16 gap analysis mentioned "12 dashboards" as a planning estimate.
> The implemented and verified set is **4 dashboards** covering all required
> operational monitoring scenarios.

---

## Quick Access

| Dashboard | URL (local) |
|---|---|
| Platform Overview | http://localhost:3000/d/platform-overview |
| Payment Domain | http://localhost:3000/d/payment-domain |
| Ledger Integrity | http://localhost:3000/d/ledger-integrity |
| Infrastructure & JVM | http://localhost:3000/d/infrastructure-jvm |

**Login**: admin / admin (development only — change in production)

---

## Dashboard Inventory

### 1. Platform Overview (`01-platform-overview.json`)

**UID**: `platform-overview`  
**Tags**: `payment-ledger`, `overview`  
**Refresh**: 30s

An at-a-glance view of the entire platform health.

| Panel | Type | Key Metrics |
|---|---|---|
| HTTP Request Rate | timeseries | `http.server.requests` by status |
| HTTP p99/p95/p50 Latency | timeseries | `http.server.requests` histogram quantiles |
| Payment Rate | timeseries | `payment.created`, `payment.settled`, `payment.failed` |
| Ledger Transactions | timeseries | `ledger.transaction.posted` by type, failures |
| Outbox Backlog | timeseries | `outbox.pending`, `outbox.processing`, `outbox.oldest.age` |
| Unbalanced Transactions (24h) | stat | `ledger.unbalanced.transaction` (red if > 0) |
| JVM Heap Usage | gauge | `jvm_memory_used_bytes / jvm_memory_max_bytes` |
| DB Connection Pool | timeseries | `hikaricp.connections.active/idle/pending` |
| Kafka Consumer Lag | timeseries | `kafka.consumer.lag` by topic |

---

### 2. Payment Domain (`02-payment-domain.json`)

**UID**: `payment-domain`  
**Tags**: `payment-ledger`, `payments`  
**Refresh**: 30s

Deep-dive into payment lifecycle metrics.

| Panel | Type | Key Metrics |
|---|---|---|
| Payments Created / min | stat | `payment.created` rate |
| Payments Settled / min | stat | `payment.settled` rate |
| Payments Failed / min | stat | `payment.failed` rate (red threshold at 5) |
| Payment Failure Rate % | gauge | settled / (settled + failed) |
| Pending Reconciliation Rate | stat | `payment.pending_reconciliation` rate (red if > 0.1/s) |
| Payment Lifecycle Over Time | timeseries | All lifecycle counters |
| Payment Capture Duration (p99) | timeseries | `payment.capture.duration` histogram |
| Payment Failures by Reason | piechart | `payment.failed` by `reason` tag |

---

### 3. Ledger Integrity (`03-ledger-integrity.json`)

**UID**: `ledger-integrity`  
**Tags**: `payment-ledger`, `ledger`, `financial`  
**Refresh**: 30s

**Primary financial safety dashboard.** Critical panels fire red on any violation.

| Panel | Type | Key Metrics |
|---|---|---|
| CRITICAL: Unbalanced Transactions (24h) | stat | `ledger.unbalanced.transaction` (must stay 0) |
| CRITICAL: Ledger Invariant Failures (24h) | stat | `ledger.invariant.failure` (must stay 0) |
| Ledger Transaction Failure Rate | gauge | failure / (posted + 0.001) |
| Insufficient Funds Rate | stat | `ledger.balance.check{result=INSUFFICIENT}` |
| Ledger Transactions Posted by Type | timeseries | `ledger.transaction.posted` by type |
| Balance Checks (Sufficient vs Insufficient) | timeseries | `ledger.balance.check` by result |
| Refund & Reversal Rates | timeseries | `refund.created`, `refund.settled`, `refund.failed` |

---

### 4. Infrastructure & JVM (`04-infrastructure-jvm.json`)

**UID**: `infrastructure-jvm`  
**Tags**: `payment-ledger`, `infrastructure`, `jvm`  
**Refresh**: 30s

Operational infrastructure health monitoring.

| Panel | Type | Key Metrics |
|---|---|---|
| JVM Heap Usage % | gauge | Heap used / max (yellow 70%, red 85%) |
| JVM Non-Heap Usage | timeseries | Non-heap used & committed |
| GC Pause Duration (avg) | timeseries | `jvm.gc.pause` avg (red > 500ms) |
| JVM Threads | stat | `jvm.threads.live`, `jvm.threads.daemon` |
| HikariCP Connection Pool | timeseries | active, idle, pending, max |
| HikariCP Acquisition Time (p99) | timeseries | Connection acquisition p99 latency |
| Redis Operations | timeseries | cache hits, misses, failures by cache name |
| Kafka Consumer Lag | timeseries | lag per topic (red > 5000) |

---

## Provisioning

Dashboards are auto-provisioned via Grafana's filesystem provisioner:

```
grafana/
  provisioning/
    datasources/
      datasources.yml       → Points to http://prometheus:9090
    dashboards/
      dashboards.yml        → Points to /etc/grafana/dashboards
  dashboards/
    01-platform-overview.json
    02-payment-domain.json
    03-ledger-integrity.json
    04-infrastructure-jvm.json
```

When Grafana starts, all 4 dashboards are automatically loaded into the
`Payment Ledger Platform` folder. No manual import required.

---

## Local Development

```bash
docker compose up -d prometheus grafana

# Wait for services to be healthy, then:
open http://localhost:3000
# Login: admin / admin
# Navigate: Dashboards → Payment Ledger Platform
```

---

## Cardinality Policy

All PromQL expressions in dashboards:
- Use bounded tag dimensions (`type`, `currency`, `reason`, `status`)
- Do NOT use `paymentId`, `userId`, `accountId`, `correlationId` as label selectors
- All templating variables (if added) must use bounded enum values only
