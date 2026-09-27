# ADR-009: Transactional Outbox Pattern for Atomic State-to-Event Integration

## Status
Accepted

## Context
Phase 8 established Kafka event contracts, topic topologies, serialization, and consumer deduplication. However, direct Kafka publication from application services introduced an inherent reliability limitation:
```text
PostgreSQL COMMIT
        ↓
application crash
        ↓
Kafka publication may be lost
```
If an application node crashes or network fails after the database transaction commits but before `KafkaTemplate.send()` completes, the event is permanently lost, causing dual-write inconsistency between PostgreSQL and downstream event consumers.

Phase 9 resolves this dual-write problem by implementing the **Transactional Outbox Pattern**.

## Decisions

### 1. Why Transactional Outbox?
The Transactional Outbox pattern guarantees that a business state transition and its corresponding domain event commit atomically inside PostgreSQL. Because PostgreSQL is the sole financial source of truth, persisting the event into `outbox_events` within the identical database transaction guarantees that events are only published for committed state transitions (no phantom events), and every committed transition produces an event (no lost events).

### 2. Why PostgreSQL Outbox?
PostgreSQL provides ACID guarantees with row-level pessimistic locking (`SELECT ... FOR UPDATE SKIP LOCKED`). Storing the outbox in the same PostgreSQL database as payment and ledger tables allows the state change and outbox record insertion to share the same local database connection and transaction boundary with zero distributed overhead.

### 3. Why Not 2PC (Two-Phase Commit)?
Two-Phase Commit (2PC / XA) was rejected because:
- Distributed transactions introduce severe latency, high coordination overhead, and coordinator single points of failure.
- Kafka does not participate in XA transactions with relational databases.
- Network partitions during 2PC can leave database locks held indefinitely, causing cascading connection pool exhaustion.

### 4. Why Not Kafka Transactions as the Primary Financial Mechanism?
Kafka transactions are designed for stream processing pipelines (read-process-write across Kafka topics). Kafka is strictly an asynchronous transport medium in this platform and is NEVER the financial source of truth. Using Kafka transactions for financial ledger state would violate Invariant 1 (PostgreSQL as the sole source of truth).

### 5. Why Is Kafka Still At-Least-Once?
Network communication between the outbox relay and Kafka brokers cannot achieve true distributed exactly-once semantics without a distributed coordinator. If the outbox relay publishes an event to Kafka and the broker writes it, but the application crashes before the outbox status update (`PUBLISHED`) commits to PostgreSQL, the relay will re-publish the event upon restart. Thus, Kafka publication remains **at-least-once**.

### 6. Why Duplicate Kafka Publication Is Acceptable
Duplicate Kafka publication is fully acceptable because downstream consumers (such as `PaymentEventAuditConsumer` from Phase 8) implement durable, database-backed deduplication (`consumed_messages`) participating in the consumer's atomic database transaction. The consumer detects duplicate event deliveries, logs them, and skips re-executing business side effects, guaranteeing **exactly-once logical database effects**.

### 7. How Concurrent Relay Workers Are Coordinated
Multiple relay workers are coordinated using PostgreSQL's `SELECT ... FOR UPDATE SKIP LOCKED` query:
```sql
SELECT * FROM outbox_events
WHERE (status = 'PENDING' AND next_attempt_at <= NOW())
   OR (status = 'PROCESSING' AND locked_at < NOW() - INTERVAL '30 seconds')
ORDER BY next_attempt_at ASC, created_at ASC
LIMIT 50
FOR UPDATE SKIP LOCKED;
```
When Worker A claims a batch, PostgreSQL places a row lock on those rows. When concurrent Worker B executes simultaneously, `SKIP LOCKED` instructs PostgreSQL to bypass the locked rows and immediately select the next available rows. This eliminates lock contention, avoids deadlocks, and scales horizontally across multiple application instances.

### 8. How Crashed Workers Are Recovered
When a worker claims an outbox batch, it sets `status = 'PROCESSING'`, `locked_by = workerId`, and `locked_at = NOW()`. If the worker process crashes before completing publication, the row remains in `PROCESSING`. The claiming query explicitly includes `OR (status = 'PROCESSING' AND locked_at < NOW() - INTERVAL '30 seconds')`. After the 30-second lease expires, any surviving worker automatically re-claims the orphaned record and completes publication.

### 9. Why the Phase 6 Financial Transaction Boundary Remains Unchanged
The frozen Phase 6 financial transaction boundary in `LedgerService.settlePaymentWithLedger` is preserved:
```text
external provider call
        ↓
BEGIN database transaction
        ↓
lock financial accounts (deterministic order)
        ↓
authoritative ledger balance calculation
        ↓
ledger posting (double-entry debit + credit)
        ↓
materialized balance update
        ↓
payment settlement (CAPTURING -> SETTLED)
        ↓
INSERT outbox_events (PaymentSettled event)
        ↓
COMMIT
```
Kafka publication is never invoked inside the database transaction. The database transaction commits independently of Kafka availability. If Kafka is completely down, financial settlement succeeds without interruption, and the outbox event remains durably queued in `outbox_events` until Kafka recovers.

## Consequences

- **Positive**:
  - 100% elimination of dual-write inconsistency between PostgreSQL and Kafka.
  - Zero financial availability dependence on Kafka; broker downtime does not affect payment settlement.
  - Linear horizontal scalability for outbox relays via `SKIP LOCKED`.
  - Automatic fault tolerance and crash recovery via distributed leases.
  - Full auditability: `outbox_events` preserves the exact sequence and timestamps of emitted domain events.
- **Negative / Trade-offs**:
  - Additional database write I/O on transaction commit.
  - Polling relay incurs small query overhead against the partial index.
  - Eventual consistency delay (typically 50-200ms) between DB commit and Kafka delivery.

## Alternatives Considered
- *Change Data Capture (Debezium)*: Rejected for Phase 9 to maintain modular monolith simplicity without introducing Kafka Connect and external daemon dependencies. Can be adopted in future high-throughput phases without changing the outbox table schema.
- *In-Memory Event Bus*: Rejected because in-memory queues lose events on process termination.
