# Metrics Reference — Payment & Ledger Platform

> Phase 16 Observability — Canonical metric definitions and cardinality rules.
>
> **Non-negotiable invariant**: Observability MUST NEVER become financial authority.
> Metrics are read-only operational signals. They do not drive financial decisions.

---

## Cardinality Rules

### Permitted Tags
| Tag | Allowed Values |
|---|---|
| `status` | HTTP status codes (200, 201, 400, 500...) |
| `currency` | ISO 4217 codes (USD, EUR, GBP...) max 5 chars |
| `type` | Bounded enum (PAYMENT, REFUND, REVERSAL, PAYOUT, ADMIN_ADJUSTMENT) |
| `reason` | Bounded enum (INSUFFICIENT_FUNDS, PROVIDER_DECLINED, DB_SETTLEMENT_FAILED...) |
| `result` | SUFFICIENT, INSUFFICIENT, SUCCESS, FAILURE |
| `error_type` | Bounded error class names |
| `event_type` | Bounded event names (PaymentSettled, RefundSettled...) |
| `action` | Bounded admin operation names |
| `resource` | Bounded resource type names |
| `cache` | Cache names (idempotency, rateLimit...) |
| `operation` | Bounded operation names |
| `topic` | Kafka topic names |
| `method` | HTTP methods (GET, POST, PUT, DELETE) |
| `uri` | Normalized URI templates (not raw paths with IDs) |
| `accountType` | CUSTOMER, MERCHANT, INTERNAL_SETTLEMENT |

### Strictly Prohibited Tags
- `paymentId`, `refundId`, `payoutId`, `reversalId` — unbounded UUIDs
- `userId`, `actorId`, `accountId` — unbounded UUIDs
- `correlationId`, `requestId`, `eventId` — unbounded UUIDs
- `email`, `phone`, `webhookUrl` — PII / unbounded
- `amountMinor`, `amount` — unbounded numeric range
- `providerReference` — unbounded external reference
- Any raw JWT token, password, or secret value

---

## Metric Families

### 1. HTTP Metrics (Auto-instrumented by Spring Boot)

| Metric | Type | Tags | Description |
|---|---|---|---|
| `http.server.requests` | Timer/Counter | `method`, `uri`, `status`, `outcome` | All HTTP request durations and counts |
| `http.server.active.requests` | Gauge | `method`, `uri` | Concurrent active HTTP requests |

**SLAs configured**: 100ms, 250ms, 500ms, 1s, 2s

### 2. Payment Metrics

| Metric | Type | Tags | Description |
|---|---|---|---|
| `payment.created` | Counter | `currency` | Payment creation events |
| `payment.settled` | Counter | `currency` | Payment settlement events |
| `payment.failed` | Counter | `reason` | Payment failure events |
| `payment.pending_reconciliation` | Counter | `reason` | Payments entering reconciliation state |
| `payment.capture.duration` | Timer | `currency` | End-to-end capture latency |

### 3. Ledger Metrics

| Metric | Type | Tags | Description |
|---|---|---|---|
| `ledger.transaction.posted` | Counter | `type`, `currency` | Successfully posted ledger transactions |
| `ledger.transaction.failure` | Counter | `type`, `reason` | Failed ledger transaction attempts |
| `ledger.balance.check` | Counter | `accountType`, `result` | Balance sufficiency checks |
| `ledger.invariant.failure` | Counter | `invariant` | Ledger invariant violations (CRITICAL) |
| `ledger.unbalanced.transaction` | Counter | (none) | Transactions where debits ≠ credits (CRITICAL) |

### 4. Refund Metrics

| Metric | Type | Tags | Description |
|---|---|---|---|
| `refund.created` | Counter | `currency` | Refund creation events |
| `refund.settled` | Counter | `currency` | Refund settlement events |
| `refund.failed` | Counter | `reason` | Refund failure events |

### 5. Payout Metrics

| Metric | Type | Tags | Description |
|---|---|---|---|
| `payout.created` | Counter | `currency` | Payout creation events |
| `payout.settled` | Counter | `currency` | Payout settlement events |
| `payout.failed` | Counter | `reason` | Payout failure events |

### 6. Outbox Metrics

| Metric | Type | Tags | Description |
|---|---|---|---|
| `outbox.pending` | Gauge | (none) | Current count of pending outbox events |
| `outbox.processing` | Gauge | (none) | Current count of events being processed |
| `outbox.oldest.age` | Gauge | (none) | Age of oldest pending event in seconds |
| `outbox.published` | Counter | (none) | Successfully published outbox events |
| `outbox.failed` | Counter | (none) | Failed outbox publish attempts |
| `outbox.retry` | Counter | (none) | Outbox retry attempts |
| `outbox.publish.duration` | Timer | (none) | Batch publish duration |

### 7. Kafka Metrics

| Metric | Type | Tags | Description |
|---|---|---|---|
| `kafka.consumer.records` | Counter | `topic`, `event_type` | Consumed Kafka records |
| `kafka.consumer.errors` | Counter | `topic`, `error_type` | Kafka consumer errors |
| `kafka.consumer.lag` | Gauge | `topic` | Consumer group lag per topic |
| `kafka.consumer.dlt` | Counter | `topic`, `event_type` | Dead letter topic events |

### 8. Redis Metrics

| Metric | Type | Tags | Description |
|---|---|---|---|
| `redis.cache.hit` | Counter | `cache` | Redis cache hits |
| `redis.cache.miss` | Counter | `cache` | Redis cache misses |
| `redis.operation.failure` | Counter | `operation` | Redis operation failures |

### 9. Reconciliation Metrics (Phase 12)

| Metric | Type | Tags | Description |
|---|---|---|---|
| `reconciliation.candidates` | Counter | (none) | Payments identified for reconciliation |
| `reconciliation.attempts` | Counter | (none) | Reconciliation attempts |
| `reconciliation.success` | Counter | (none) | Successfully reconciled payments |
| `reconciliation.failed` | Counter | (none) | Failed reconciliation attempts |
| `reconciliation.manual_review` | Counter | (none) | Items requiring manual review |

### 10. Notification Metrics (Phase 13)

| Metric | Type | Tags | Description |
|---|---|---|---|
| `notification.created` | Counter | `channel` | Notifications created |
| `notification.sent` | Counter | `channel` | Successfully delivered notifications |
| `notification.failed` | Counter | `channel`, `reason` | Failed notification deliveries |
| `notification.retry` | Counter | `channel` | Notification retry attempts |

### 11. Admin Metrics (Phase 14)

| Metric | Type | Tags | Description |
|---|---|---|---|
| `admin.operation.count` | Counter | `action`, `resource`, `result` | Admin operations performed |
| `admin.investigation.request` | Counter | `resource` | Admin investigation requests |

### 12. Infrastructure Metrics (Auto-instrumented)

| Metric | Type | Description |
|---|---|---|
| `hikaricp.connections.active` | Gauge | Active DB connections |
| `hikaricp.connections.idle` | Gauge | Idle DB connections |
| `hikaricp.connections.pending` | Gauge | Pending connection acquisitions |
| `hikaricp.connections.timeout.total` | Counter | Connection acquisition timeouts |
| `jvm.memory.used` | Gauge | JVM memory usage by area |
| `jvm.memory.max` | Gauge | Maximum JVM memory by area |
| `jvm.gc.pause` | Timer | GC pause durations |
| `jvm.threads.live` | Gauge | Live JVM threads |

---

## Accessing Metrics

- **Prometheus endpoint**: `GET /actuator/prometheus`
- **Spring Boot Metrics**: `GET /actuator/metrics/{name}`
- **Grafana**: `http://localhost:3000` (admin/admin in dev)
- **Prometheus UI**: `http://localhost:9090`
