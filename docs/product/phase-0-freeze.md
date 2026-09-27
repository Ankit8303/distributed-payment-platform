# Phase 0 Specification Freeze

This directory defines the authoritative contractual baseline for the Distributed Payment & Ledger Platform before any application code is implemented.

## Frozen Specification Artifacts:
1. `docs/product/01-system-requirements.md` — System boundaries, actors, capabilities, non-goals, failure workflows.
2. `docs/product/02-domain-model.md` — Aggregates, Money model, Account types, lifecycle, traceability.
3. `docs/product/03-use-cases.md` — Exhaustive use cases (UC-01 through UC-11) including timeout recovery.
4. `docs/product/04-financial-invariants.md` — 15 explicit financial invariants with DB and application enforcement.
5. `docs/product/05-state-machines.md` — Payment, Refund, Account, and Reconciliation state machines and transition matrices.
6. `docs/product/06-database-model.md` — PostgreSQL 16+ relational schema, constraints, triggers, indexes, Flyway strategy.
7. `docs/product/07-api-contract.md` — REST API specifications, RFC 7807 problem details, standard endpoints.
8. `docs/product/08-event-contract.md` — CloudEvents-inspired internal event envelope, Kafka topics, idempotency.
9. `docs/product/09-security-model.md` — Authentication, RBAC, IDOR defense, and 14-threat threat model.
10. `docs/product/10-non-functional-requirements.md` — Durability, NFRs, observability, evidence-based metrics.
11. `docs/product/11-acceptance-criteria.md` — Concrete Given-When-Then criteria (AC-01 through AC-20).
12. `docs/product/12-requirement-traceability.md` — End-to-end requirement traceability matrix.

## Architecture Decision Records:
- `docs/adr/ADR-001-modular-monolith-architecture.md`
- `docs/adr/ADR-002-immutable-double-entry-ledger-and-money-model.md`
- `docs/adr/ADR-003-durable-idempotency-at-database-boundary.md`
- `docs/adr/ADR-004-transactional-outbox-pattern.md`
- `docs/adr/ADR-005-payment-provider-boundary.md`

Any future change to these specifications requires following `.agent/CHANGE-CONTROL.md`, creating an ADR, and undergoing human review.
