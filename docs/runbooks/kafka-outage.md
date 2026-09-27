# Operational Runbook: Kafka Outage & Event Pipeline Degradation

**Severity:** SEV-2  
**Target Subsystem:** Apache Kafka Cluster / Transactional Outbox Relay / Consumer Groups

---

## 1. Symptoms
- Webhook notifications and asynchronous event consumers cease processing.
- Application logs showing `KafkaException`, `TimeoutException`, or `DisconnectException`.
- Database table `outbox_events` accumulating records in `PENDING` status.

## 2. Detection
- Alert: `OutboxBacklogGrowing` (threshold: > 500 pending events older than 2 minutes).
- Alert: `KafkaConsumerLagHigh` (threshold: > 1,000 records).
- Prometheus metric: `kafka_consumer_lag{topic="payment-events"} > 500`.

## 3. Diagnosis
- Inspect Kafka broker health and cluster quorum status.
- Check broker disk utilization and partition leader elections.
- Query PostgreSQL outbox table to monitor accumulation:
  ```sql
  SELECT status, count(*), min(created_at) FROM outbox_events GROUP BY status;
  ```

## 4. Commands
```bash
# Check Kafka topic details
kafka-topics.sh --bootstrap-server $KAFKA_BROKER --describe --topic payment-events

# Check consumer group lag
kafka-consumer-groups.sh --bootstrap-server $KAFKA_BROKER --describe --group payment-ledger-consumers
```

## 5. Safe Actions
- Because the system uses the **Transactional Outbox Pattern**, financial transactions commit safely in PostgreSQL even when Kafka is 100% offline.
- Allow outbox relay worker to back off and pause dispatching until Kafka broker recovers.
- Restart unhealthy Kafka brokers sequentially (rolling restart).

## 6. Unsafe Actions
- **NEVER** delete uncommitted events from the `outbox_events` table.
- **NEVER** bypass or delete consumer group offsets without understanding replay impact.
- **NEVER** disable outbox polling permanently.

## 7. Rollback
- If a schema evolution on event payloads caused consumer serialization failures, roll back consumer application.

## 8. Verification
- Verify brokers are in healthy sync: `kafka-topics.sh ...` reports `Isr` matching partition replicas.
- Verify `outbox_events` pending count drains down to 0:
  `SELECT count(*) FROM outbox_events WHERE status = 'PENDING';`
- Verify consumer lag decreases to 0.

## 9. Post-Incident Checks
- Verify consumer idempotency: ensure no downstream systems received duplicate double-processed actions.
