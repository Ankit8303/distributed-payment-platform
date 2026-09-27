# ADR-013: Event-Driven Notification Orchestration

**Status**: ACCEPTED  
**Date**: 2026-09-25  
**Deciders**: Antigravity (Lead AI Architect), Distributed Payment & Ledger Platform Team  
**Consulted**: Financial Systems Engineering, Security, Infrastructure  

---

## 1. Context and Problem Statement

The platform settles financial transactions (payments, refunds, payouts) and records immutable double-entry ledger entries with atomic transactional outbox persistence. Downstream customers, merchants, and internal systems require notifications across diverse channels (email receipts, SMS alerts, outbound webhooks).

However, external delivery channels present unpredictable network latency, intermittent provider downtime, third-party rate limiting, and arbitrary untrusted recipient endpoints. If notification delivery were coupled to the financial transaction boundary, provider failures or latency would degrade or roll back committed financial operations.

How do we design an event-driven notification orchestration layer that delivers notifications reliably across email, SMS, and webhooks while preserving absolute financial consistency and security?

---

## 2. Decision Drivers

1. **Absolute Financial Isolation**: Notification outcomes must never determine or alter financial state.
2. **PostgreSQL as Notification State Authority**: Delivery state, attempts, templates, and subscriptions must be durably persisted.
3. **Asynchronous Decoupling via Kafka**: Financial transactions commit locally; Kafka transports domain events to the notification orchestrator.
4. **Consumer Idempotency**: At-least-once Kafka transport must not produce duplicate notification jobs or customer spam.
5. **SSRF Protection**: Outbound webhook delivery must strictly prevent Server-Side Request Forgery against private networks and cloud metadata services.
6. **Template Safety**: Dynamic template rendering must prohibit arbitrary code or script execution.
7. **Durable Bounded Retries**: Transient delivery failures must retry with exponential backoff and safe lease expiration recovery.

---

## 3. Considered Options

* **Option 1: Direct Synchronous Invocation inside Payment / Refund Services**  
  *Pros*: Immediate delivery attempt.  
  *Cons*: Fatal violation of architectural boundaries. Network latency blocks database transactions; external provider outages threaten financial settlement.
* **Option 2: Redis-backed In-Memory Task Queue**  
  *Pros*: Fast job dispatch.  
  *Cons*: Redis is auxiliary, not durable. Redis crashes or restarts risk losing notification audit history.
* **Option 3: Event-Driven Kafka Consumer with PostgreSQL-Backed Orchestrator and Pluggable Providers (Chosen)**  
  *Pros*: Strict asynchronous decoupling via Transactional Outbox + Kafka; durable PostgreSQL state with `FOR UPDATE SKIP LOCKED` claiming; bounded retries; comprehensive SSRF and template security controls.

---

## 4. Decision Outcome

We choose **Option 3: Event-Driven Kafka Consumer with PostgreSQL-Backed Orchestrator**.

### Key Architectural Tenets:

### 4.1 Strict Asynchronous Boundary & Financial Isolation
Notification processing is strictly decoupled from the financial transaction boundary:
```text
Financial Transaction (PostgreSQL ACID)
   ├── Payment/Refund/Payout State Update
   ├── Immutable Double-Entry Ledger Posting
   └── Transactional Outbox Insertion
          │
          ▼ COMMIT
   Outbox Relay (Asynchronous)
          │
          ▼
     Apache Kafka
          │
          ▼ At-Least-Once Delivery
   Notification Event Consumer
          │
          ▼
   Notification Orchestrator & PostgreSQL State (Isolated)
          │
          ├── Email Provider (Fake / External)
          ├── SMS Provider (Fake / External)
          └── Webhook Provider (Fake / External)
```
If an email bounces, an SMS gateway is unreachable, or a merchant webhook endpoint returns HTTP 500, the notification transitions independently to `RETRY_REQUIRED` or `FAILED`. The originating financial operation (`PaymentEntity`, `RefundEntity`, `PayoutEntity`, `LedgerTransactionEntity`) remains completely untouched in its authoritative `SETTLED` state.

### 4.2 Kafka as Event Transport, PostgreSQL as Notification Authority
Kafka provides distributed at-least-once event streaming. However, notification job durability, attempt history, and template versions reside authoritatively in PostgreSQL (`notifications`, `notification_deliveries`, `notification_templates`, `webhook_subscriptions`). Redis is not used for notification durability.

### 4.3 Deduplication & Idempotent Ingestion
Kafka delivery is at-least-once. Duplicate Kafka messages are handled at two layers:
1. **Consumer Deduplication**: `ConsumerDeduplicationService` records consumed message IDs in `consumed_messages` within the consumer's transaction.
2. **Database Constraint**: `notifications` enforces `UNIQUE (event_id, channel, recipient)`. Redundant deliveries of the same event cannot insert duplicate logical notification rows.

### 4.4 Bounded Exponential Backoff & Crash Recovery
* **Retry Schedule**: Exponential backoff ($1\text{s}, 2\text{s}, 4\text{s}, 8\text{s}, 16\text{s}, 32\text{s}, 60\text{s}$).
* **Terminal Failure**: After 5 failed attempts, or upon non-retryable errors (`INVALID_RECIPIENT`, `AUTHENTICATION_FAILURE`, `SSRF_BLOCKED`), the notification transitions to `FAILED`.
* **Lease Management**: Workers claim jobs using `SELECT ... FOR UPDATE SKIP LOCKED` with a 60-second lease. If a worker crashes mid-processing, the query claims expired `PROCESSING` leases (`lease_expires_at < NOW()`), preventing stranded notifications.

### 4.5 At-Least-Once External Delivery Semantics
Because external delivery involves non-transactional network calls, a worker could crash after the provider call succeeds but before the database status update commits. Therefore, external delivery is inherently **at-least-once**, not exactly-once. Webhook payloads include stable `eventId` and `correlationId` headers to allow downstream receivers to deduplicate idempotently.

### 4.6 Webhook SSRF Security
Outbound webhooks present a severe Server-Side Request Forgery risk if user-supplied URLs target internal infrastructure. `WebhookSecurityValidator` enforces:
1. **Scheme Validation**: Strictly `http` and `https` allowed.
2. **DNS & IP Range Filtering**: Resolves hostnames and rejects loopback (`127.0.0.0/8`, `::1`), private RFC-1918 ranges (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`), link-local (`169.254.0.0/16`, `fe80::/10`), wildcard addresses (`0.0.0.0`), and cloud instance metadata services (`169.254.169.254`, `metadata.google.internal`).
3. **Test Domain Accommodation**: IANA-reserved test domains (`.example.com`, `.test`) are handled safely in test profiles without external DNS dependencies.

### 4.7 Secure Constrained Template Engine
`NotificationTemplateEngine` performs regex-based variable substitution strictly of the format `{{variableName}}`. It prohibits SpEL (Spring Expression Language), script evaluation (`<script>`), shell commands, or arbitrary Java reflection. Malicious template inputs are treated as literal text.

---

## 5. Consequences

### Positive
* **Zero Financial Coupling**: Financial settlement is 100% immune to external communication latency or failure.
* **Auditability**: Every delivery attempt is logged in `notification_deliveries` with HTTP status codes and error classifications.
* **High Concurrency & Resilience**: `FOR UPDATE SKIP LOCKED` allows horizontal scaling of notification workers with zero locking contention.
* **Safe Webhooks**: Robust SSRF defenses prevent internal cloud exploitation.

### Negative / Trade-Offs
* **At-Least-Once Delivery**: External recipients must be prepared to handle duplicate messages under rare worker crash scenarios.
* **Asynchronous Latency**: Notifications are delivered slightly after financial transactions commit (sub-second in normal conditions).
