# Event Contract Specification

## 1. Event Streaming Architecture
- **Transport**: Apache Kafka 3.x
- **Publishing Pattern**: Transactional Outbox Pattern (events are persisted atomically in PostgreSQL `outbox_events` and relayed asynchronously).
- **Delivery Guarantee**: At-Least-Once Delivery.
- **Consumer Processing**: Idempotent Consumers with dedicated consumer message deduplication tables.
- **Envelope Standard**: **CloudEvents-inspired internal event envelope** (v1.0).

---

## 2. CloudEvents-Inspired Internal Event Envelope

Every event published to Apache Kafka strictly conforms to the following JSON structure:

```json
{
  "eventId": "a7b3c2d1-e4f5-4a6b-8c9d-0e1f2a3b4c5d",
  "eventType": "PaymentSettled",
  "occurredAt": "2026-09-23T15:40:00.000Z",
  "aggregateType": "PAYMENT",
  "aggregateId": "5e1a3b8c-9d2e-4f7a-8b1c-3d5e7f9a1b3c",
  "schemaVersion": "1.0",
  "correlationId": "c4b3a987-e21b-4f90-8b65-685b882312a0",
  "causationId": "9a8b7c6d-5e4f-3a2b-1c0d-e9f8a7b6c5d4",
  "payload": {
    "paymentId": "5e1a3b8c-9d2e-4f7a-8b1c-3d5e7f9a1b3c",
    "payerAccountId": "7b8e5c3e-8f24-4f76-9289-53e9cb14c412",
    "payeeAccountId": "8f3e2b1a-4c5d-6e7f-8a9b-0c1d2e3f4a5b",
    "amountMinor": 5000,
    "feeAmountMinor": 150,
    "currency": "USD",
    "ledgerTransactionId": "6a2b4c8e-0f1a-3b5d-7e9c-1a3b5d7e9c1a",
    "providerReference": "ch_3MtwL2LkdIwHu7ix0snN00fn",
    "settledAt": "2026-09-23T15:40:00.000Z"
  }
}
```

### Envelope Field Definitions
| Field | Type | Description |
| :--- | :--- | :--- |
| `eventId` | `UUID` | Unique identifier for this discrete event instance. |
| `eventType` | `String` | Categorical event name (e.g. `PaymentSettled`). |
| `occurredAt` | `String (ISO 8601)` | Timestamp when the domain event occurred in UTC. |
| `aggregateType` | `String` | Originating domain aggregate (`PAYMENT`, `REFUND`, `ACCOUNT`). |
| `aggregateId` | `String` | Unique identifier of the originating aggregate root. |
| `schemaVersion` | `String` | Contract version token (e.g. `"1.0"`). |
| `correlationId` | `UUID` | Traces end-to-end operation across HTTP, DB, Kafka, and consumers. |
| `causationId` | `UUID` | Identifier of the command, message, or key that triggered this event. |
| `payload` | `JSON Object` | Domain-specific event details. |

---

## 3. Topic Topology & Partitioning

| Topic Name | Partition Key | Emitted Events | Purpose |
| :--- | :--- | :--- | :--- |
| `payment.events` | `payerAccountId` or `aggregateId` | `PaymentCreated`, `PaymentAuthorized`, `PaymentSettled`, `PaymentDeclined`, `PaymentFailed` | Lifecycle updates for payment authorization and settlement. |
| `refund.events` | `paymentId` | `RefundRequested`, `RefundSettled`, `RefundFailed` | Lifecycle updates for customer/merchant refunds. |
| `account.events` | `aggregateId` (`accountId`) | `AccountCreated`, `AccountFrozen`, `AccountUnfrozen`, `AccountClosed` | Account lifecycle and operational restriction changes. |
| `reconciliation.events` | `aggregateId` (`runId`) | `ReconciliationStarted`, `ReconciliationCompleted`, `DiscrepancyDetected` | Discrepancy reporting and operational alerting. |
| `notification.events` | `recipientUserId` | `NotificationRequested`, `NotificationDelivered` | Downstream notification delivery orchestration. |

### Partitioning & Ordering Guarantee
- Partitioning by `aggregateId` or `accountId` ensures that all events affecting a single entity or account arrive at the same Kafka partition in strict chronological order.
- Consumers processing a specific account will never process events out-of-order within that partition.

---

## 4. Consumer Idempotency & Error Handling Strategy

### 4.1 Consumer Message Deduplication & Transaction Atomicity
Kafka provides **at-least-once** delivery. Durable database-backed deduplication combined with an atomic database transaction provides **exactly-once logical database effects** for the consumer.
1. Every consumer maintains a database deduplication table:
```sql
CREATE TABLE consumed_messages (
    consumer_group VARCHAR(100) NOT NULL,
    message_id UUID NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (consumer_group, message_id)
);
```
2. The consumer executes deduplication recording and persistent consumer side-effects in the **SAME** atomic database transaction:
```
Kafka message
↓
BEGIN DATABASE TRANSACTION
↓
insert consumed_messages
↓
perform persistent consumer side-effect
↓
COMMIT
↓
Kafka offset acknowledgement
```
Committing deduplication independently with `REQUIRES_NEW` before the side-effect is prohibited: if the side-effect fails, both the side-effect and the deduplication marker roll back together, allowing Kafka retries to complete without phantom deduplication.

### 4.2 Dead-Letter Queue (DLQ) & Retry Policy
- **Transient Failures** (e.g. downstream email provider 503, database connection pool exhaustion):
  - Retried with exponential backoff (e.g. 1s, 2s, 4s, 8s up to 5 attempts).
- **Poison Pills / Fatal Failures** (e.g. unparseable JSON, unresolvable business state contradiction):
  - Published to corresponding Dead-Letter Topic (e.g. `payment.events.dlq`) with full error metadata, stack trace, and original headers.
  - Alert dispatched to Operations via Micrometer / Prometheus metrics.

---

## 5. Schema Evolution Rules
To guarantee zero consumer breakage during platform upgrades:
1. **Backward Compatibility**: New fields added to `payload` must be optional (`nullable` or have default values).
2. **Field Deletion Forbidden**: Fields cannot be renamed or removed within a major `schemaVersion`.
3. **Major Version Bump**: Incompatible payload restructuring requires bumping `schemaVersion` to `"2.0"` and publishing to a new topic (or dual-publishing during migration).
