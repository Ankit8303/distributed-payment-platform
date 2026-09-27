# Observability Runbooks — Payment & Ledger Platform

> **CRITICAL INVARIANT**: Administrative operations MUST NEVER directly mutate financial truth.
> Observability tools are for investigation, not remediation. All financial corrections
> MUST go through the authorized Admin API with immutable audit trails.

---

## Runbook Index

| Alert | Severity | Runbook |
|---|---|---|
| `HighHttpErrorRate` | CRITICAL | [#http-5xx-rate](#http-5xx-rate) |
| `HighHttpP99Latency` | WARNING | [#http-p99-latency](#http-p99-latency) |
| `HighPaymentFailureRate` | CRITICAL | [#payment-failure-rate](#payment-failure-rate) |
| `PaymentPendingReconciliationSpike` | CRITICAL | [#pending-reconciliation-spike](#pending-reconciliation-spike) |
| `LedgerUnbalancedTransaction` | CRITICAL | [#unbalanced-transaction](#unbalanced-transaction) |
| `LedgerInvariantFailure` | CRITICAL | [#ledger-invariant-failure](#ledger-invariant-failure) |
| `HighLedgerTransactionFailureRate` | WARNING | [#ledger-transaction-failure](#ledger-transaction-failure) |
| `OutboxPendingBacklog` | WARNING | [#outbox-backlog](#outbox-backlog) |
| `OutboxCriticalBacklog` | CRITICAL | [#outbox-backlog](#outbox-backlog) |
| `OutboxOldestEventAge` | WARNING | [#outbox-oldest-age](#outbox-oldest-age) |
| `OutboxHighFailureRate` | CRITICAL | [#outbox-failure-rate](#outbox-failure-rate) |
| `KafkaConsumerLagHigh` | WARNING | [#kafka-consumer-lag](#kafka-consumer-lag) |
| `KafkaConsumerLagCritical` | CRITICAL | [#kafka-consumer-lag](#kafka-consumer-lag) |
| `KafkaDltEventsDetected` | WARNING | [#kafka-dlt](#kafka-dlt) |
| `HikariConnectionPoolSaturation` | WARNING | [#hikari-saturation](#hikari-saturation) |
| `HikariConnectionTimeout` | CRITICAL | [#hikari-timeout](#hikari-timeout) |
| `RedisOperationFailureRate` | WARNING | [#redis-failures](#redis-failures) |
| `JvmHeapMemoryPressure` | WARNING | [#jvm-heap](#jvm-heap) |
| `JvmGcPauseDurationHigh` | WARNING | [#jvm-gc](#jvm-gc) |
| `ReconciliationManualReviewRequired` | WARNING | [#reconciliation-manual-review](#reconciliation-manual-review) |
| `NotificationHighFailureRate` | WARNING | [#notification-failures](#notification-failures) |
| `ApplicationDown` | CRITICAL | [#application-down](#application-down) |

---

## http-5xx-rate

**Alert**: `HighHttpErrorRate`
**Threshold**: HTTP 5xx error rate > 5% for 2 minutes

**Investigation Steps**:
1. Check structured logs: `grep '"level":"ERROR"' application.log | jq .`
2. Identify which routes are failing: review `http.server.requests` by `uri` and `status`
3. Check Grafana → Platform Overview → HTTP Request Rate panel
4. Check database connectivity: `GET /actuator/health/readiness`
5. Check Kafka connectivity: look for Kafka producer timeouts in logs

**DO NOT**: Directly modify database records to resolve.

---

## http-p99-latency

**Alert**: `HighHttpP99Latency`
**Threshold**: p99 HTTP latency > 2 seconds for 3 minutes

**Investigation Steps**:
1. Identify slow routes via Grafana → Platform Overview → HTTP p99 Latency panel
2. Check HikariCP pool saturation (pool exhaustion → slow DB queries)
3. Check JVM GC pressure (GC pauses cause latency spikes)
4. Check Kafka producer `linger.ms` and producer lag

---

## payment-failure-rate

**Alert**: `HighPaymentFailureRate`
**Threshold**: Payment failure rate > 10% for 3 minutes

**Investigation Steps**:
1. Check payment failure reasons: Grafana → Payment Domain → Payment Failures by Reason
2. Look for `PROVIDER_DECLINED`, `INSUFFICIENT_FUNDS`, `ACCOUNT_FROZEN` in logs
3. Check provider connectivity: mock provider logs / external provider dashboard
4. Check `account_entity` status — accounts may have been mass-frozen
5. If provider is degraded, consider circuit-breaking inbound payment requests

**DO NOT**: Manually update `payment_entity.status` in the database.

---

## pending-reconciliation-spike

**Alert**: `PaymentPendingReconciliationSpike`
**Threshold**: Payments entering PENDING_RECONCILIATION > 0.1/s for 2 minutes

**Investigation Steps**:
1. Check for DB transaction failures after successful provider capture
2. Check for provider gateway timeouts (`GATEWAY_TIMEOUT` in logs)
3. Verify reconciliation service is running: `GET /actuator/health`
4. Review `payment_entity` records with `status = PENDING_RECONCILIATION`
5. Use Admin API to investigate specific payments: `GET /api/admin/payments/{id}`

**Resolution**: The reconciliation engine (Phase 12) will automatically resolve most cases.
Manual intervention via Admin API is only needed for persistent outliers.

---

## unbalanced-transaction

**Alert**: `LedgerUnbalancedTransaction`
**Threshold**: ANY occurrence — fires immediately, 0 minute `for` duration

> ⚠️ **HIGHEST SEVERITY**: This indicates a fundamental financial integrity violation.

**Investigation Steps**:
1. IMMEDIATELY escalate to engineering leadership and financial operations
2. Identify the unbalanced transaction via: `SELECT * FROM ledger_transaction WHERE posted_at > NOW() - INTERVAL '1 hour'`
3. Verify sum of entries: `SELECT SUM(CASE direction WHEN 'DEBIT' THEN amount_minor ELSE -amount_minor END) FROM ledger_entry WHERE transaction_id = ?`
4. Cross-reference with payment/refund/payout records
5. Review application logs for the `ledger.unbalanced.transaction` metric emission site

**CRITICAL DO NOT**:
- Do NOT directly UPDATE any `ledger_entry` or `ledger_transaction` records
- Do NOT delete records
- All remediation MUST go through the authorized financial adjustment Admin API

---

## ledger-invariant-failure

**Alert**: `LedgerInvariantFailure`
**Threshold**: ANY occurrence — fires immediately

**Investigation Steps**:
1. Check logs for `LedgerService` invariant failure context
2. Identify the invariant type from the `type` label in the metric
3. Verify account balances vs ledger entry sums
4. Use Admin API to investigate: `GET /api/admin/accounts/{id}/ledger`

---

## ledger-transaction-failure

**Alert**: `HighLedgerTransactionFailureRate`
**Threshold**: > 5% of ledger transactions failing for 3 minutes

**Investigation Steps**:
1. Check HikariCP pool — DB unreachable causes mass ledger failures
2. Check for `DataIntegrityViolationException` in logs (duplicate posting attempt)
3. Check for `INSUFFICIENT_FUNDS` errors — may indicate balance calculation issues

---

## outbox-backlog

**Alert**: `OutboxPendingBacklog` / `OutboxCriticalBacklog`
**Thresholds**: > 500 (warning), > 2000 (critical)

**Investigation Steps**:
1. Check `OutboxRelayScheduler` logs for publish errors
2. Verify Kafka broker connectivity: `kafka-broker-api-versions --bootstrap-server localhost:9092`
3. Check `outbox_event` table: `SELECT status, COUNT(*) FROM outbox_event GROUP BY status`
4. Check oldest event: `SELECT MIN(created_at) FROM outbox_event WHERE status = 'PENDING'`
5. If Kafka is unavailable, events accumulate safely — they will drain once Kafka recovers

---

## outbox-oldest-age

**Alert**: `OutboxOldestEventAge`
**Threshold**: Oldest event > 5 minutes old

**Investigation Steps**:
1. Check relay scheduler is running (not disabled by feature flag)
2. Check for lease expiry issues — events stuck in PROCESSING state
3. Query: `SELECT id, event_type, attempt_count, next_attempt_at FROM outbox_event WHERE status = 'PROCESSING' AND updated_at < NOW() - INTERVAL '10 minutes'`

---

## outbox-failure-rate

**Alert**: `OutboxHighFailureRate`
**Threshold**: Outbox publish failure rate > 10%

**Investigation Steps**:
1. Check Kafka producer connectivity and broker availability
2. Check `attempt_count` values — events at max retries indicate persistent failure
3. Review `outbox_event` records with `status = 'FAILED'`

---

## kafka-consumer-lag

**Alert**: `KafkaConsumerLagHigh` / `KafkaConsumerLagCritical`
**Thresholds**: > 1000 (warning), > 5000 (critical)

**Investigation Steps**:
1. Check `PaymentEventAuditConsumer` for processing errors
2. Check for `UnsupportedEventVersionException` — schema version mismatch routing events to DLQ
3. Check consumer thread pool size vs partition count
4. Verify consumer group is healthy: `kafka-consumer-groups --describe --group payment-audit-group`

---

## kafka-dlt

**Alert**: `KafkaDltEventsDetected`
**Threshold**: ANY DLT message for 0 minutes

**Investigation Steps**:
1. Read messages from the DLT topic
2. Look for schema version mismatches or deserialization errors
3. Fix the root cause (typically a schema incompatibility or invalid payload)
4. Replay DLT messages after fix if needed (use Kafka consumer position reset)

---

## hikari-saturation

**Alert**: `HikariConnectionPoolSaturation`
**Threshold**: > 90% pool utilization for 3 minutes

**Investigation Steps**:
1. Check for long-running transactions: `SELECT pid, now() - pg_stat_activity.query_start AS duration, query FROM pg_stat_activity WHERE state = 'active' AND query_start < NOW() - INTERVAL '30 seconds'`
2. Check for blocking locks: `SELECT * FROM pg_locks WHERE granted = false`
3. Consider increasing `hikari.maximum-pool-size` (requires deployment)

---

## hikari-timeout

**Alert**: `HikariConnectionTimeout`
**Threshold**: > 5 connection timeouts in 5 minutes

**Investigation Steps**:
1. Check PostgreSQL server connectivity
2. Check for long-running queries blocking connections
3. Check application thread count vs pool size ratio

---

## redis-failures

**Alert**: `RedisOperationFailureRate`
**Threshold**: > 0.5 failures/s for 3 minutes

**Investigation Steps**:
1. Test Redis connectivity: `redis-cli -h localhost ping`
2. Check Redis memory usage: `redis-cli info memory`
3. Idempotency and rate-limiting features degrade gracefully when Redis is unavailable
4. Review `RedisConfig` timeout settings

---

## jvm-heap

**Alert**: `JvmHeapMemoryPressure`
**Threshold**: Heap > 85% for 5 minutes

**Investigation Steps**:
1. Check for memory leaks in metric gauges (unbounded cardinality violation)
2. Review recent deployments for new cache structures
3. Generate heap dump: `jmap -dump:format=b,file=heap.hprof <pid>`
4. Analyze with Eclipse MAT or similar

---

## jvm-gc

**Alert**: `JvmGcPauseDurationHigh`
**Threshold**: Average GC pause > 500ms for 3 minutes

**Investigation Steps**:
1. Check heap utilization (high heap → more frequent/longer GC)
2. Consider switching to ZGC (`-XX:+UseZGC`) for lower pause times
3. Review GC log output for promotion failures

---

## reconciliation-manual-review

**Alert**: `ReconciliationManualReviewRequired`
**Threshold**: Any manual review items for 1 minute

**Investigation Steps**:
1. Use Admin API: `GET /api/admin/reconciliation?status=MANUAL_REVIEW`
2. Cross-reference with provider transaction records
3. If provider-side confirmed: use financial adjustment Admin API to correct
4. All corrections must be immutably logged in the admin audit trail

---

## notification-failures

**Alert**: `NotificationHighFailureRate`
**Threshold**: > 15% notification failure rate for 5 minutes

**Investigation Steps**:
1. Check notification provider connectivity (SMTP, SMS gateway, webhook endpoints)
2. Review `notification_attempt` table for error patterns
3. Check retry queue backlog
4. Note: Notification failures do NOT affect financial correctness

---

## application-down

**Alert**: `ApplicationDown`
**Threshold**: Scrape target unreachable for 1 minute

**Investigation Steps**:
1. Check if the application container/process is running
2. Check liveness probe: `GET /actuator/health/liveness`
3. Check JVM crash logs / OOM killer logs
4. Restart the application if confirmed crashed — all financial state is in PostgreSQL

---

## readiness-probe

**Alert**: `ReadinessProbeFailure`
**Threshold**: Readiness probe failing for 2 minutes

**Investigation Steps**:
1. Check `GET /actuator/health/readiness` response body
2. Identify which health indicator is failing (DB, Kafka, Redis)
3. Check infrastructure service connectivity
4. Application will stop receiving traffic from load balancer automatically
