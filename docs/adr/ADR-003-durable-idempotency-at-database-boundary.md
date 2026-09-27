# ADR-003: Durable Idempotency at the Database Boundary

## Status
Accepted

## Context
Payment processing is vulnerable to duplicate requests originating from network retries, client double-clicks, mobile reconnection blips, and automated webhook retries. If an operation is processed more than once, it causes duplicate charges, double refunds, or corrupted financial balances. Idempotency must be durable, race-condition proof, and independent of volatile in-memory or Redis caches.

## Decision
1. **Durable Database-Backed Idempotency**:
   - Establish a dedicated PostgreSQL table `idempotency_records` acting as the authoritative consistency gate.
   - Enforce a strict database unique constraint on `(actor_id, operation, idempotency_key)`.
   - Redis may be used optionally as a fast pre-flight check, but PostgreSQL is the definitive authority. A Redis outage or flush must never permit duplicate financial execution.
2. **Deterministic Lifecycle & Conflict Handling**:
   - **Case A (Exact Match - Completed)**: When a request arrives with an existing key whose cryptographic request hash matches a completed record, return the persisted HTTP status code and response payload immediately without executing financial logic.
   - **Case B (Payload Mismatch - Conflict)**: When a request arrives with an existing key but a different request hash, immediately abort and return `409 Conflict` (`IDEMPOTENCY_KEY_PAYLOAD_MISMATCH`).
   - **Case C (In-Flight Concurrency)**: If a duplicate arrives while the first execution is `IN_PROGRESS`, reject the concurrent duplicate with `409 Conflict` (`IDEMPOTENCY_CONCURRENT_REQUEST`) or `429 Too Many Requests` with a `Retry-After` header.
   - **Case D (Crash After Commit)**: Because the `idempotency_records` row transition to `COMPLETED` is committed in the same database transaction as the business and ledger state changes, any retry following an application crash reads the committed record and returns the cached result.
   - **Case E (Provider Timeout / Ambiguity)**: If an external provider call times out, the local operation enters `PENDING_RECONCILIATION`. A client retry does not initiate a second provider call; instead, it triggers a deterministic provider status query using the provider-assigned idempotency reference.
3. **Scope and Retention**:
   - Idempotency scope is strictly compound: `actor_id` + `operation` (e.g. `PAYMENT_CREATE`) + `idempotency_key`. This prevents cross-tenant key collisions.
   - Idempotency records have a configurable retention window (e.g., 30 to 90 days) managed via scheduled partitioning/archival, strictly decoupled from permanent immutable financial ledger records.

## Alternatives Considered
1. **Redis-Only Idempotency (SETNX with TTL)**:
   - *Rejected*: In-memory caches are susceptible to evictions, replication lag, and node restarts. A Redis eviction during a network blip could lead to duplicate payment execution.
2. **Blind Retry without Request Hashing**:
   - *Rejected*: Permitting different payloads under the same key allows subtle tampering and accidental parameter mutations (e.g., changing the recipient account on a retry).

## Consequences
- **Positive**:
  - Absolute protection against double-charging, double-refunding, or phantom ledger postings.
  - Consistent and predictable client experience across network timeouts and retry loops.
  - Resilient to container crashes and node redeployments.
- **Negative / Trade-offs**:
  - An additional database write/read per mutating financial request.
  - Requires maintaining response payloads in the database.

## Validation
- Concurrency test (Phase 7 & Phase 15): 50 concurrent threads firing the identical payment request with the same idempotency key simultaneously; assert exactly 1 payment and 1 ledger transaction are created.
- Negative test: Submitting the same key with different amounts yields `409 Conflict`.
