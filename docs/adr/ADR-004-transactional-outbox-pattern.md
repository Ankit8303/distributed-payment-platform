# ADR-004: Transactional Outbox Pattern for Asynchronous Integration

## Status
Accepted

## Context
When a financial transaction completes (e.g. payment settled, account frozen), downstream systems (notifications, fraud intelligence, data warehouse) must be notified via Kafka events. If the application directly publishes to Kafka after committing to PostgreSQL, a network blip or crash during Kafka publication causes the event to be lost ("dual-write inconsistency"). Conversely, publishing to Kafka before database commit risks publishing events for transactions that subsequently roll back ("phantom events").

## Decision
1. **Transactional Outbox Table**:
   - Persist events into an `outbox_events` table inside the *exact same* PostgreSQL transaction that mutates business entities (e.g. payment status, double-entry ledger rows).
   - This ensures atomic consistency: either both business state changes and outbox records commit together, or neither commits.
2. **Outbox Relay Dispatcher**:
   - An asynchronous background scheduler polls pending outbox entries using `SELECT ... FOR UPDATE SKIP LOCKED` (or Change Data Capture in future high-throughput scaling) and publishes them to Apache Kafka.
   - Upon successful broker acknowledgment, the outbox record is marked `PUBLISHED` (with `published_at` timestamp) or pruned according to retention rules.
   - In case of broker unavailability, the poller uses exponential backoff and retries without dropping events.
3. **Delivery & Consumption Contract**:
   - Publishing guarantees **At-Least-Once** delivery.
   - All Kafka consumers must be inherently idempotent, leveraging local consumer deduplication tables or checking business entity state before applying side effects.

## Alternatives Considered
1. **Direct In-Band Kafka Publishing in Spring `@Transactional`**:
   - *Rejected*: Direct publish before commit causes phantom events if the DB rolls back; publish after commit causes lost events if the application crashes prior to publication.
2. **Distributed 2PC / XA Transactions**:
   - *Rejected*: High latency, low availability, brittle coordinator recovery, and poor support across modern cloud infrastructure and Kafka brokers.

## Consequences
- **Positive**:
  - Elimination of dual-write inconsistency and phantom messages.
  - Asynchronous event emission decoupled from synchronous HTTP response latency.
  - Durable event buffer during transient Kafka broker outages.
- **Negative / Trade-offs**:
  - Event publishing is eventually consistent (typically tens to hundreds of milliseconds delay).
  - Additional database write I/O on transaction commit and polling load.

## Validation
- Chaos / failure test: Inject Kafka broker outage while processing payments; verify payments complete, outbox fills up, and all events are flushed to Kafka upon broker recovery without message loss.
