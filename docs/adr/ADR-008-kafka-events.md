# ADR-008: Kafka Event Infrastructure and Contracts

## Status
Accepted

## Context
Phase 8 introduces production-grade Kafka event streaming into the Distributed Payment & Ledger Platform. The platform requires asynchronous event communication between domains (e.g. notifying consumers of payment settlement, account freeze, or auditing), while strictly preserving core financial invariants:
1. PostgreSQL remains the sole authoritative source of financial truth.
2. Kafka is strictly an asynchronous integration and event transport medium; Kafka has zero financial authority.
3. The Phase 6 financial transaction boundary (`LedgerService.settlePaymentWithLedger`) is frozen: external provider calls remain outside the DB transaction, and the database transaction commits independently of Kafka.
4. Transactional Outbox is deferred to Phase 9.

## Decisions

### 1. Kafka's Architectural Role & Boundary
- Kafka provides decoupled, asynchronous publish-subscribe event distribution for downstream integration.
- Kafka **does NOT participate** in the financial database transaction.
- Consumers must never calculate, assert, or mutate authoritative balances based on Kafka messages.

### 2. Standardized CloudEvents-Inspired Event Envelope
All events published to Kafka conform to the immutable `EventEnvelope<T>` schema defined in `docs/product/08-event-contract.md`:
```json
{
  "eventId": "UUID",
  "eventType": "String",
  "occurredAt": "ISO-8601 UTC Instant",
  "aggregateType": "PAYMENT | ACCOUNT | LEDGER",
  "aggregateId": "String",
  "schemaVersion": "1.0",
  "correlationId": "UUID",
  "causationId": "String",
  "payload": { ... }
}
```

### 3. Event Immutability & Versioning
- Events represent immutable business facts that have already occurred.
- Every envelope defines an explicit `schemaVersion` (e.g. `"1.0"`).
- Schema evolution must be backward-compatible: new fields are optional; fields are never deleted within the same major version; unknown versions are rejected deterministically and routed to dead-letter handling.

### 4. Topic Strategy & Partitioning
- **Topic Naming**: Hierarchical dot-notation by domain:
  - `payment.events`: Payment lifecycle updates (`PaymentCreated`, `PaymentSettled`, `PaymentDeclined`, `PaymentFailed`, `PaymentPendingReconciliation`).
  - `account.events`: Account lifecycle changes (`AccountCreated`, `AccountFrozen`, `AccountUnfrozen`, `AccountClosed`).
  - Corresponding dead-letter topics with `.dlq` suffix: `payment.events.dlq`, `account.events.dlq`.
- **Partition Key**:
  - `payment.events` partitions on `payerAccountId` or `aggregateId` (`paymentId`).
  - `account.events` partitions on `aggregateId` (`accountId`).
  - Guarantees strict chronological ordering for events affecting the same entity without global bottlenecking.

### 5. Deterministic Serialization & Money Representation
- Serialization uses Jackson with JavaTimeModule.
- Monetary amounts are strictly formatted as integer minor units (`amountMinor: long`, `feeAmountMinor: long`) with standard 3-character ISO-4217 currency strings (`currency: String`). Floating-point numbers are prohibited.

### 6. Producer Abstraction & Production Call Sites
- An application-level `EventPublisher` interface decouples domain code from Spring Kafka's `KafkaTemplate`.
- Direct publication is wired into production services outside the database transactions:
  - `PaymentService.createPayment`: Emits `PaymentCreated`, `PaymentSettled`, `PaymentDeclined`, `PaymentFailed`, and `PaymentPendingReconciliation` to `payment.events` partitioned by `payerAccountId`.
  - `AccountService.freezeAccount` / `unfreezeAccount`: Emits `AccountFrozen` and `AccountUnfrozen` to `account.events` partitioned by `accountId`.
- All direct publication call sites catch Kafka transport exceptions with warning logs to guarantee that Kafka unavailability never compromises committed PostgreSQL financial transactions.

### 7. Consumer Idempotency, Transaction Atomicity & Terminology
- **Precise Terminology**: Kafka provides at-least-once delivery. Durable database-backed deduplication combined with an atomic database transaction provides exactly-once logical database effects for the consumer.
- **Transaction Atomicity Pattern**: Deduplication records MUST participate in the SAME PostgreSQL transaction as the persistent consumer side-effects:
  ```
  Kafka message
  ↓
  BEGIN DATABASE TRANSACTION
  ↓
  insert consumed_messages
  ↓
  perform persistent consumer side-effect (e.g. insert payment_event_audits)
  ↓
  COMMIT
  ↓
  Kafka offset acknowledgement
  ```
- **Prohibition of `REQUIRES_NEW` on Deduplication**: Committing deduplication markers independently using `REQUIRES_NEW` before consumer side-effects is strictly prohibited. If the consumer side-effect fails or the application crashes, an independently committed deduplication marker would cause Kafka retries to falsely detect a duplicate and permanently skip the lost side-effect (phantom deduplication).
- Participating in the same transaction guarantees that if the side-effect fails, both the side-effect and the deduplication marker roll back together. A subsequent Kafka retry safely re-executes and commits both.

### 8. Failure Handling & Dead-Letter Topics (DLT)
- Transient exceptions are retried with backoff.
- Fatal errors (unparseable JSON, malformed envelope, unsupported major schema version) bypass retries and are routed immediately to the corresponding `.dlq` topic using `DeadLetterPublishingRecoverer` with error headers (`X-Exception-Message`, `X-Exception-Stacktrace`).

### 9. Observability & Security
- MDC context is populated from `EventEnvelope.correlationId` and Kafka header `X-Correlation-ID`.
- No sensitive customer credentials, passwords, raw card numbers, CVVs, or JWTs are permitted in event payloads.

### 10. Phase 8 Direct-Publish Reliability Limitation & Phase 9 Boundary
- **Intentional Limitation**: Phase 8 establishes Kafka event contracts, producers, consumers, deduplication and failure handling. Direct publication has an intentional reliability limitation:
  ```
  PostgreSQL COMMIT
  ↓
  application crash
  ↓
  Kafka publication may be lost
  ```
- Reliable atomic database-state-to-event publication across process crashes is intentionally deferred to **Phase 9 Transactional Outbox**.
- Phase 8 strictly excludes outbox tables (`outbox_events`), outbox workers, outbox polling relays, CDC, Debezium, and Kafka Connect.

## Consequences

- **Positive**:
  - Clean asynchronous decoupling of downstream consumers.
  - Complete immunity to duplicate deliveries and partition rebalancing spikes.
  - Strict preservation of double-entry ledger invariants and transaction boundaries.
  - Observability and traceability guaranteed across HTTP, database, and messaging boundaries.
  - Proved atomicity and retry safety: side-effect failure rolls back deduplication marker cleanly.
- **Negative / Trade-offs**:
  - Each consumer group incurs a database write into `consumed_messages` for deduplication.
  - Direct publication in Phase 8 can lose events during an application crash between DB commit and Kafka send (resolved in Phase 9).

## Rejected Alternatives
1. *In-Memory or Redis-Only Deduplication*: Rejected because in-memory state does not survive crashes/restarts, and Redis is not the authoritative source of truth.
2. *Committing Deduplication with REQUIRES_NEW*: Rejected because failure during side-effect leaves phantom deduplication marker, permanently losing business processing upon retry.
3. *Kafka Streams for deduplication*: Rejected as excessive overhead for the modular monolith architecture.
4. *Kafka Transactions across DB and Kafka*: Rejected because 2PC/distributed transactions introduce severe availability risks and violate frozen Phase 6 rules.
