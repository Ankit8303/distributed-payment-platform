# ADR-001: Modular Monolith Architecture with Event-Driven Integration

## Status
Accepted

## Context
The platform requires high financial consistency, atomic transactional guarantees, durable double-entry bookkeeping, and predictable operational maintenance. Microservice architectures introduce distributed transactions (sagas, two-phase commits), eventual consistency edge cases at financial boundaries, distributed network failure modes, and high operational overhead. We need an architecture that guarantees ACID transactional boundaries for financial integrity while maintaining strict modular separation and asynchronous event decoupling.

## Decision
We adopt a **Modular Monolith** architecture implemented in Java 21 / Spring Boot 3.x, coupled with **Event-Driven Integration** via Apache Kafka for asynchronous downstream processing.

Core boundaries:
- Domain modules: `auth`, `account`, `payment`, `ledger`, `refund`, `reconciliation`, `notification`, `admin`, `outbox`, `messaging`, `shared`.
- Module dependencies flow in a single direction: `Controller` -> `Application Service` -> `Domain` -> `Repository` -> `PostgreSQL`.
- Cross-module operations that require strict atomicity (e.g., payment status change + double-entry ledger posting + outbox record) execute within a single PostgreSQL ACID transaction.
- Asynchronous side effects (customer email notifications, external analytics, downstream processing) consume events emitted to Kafka via the Transactional Outbox.
- Redis serves strictly as an auxiliary performance optimization (rate limiting, distributed caching), never as a financial source of truth.

## Alternatives Considered
1. **Microservices Decomposition (Payment Service, Ledger Service, Account Service, etc.)**:
   - *Rejected*: Requires distributed transactions or complex compensation sagas across network boundaries. Distributed two-phase commit is brittle; eventual consistency between payment and ledger introduces windows where balances are indeterminate.
2. **Synchronous Monolith without Event Streaming**:
   - *Rejected*: Coupling non-critical side effects (e.g. notifications, external partner webhooks) into the synchronous request-response flow harms latency, throughput, and fault isolation.

## Consequences
- **Positive**:
  - Direct database ACID transactions ensure single-phase atomic consistency between payments and ledger entries.
  - Zero network overhead or distributed failure modes between core financial domains.
  - Simplified deployment, debugging, and integration testing using Testcontainers.
  - Clear domain boundaries enforce strict encapsulation, easing future extraction if specific scaling needs ever warrant it.
- **Negative / Trade-offs**:
  - Requires disciplined package and dependency enforcement (ArchUnit tests) to prevent illicit cross-module database joins or cyclical dependencies.
  - Shared relational database connection pool requires careful tuning.

## Validation
- Enforce module coupling and package boundaries using ArchUnit tests in Phase 18.
- Verify atomic rollback behavior: simulated failures in ledger posting must roll back payment state and outbox generation.
