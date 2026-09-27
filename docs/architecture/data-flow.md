# Data Flow & Asynchronous Integration Topologies

**Governing Phase**: Phase 21 — Flagship Portfolio Packaging & GitHub Release  
**Domain**: Transactional Outbox, Event Streaming, and Reconciliation Data Pipelines  
**Status**: VERIFIED DATA FLOWS  

---

## 1. Transactional Outbox Relay Pipeline

The platform uses the **Transactional Outbox Pattern** to solve the dual-write problem across PostgreSQL and Apache Kafka:

```mermaid
graph TB
    subgraph Database_Transaction["Single Atomic ACID Database Transaction"]
        Tx["Business Logic Mutation<br/>(Payments, Ledger, Accounts)"]
        EventRecord["Outbox Event Insert<br/>INSERT INTO outbox_events<br/>(status: 'PENDING', payload, retry_count: 0)"]
        Tx -->|Both Written Together| DB[("PostgreSQL 16 Engine")]
        EventRecord -->|Both Committed Together| DB
    end

    subgraph Polling_Worker["Outbox Relay Scheduler (Runs every 1000ms)"]
        Poller["SELECT ... FROM outbox_events<br/>WHERE status = 'PENDING'<br/>ORDER BY created_at ASC<br/>LIMIT 50 FOR UPDATE SKIP LOCKED"]
    end

    subgraph Kafka_Ecosystem["Apache Kafka Cluster"]
        Topic["Topic: payment.events<br/>(acks=all, snappy compression)"]
        DLT["Topic: payment.events.DLT<br/>(Poison Pill Dead Letter Topic)"]
    end

    subgraph Consumers["Downstream Consumer Groups"]
        AuditConsumer["PaymentEventAuditConsumer<br/>(Idempotent Deduplication)"]
        NotifyConsumer["NotificationConsumer<br/>(Customer/Merchant Email/Webhook)"]
    end

    DB -->|Poll 50 Events| Poller
    Poller -->|Publish Event| Topic
    Topic -->|Event Received| AuditConsumer
    Topic -->|Event Received| NotifyConsumer
    Poller -->|Publish Failure: Retry++| DB
    Poller -->|Max Retries Exceeded| DLT
    Poller -->|Success: UPDATE status='PUBLISHED'| DB
```

### Outbox Guarantees:
1. **Zero Dual-Write Race**: If database transaction rolls back, no outbox event is persisted.
2. **`FOR UPDATE SKIP LOCKED`**: Enables multiple concurrent outbox workers to poll distinct batches without lock contention or thread collisions.
3. **Lease Expiry Recovery**: If a worker node crashes mid-relay, its in-flight events are unlocked automatically after 30 seconds (`lease_until < NOW()`) and re-claimed by surviving threads.

---

## 2. Reconciliation Engine Data Flow

The reconciliation engine discovers and resolves discrepancies between internal platform states and external payment providers:

```mermaid
graph TD
    A["Discrepancy Discovery Query<br/>SELECT ... FROM payments<br/>WHERE status = 'PENDING_RECONCILIATION'"] --> B["Reconciliation Engine Worker"]
    B --> C["Fetch External Settlement Report<br/>(Provider Ingest / Mock Gateway)"]
    C --> D{"Match Transaction Reference & Amount?"}
    
    D -->|Match Confirmed: Provider Settled| E["Create ReconciliationCase(status: RESOLVED)"]
    E --> F["Post Compensating Ledger Adjustment<br/>(DEBIT Merchant / CREDIT Customer)"]
    F --> G["Transition Payment to SETTLED"]
    
    D -->|Match Confirmed: Provider Voided| H["Create ReconciliationCase(status: VOIDED)"]
    H --> I["Transition Payment to FAILED<br/>(Zero Ledger Mutation)"]
    
    D -->|No Match / Disputed| J["Create ReconciliationCase(status: MANUAL_REVIEW)"]
    J --> K["Alert Operator / Admin Dashboard"]
```

### Reconciliation Invariants:
- **Historical Immutability**: The reconciliation engine **never mutates or deletes** existing posted ledger entries.
- **Compensating Transactions**: All financial corrections are executed by appending new, fully balanced double-entry transactions (`SUM(debit) == SUM(credit)`).
