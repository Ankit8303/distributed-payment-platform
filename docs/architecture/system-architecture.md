# System Architecture & Component Topology

**Governing Phase**: Phase 21 — Flagship Portfolio Packaging & GitHub Release  
**Architecture Pattern**: Modular Monolith with Transactional Outbox & Event-Driven Transport  
**Primary Engine**: Java 21 LTS, Spring Boot 3.3.4, PostgreSQL 16  

---

## 1. High-Level Architecture Overview

The Distributed Payment & Ledger Platform is engineered as a **high-throughput, modular monolith** that balances the operational simplicity of a single deployable unit with strict internal domain boundaries and event-driven decoupling. 

PostgreSQL serves as the **sole authoritative financial source of truth**. All account balances, ledger journals, idempotency tokens, and outbox events are committed transactionally to PostgreSQL. Auxiliary systems (Apache Kafka, Redis) provide event streaming and read caching without holding financial authority.

```mermaid
graph TB
    subgraph Client_Layer["Ingress & Client Boundary"]
        Client["Web / Mobile / Merchant Client"]
    end

    subgraph Security_Idempotency["Security & Gateway Boundary"]
        Security["Spring Security 6.3<br/>(JWT HMAC-SHA256, RBAC)"]
        Idempotency["Idempotency Filter<br/>(uq_idempotency_actor_op_key)"]
    end

    subgraph Modular_Monolith["Spring Boot 3.3.4 Modular Monolith"]
        PaymentService["Payment Domain<br/>(State Machine Engine)"]
        AccountService["Account Domain<br/>(Deterministic Locking)"]
        LedgerEngine["Ledger Engine<br/>(Double-Entry Invariant)"]
        OutboxRelay["Transactional Outbox Relay<br/>(FOR UPDATE SKIP LOCKED)"]
        Reconciliation["Reconciliation Engine<br/>(Discrepancy Resolver)"]
        NotificationWorker["Notification Consumer<br/>(Templates & Webhooks)"]
    end

    subgraph Auxiliary_Cache["Auxiliary Caching (Non-Authoritative)"]
        Redis["Redis 7.2<br/>(Account Cache & Rate Limiting)"]
    end

    subgraph Authoritative_Store["Authoritative Financial Source of Truth"]
        Postgres[(PostgreSQL 16.15<br/>Accounts, Payments, Ledger Entries,<br/>Outbox, Idempotency Records)]
    end

    subgraph Event_Transport["Asynchronous Event Streaming"]
        Kafka["Apache Kafka 7.6.0 (KRaft)<br/>Topics: payment.events, notification.events"]
    end

    subgraph External_Boundary["External Providers"]
        Gateway["External Payment Gateway<br/>(Simulated / REST Provider)"]
        WebhookClient["Merchant Webhook Receiver<br/>(SSRF-Protected)"]
    end

    Client -->|HTTPS REST| Security
    Security --> Idempotency
    Idempotency -->|Cache Check| Redis
    Idempotency --> PaymentService
    PaymentService -->|Out-of-band Call| Gateway
    PaymentService -->|UUID Lock Ordering| AccountService
    PaymentService -->|Atomic Transaction| LedgerEngine
    LedgerEngine -->|Post Balanced Entry| Postgres
    PaymentService -->|Enqueue Event| OutboxRelay
    OutboxRelay -->|Atomic Write| Postgres
    OutboxRelay -->|Poll & Claim 210 eps| Kafka
    Kafka -->|Topic Consume| NotificationWorker
    Kafka -->|Topic Consume| Reconciliation
    NotificationWorker -->|SSRF-Filtered Delivery| WebhookClient
```

---

## 2. Component Directory & Domain Boundaries

The application enforces strict package boundaries under `com.paymentledger`:

| Domain Module | Primary Responsibility | Architectural Guarantee |
|---|---|---|
| `com.paymentledger.auth` | User authentication, token issuance, BCrypt password hashing. | Refresh-token rotation; single-use replay protection; zero token leakage. |
| `com.paymentledger.account` | Account lifecycle, balance queries, currency validation. | Non-overdraft check constraints; cached reads degrade to DB fallback. |
| `com.paymentledger.payment` | Payment state machine (`CREATED`..`SETTLED`, `FAILED`). | Zero double-charge; non-blocking external provider execution. |
| `com.paymentledger.ledger` | Immutable double-entry posting engine. | Strict invariant: $\sum \text{Debits} == \sum \text{Credits}$; append-only journal entries. |
| `com.paymentledger.outbox` | Transactional outbox polling and batch publishing. | Dual-write problem eliminated; DB state and event commit atomically. |
| `com.paymentledger.messaging` | Kafka topic configurations, serializers, consumer groups. | At-least-once event delivery; consumer idempotency deduplication. |
| `com.paymentledger.refund` | Compensating refunds and payment reversals. | Ledger history never mutated; refunds execute via inverse credit/debit entries. |
| `com.paymentledger.reconciliation`| Internal vs provider discrepancy discovery and resolution. | Automatic un-reconciled payment discovery; compensating adjustment transactions. |
| `com.paymentledger.notification`| Asynchronous email/SMS/webhook dispatch. | Notification failures strictly isolated from financial payment commits. |
| `com.paymentledger.admin` | Operations, account freeze/unfreeze, audit log queries. | Least privilege (`ROLE_ADMIN`); mandatory clamped pagination (<= 100). |
| `com.paymentledger.shared` | RFC 7807 error formatting, correlation ID filters, security. | Uniform error contracts; end-to-end request tracing via SLF4J MDC. |

---

## 3. Production Deployment Topology

The platform deploys as an isolated, containerized stack designed for reliability and resource predictability:

```mermaid
graph TB
    subgraph Host_Environment["8 vCPU x86_64 Host / 16 GB RAM"]
        subgraph Ingress["Edge & Load Balancing"]
            Proxy["Reverse Proxy / TLS Terminator<br/>Port 443 / 80"]
        end

        subgraph Container_App["Application Container (Non-Root UID: 10001)"]
            JVM["Spring Boot 3.3.4 (Java 21 LTS)<br/>Max Heap: 1,024 MB (75% Container RAM)<br/>HikariCP Pool: 20 Connections"]
        end

        subgraph Container_DB["PostgreSQL Container (Authoritative DB)"]
            PG["PostgreSQL 16.15-alpine<br/>Shared Buffers: 256MB<br/>Max Connections: 100<br/>Port: 5432"]
        end

        subgraph Container_Kafka["Kafka Container (KRaft Mode)"]
            K["Confluent Kafka 7.6.0<br/>Partitions: 3 per topic<br/>Port: 9092"]
        end

        subgraph Container_Redis["Redis Container (Auxiliary Cache)"]
            R["Redis 7.2.4-alpine<br/>Max Memory: 512MB (volatile-LRU)<br/>Port: 6379"]
        end

        subgraph Persistent_Storage["Durable NVMe Storage Volumes"]
            PG_DATA[("postgres_data (Flyway V1..V12)")]
            K_DATA[("kafka_data")]
            R_DATA[("redis_data")]
        end
    end

    Proxy -->|HTTP:8080| JVM
    JVM -->|JDBC / Hikari:5432| PG
    JVM -->|TCP:9092| K
    JVM -->|TCP:6379| R
    PG --> PG_DATA
    K --> K_DATA
    R --> R_DATA
```
