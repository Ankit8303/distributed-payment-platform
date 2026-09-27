# Architectural Decision Records (ADR) Index

**Governing Phase**: Phase 21 — Flagship Portfolio Packaging & GitHub Release  
**Primary Directory**: [`docs/adr/`](file:///c:/Users/Ankit/Downloads/payment-ledger-platform-complete-agent-kit/docs/adr/)  
**Classification**: Architectural Governance & Design Rationale  

---

## 1. Overview

Every major design decision in the Distributed Payment & Ledger Platform is governed by an Architectural Decision Record (ADR). This document provides an index of the 20 fundamental architectural decisions, detailing the engineering problem, chosen solution, rationale, and associated trade-offs.

---

## 2. Core Architectural Decisions Catalog

### ADR-001: Modular Monolith Architecture
- **Problem**: Balance transactional consistency requirements against code modularity.
- **Decision**: Build as a modular monolith in Spring Boot 3.3 with strict domain package isolation.
- **Why**: Eliminates distributed 2PC transactions and network partial-failure modes across accounts and ledger.
- **Trade-off**: Requires strict developer discipline to prevent cross-package database joins.
- **Full Record**: [`docs/adr/ADR-001-modular-monolith-architecture.md`](file:///c:/Users/Ankit/Downloads/payment-ledger-platform-complete-agent-kit/docs/adr/ADR-001-modular-monolith-architecture.md)

### ADR-002 & ADR-006: Immutable Double-Entry Ledger & Integer Money
- **Problem**: Prevent accounting discrepancies and rounding errors inherent in single-entry balance columns.
- **Decision**: Store all funds as 64-bit integer minor units (`amountMinor`). Enforce $\sum \text{Debits} == \sum \text{Credits}$.
- **Why**: Provides mathematical non-repudiation and auditability; integer math eliminates IEEE 754 float drift.
- **Trade-off**: Increases database storage volume as every movement writes paired journal rows.
- **Full Records**: [`ADR-002`](file:///c:/Users/Ankit/Downloads/payment-ledger-platform-complete-agent-kit/docs/adr/ADR-002-immutable-double-entry-ledger-and-money-model.md), [`ADR-006`](file:///c:/Users/Ankit/Downloads/payment-ledger-platform-complete-agent-kit/docs/adr/ADR-006-phase-6-ledger-financial-source-of-truth.md)

### ADR-003 & ADR-007: Durable Idempotency & Deterministic Concurrency
- **Problem**: Network retries causing double-charges; concurrent transfers causing database deadlocks.
- **Decision**: Enforce idempotency via PostgreSQL unique constraint `(actor, op, key)`. Order account locks lexicographically via `UUID.compareTo`.
- **Why**: Eliminates circular wait conditions (0 deadlocks); guarantees exact-once financial execution.
- **Trade-off**: Sequential lock acquisition adds ~0.85ms per transaction.
- **Full Records**: [`ADR-003`](file:///c:/Users/Ankit/Downloads/payment-ledger-platform-complete-agent-kit/docs/adr/ADR-003-durable-idempotency-at-database-boundary.md), [`ADR-007`](file:///c:/Users/Ankit/Downloads/payment-ledger-platform-complete-agent-kit/docs/adr/ADR-007-idempotency-recovery-and-concurrency-hardening.md)

### ADR-004 & ADR-009: Transactional Outbox Pattern
- **Problem**: Dual-write race condition between database commit and Kafka event publishing.
- **Decision**: Insert outbox records into PostgreSQL within the same transaction; relay asynchronously via `FOR UPDATE SKIP LOCKED`.
- **Why**: Guarantees zero event loss and zero ghost events if database transaction rolls back.
- **Trade-off**: Poller introduces slight asynchronous relay latency (mean 200–500ms).
- **Full Records**: [`ADR-004`](file:///c:/Users/Ankit/Downloads/payment-ledger-platform-complete-agent-kit/docs/adr/ADR-004-transactional-outbox-pattern.md), [`ADR-009`](file:///c:/Users/Ankit/Downloads/payment-ledger-platform-complete-agent-kit/docs/adr/ADR-009-transactional-outbox.md)

### ADR-005: Out-of-Band Payment Provider Boundary
- **Problem**: Provider network latency holding database connections and starving the connection pool.
- **Decision**: Execute provider HTTP calls outside the database transaction boundary.
- **Why**: Prevents HikariCP connection starvation during provider outages.
- **Trade-off**: Requires dedicated reconciliation state (`PENDING_RECONCILIATION`) for ambiguous provider timeouts.
- **Full Record**: [`docs/adr/ADR-005-payment-provider-boundary.md`](file:///c:/Users/Ankit/Downloads/payment-ledger-platform-complete-agent-kit/docs/adr/ADR-005-payment-provider-boundary.md)

### ADR-008: Kafka Event Transport Architecture
- **Problem**: Distribute domain events to multiple consumers without tight coupling.
- **Decision**: Standardize on Apache Kafka 7.6 (KRaft mode) with idempotent producers and consumer deduplication.
- **Why**: Replayable, partitionable event log decoupled from the authoritative financial store.
- **Trade-off**: Requires consumers to implement deduplication contracts.
- **Full Record**: [`docs/adr/ADR-008-kafka-events.md`](file:///c:/Users/Ankit/Downloads/payment-ledger-platform-complete-agent-kit/docs/adr/ADR-008-kafka-events.md)

### ADR-010: Redis as Strictly Auxiliary Infrastructure
- **Problem**: Balance query throughput against data durability risks.
- **Decision**: Restrict Redis to non-authoritative read caching and rate limiting; zero financial state stored in Redis.
- **Why**: Redis outages incur zero data loss; application degrades gracefully to PostgreSQL reads (+6.4ms latency).
- **Trade-off**: Requires cache-invalidation logic upon balance mutations.
- **Full Record**: [`docs/adr/ADR-010-redis-auxiliary-infrastructure.md`](file:///c:/Users/Ankit/Downloads/payment-ledger-platform-complete-agent-kit/docs/adr/ADR-010-redis-auxiliary-infrastructure.md)

### ADR-011: Compensating Reversals & Refunds
- **Problem**: Execute refunds and dispute resolutions without corrupting accounting history.
- **Decision**: Execute all reversals exclusively via new compensating double-entry ledger transactions.
- **Why**: Posted ledger history remains immutable and compliant with financial audit standards.
- **Trade-off**: Increases journal entry row counts.
- **Full Record**: [`docs/adr/ADR-011-refunds-reversals-payouts-adjustments.md`](file:///c:/Users/Ankit/Downloads/payment-ledger-platform-complete-agent-kit/docs/adr/ADR-011-refunds-reversals-payouts-adjustments.md)

### ADR-012: Asynchronous Reconciliation Engine
- **Problem**: Resolve payment status ambiguity following gateway timeouts or crashes.
- **Decision**: Implement background reconciliation workers that ingest provider reports and post compensating adjustments.
- **Why**: Guarantees eventual consistency between internal ledger and external banks.
- **Trade-off**: Reconciliation operates on a polling schedule rather than instantaneous resolution.
- **Full Record**: [`docs/adr/ADR-012-reconciliation.md`](file:///c:/Users/Ankit/Downloads/payment-ledger-platform-complete-agent-kit/docs/adr/ADR-012-reconciliation.md)

### ADR-013: Decoupled Notification Orchestration
- **Problem**: Webhook and notification delivery failures impacting customer payment response times.
- **Decision**: Process notifications asynchronously via Kafka consumers with SSRF filtering.
- **Why**: Isolates external delivery latency from core financial settlement.
- **Trade-off**: Notifications are delivered with at-least-once semantics; external recipients must handle duplicates.
- **Full Record**: [`docs/adr/ADR-013-notification-orchestration.md`](file:///c:/Users/Ankit/Downloads/payment-ledger-platform-complete-agent-kit/docs/adr/ADR-013-notification-orchestration.md)

### ADR-014: Least-Privilege Administrative Controls
- **Problem**: Operators requiring emergency investigation tools without enabling rogue balance edits.
- **Decision**: Guard admin APIs with `ROLE_ADMIN`; clamp search pagination to 100 rows; omit arbitrary balance edit APIs.
- **Why**: Enforces non-repudiation and prevents unauthorized balance manipulation.
- **Trade-off**: Operators must use compensating adjustments rather than direct row edits.
- **Full Record**: [`docs/adr/ADR-014-administrative-operations.md`](file:///c:/Users/Ankit/Downloads/payment-ledger-platform-complete-agent-kit/docs/adr/ADR-014-administrative-operations.md)

### ADR-015 & ADR-016: Comprehensive Testing & Production Observability
- **Problem**: Maintain high confidence across complex distributed failure modes.
- **Decision**: 452 automated tests leveraging Testcontainers; Micrometer Prometheus metrics and structured JSON logging.
- **Why**: Real-time production visibility and zero-regression quality gates.
- **Trade-off**: Testcontainers integration suite requires ~3 minutes execution time.
- **Full Records**: [`ADR-015`](file:///c:/Users/Ankit/Downloads/payment-ledger-platform-complete-agent-kit/docs/adr/ADR-015-comprehensive-testing-strategy.md), [`ADR-016`](file:///c:/Users/Ankit/Downloads/payment-ledger-platform-complete-agent-kit/docs/adr/ADR-016-production-observability.md)

### ADR-017 & ADR-018: Production Hardening & CI Quality Gates
- **Problem**: Secure runtime execution and automated enforcement of engineering invariants.
- **Decision**: Multi-stage Docker build with non-root user (`appuser:10001`); automated secret and dependency scanning.
- **Why**: Eliminates container privilege escalation and blocks credential commits.
- **Trade-off**: Requires strict environment variable configuration management.
- **Full Records**: [`ADR-017`](file:///c:/Users/Ankit/Downloads/payment-ledger-platform-complete-agent-kit/docs/adr/ADR-017-production-hardening.md), [`ADR-018`](file:///c:/Users/Ankit/Downloads/payment-ledger-platform-complete-agent-kit/docs/adr/ADR-018-ci-quality-gates.md)

### ADR-019: Evidence-Based Performance Engineering & Limits
- **Problem**: Prevent speculative optimization and establish empirical system capacity.
- **Decision**: Standardize on k6 load generation; calibrate HikariCP to 20 connections as the preferred measured operating point (185 TPS sustainable capacity / 235 TPS peak test load).
- **Why**: Concrete empirical limits prevent over-sizing or under-sizing database connections.
- **Trade-off**: Physical saturation occurs at ~240 TPS due to row-lock contention.
- **Full Record**: [`docs/adr/ADR-019-performance-engineering.md`](file:///c:/Users/Ankit/Downloads/payment-ledger-platform-complete-agent-kit/docs/adr/ADR-019-performance-engineering.md)
