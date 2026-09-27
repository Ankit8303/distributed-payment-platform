# ADR-015: Comprehensive Automated Testing & Verification Strategy

## Status
Accepted and Frozen (Phase 15)

## Context
The Distributed Payment & Ledger Platform has evolved through 15 development phases, establishing a robust double-entry core, durable database-backed idempotency, transactional outbox messaging via Apache Kafka, Redis auxiliary caching and distributed locks, refunds, reversals, payouts, reconciliation, notification orchestration, and administrative operations.

Phase 14 completed the operational and administrative boundary. Phase 15 is strictly a verification and hardening phase whose mission is to assure correctness, financial invariants, concurrency safety, fault tolerance, and security through comprehensive automated testing without introducing speculative production architecture or expanding business scope.

---

## Decision Drivers
- **Non-Negotiable Financial Invariants**: Guarantee double-entry ledger balance ($\sum \text{Debit} == \sum \text{Credit}$), positive minor amounts, immutable history, and append-only ledgers.
- **Deterministic Verification**: Eliminate flaky, sleep-based, and timing-dependent test cases. Tests must execute repeatably across environments.
- **Realistic Infrastructure Integration**: Verify PostgreSQL constraints, Kafka deduplication, and Redis caching against real containerized infrastructure via Testcontainers rather than shallow in-memory mocks.
- **Balanced Testing Pyramid**: Maintain high-speed pure unit tests for domain invariants and state machines, complemented by exhaustive integration tests across infrastructure, transactional boundaries, and REST APIs.
- **Fail-Safe Fault Isolation**: Prove that Kafka, Redis, external provider, or network failures cannot corrupt PostgreSQL-authoritative financial truth.
- **Zero Phase 16 Leakage**: Protect Phase 16 boundaries (production observability, dashboards, and alerting) from premature implementation.

---

## Architectural Decisions

### 1. Nine-Layer Testing Pyramid
Phase 15 organizes verification into a nine-layer testing pyramid:
1. **Layer 1: Unit Tests**: Pure domain logic and state machines tested in sub-second isolation (`AccountEntityTest`, `RefundEntityTest`, `PayoutEntityTest`, `NotificationEntityTest`, `WebhookSecurityValidatorTest`, `NotificationTemplateEngineTest`).
2. **Layer 2: Repository / Data-Access Tests**: Verification of Flyway schemas, PostgreSQL foreign keys, unique constraints (`uq_accounts_account_number`, `uq_ledger_entries_account_seq`, `uq_idempotency_records_scope_key`), optimistic locking, and sequence uniqueness.
3. **Layer 3: Service / Application Integration Tests**: Verification of transactional boundaries, rollback guarantees, and domain event creation.
4. **Layer 4: REST / API Integration Tests**: Verification of HTTP request/response schemas, validation rules, RFC 7807 problem details, pagination clamping, and header contracts (`Idempotency-Key`, `X-Correlation-ID`).
5. **Layer 5: Security Tests**: Role-based access control (RBAC), JWT cryptographic validation, expired/tampered tokens, IDOR isolation, and SSRF prevention on webhooks.
6. **Layer 6: Infrastructure Integration Tests**: Real containerized PostgreSQL 16, Apache Kafka 3.8.0, and Redis 7 verifying connection resilience, cache invalidation, and consumer group offset management.
7. **Layer 7: Concurrency & Failure Tests**: Multi-threaded latch-synchronized execution verifying deadlock freedom on bidirectional transfers, concurrent idempotent submissions, and worker crash recovery.
8. **Layer 8: Cross-Module End-to-End Traces**: Complete correlation trace traversal from HTTP payment capture through outbox publishing, ledger posting, notification dispatch, and administrative investigation.
9. **Layer 9: Contract & Schema Compatibility Tests**: Strict validation of event envelopes (`id`, `aggregateType`, `aggregateId`, `eventType`, `payload`, `correlationId`, `causationId`, `timestamp`) ensuring consumer schema compatibility.

### 2. Infrastructure Strategy & Testcontainers
- Integration tests extend `AbstractIntegrationTest`, which initializes static singleton Testcontainers for PostgreSQL, Kafka, and Redis.
- Singleton containers avoid costly per-class container lifecycle restarts, maintaining build speed while guaranteeing full relational and distributed fidelity.
- PostgreSQL Flyway migrations run automatically on startup from `V1` to `V12`, verifying complete DDL execution against a clean database instance.

### 3. Concurrency & Deadlock Prevention Verification
- Concurrency tests utilize `CountDownLatch` and `ExecutorService` thread pools to trigger simultaneous execution precisely at the database boundary.
- Deterministic locking orders (`payerAccountId.compareTo(payeeAccountId)`) are validated against simultaneous opposing transfers ($A \rightarrow B$ and $B \rightarrow A$) ensuring 0 deadlocks and consistent final balances.
- Idempotency concurrency testing subjects the platform to 20+ concurrent identical requests, proving that exactly one business operation and ledger transaction are created while duplicate requests receive the cached result or a 409 conflict lock acknowledgment.

### 4. Financial Invariant Verification
- A dedicated automated check audits all posted transactions:
  - $\sum \text{Debit} == \sum \text{Credit}$ strictly holds for every posted ledger transaction.
  - Every ledger entry references an existing account and has positive `amount_minor > 0`.
  - Posted ledger transactions and entries cannot be modified or deleted (`PUT`/`DELETE` prohibited, append-only).
  - Materialized balances match the dynamic sum of immutable ledger entries.

### 5. Failure Injection & Crash Recovery
- **Database Settlement Failure**: External provider captures funds, but database transaction fails. Verification confirms: payment transitions to `PENDING_RECONCILIATION`, no partial ledger transaction is posted, and reconciliation successfully recovers the state.
- **Kafka Outage**: Outbox events remain durably persisted in PostgreSQL with status `PENDING`. Upon Kafka recovery, relay workers publish events with 0 event loss.
- **Redis Outage**: Cache failures fall back transparently to PostgreSQL authoritative truth. Financial operations continue without corruption.
- **Worker Crash & Lease Expiration**: Stale lease recovery automatically reclaims orphaned reconciliation and notification jobs after timeout expiry.

### 6. Flaky-Test Policy & CI Execution
- Sleep-based waits (`Thread.sleep()`) are forbidden in assertion paths; tests use deterministic latches, polling timeouts (`Awaitility`), or synchronous transaction execution.
- Tests must be completely isolated: `@BeforeEach` truncates transactional tables and flushes caches, ensuring zero cross-test interference.
- The platform build gate enforces: 0 failures, 0 errors, 0 skipped tests on `./mvnw clean verify`.

---

## Consequences

### Positive
- Total platform verification with 294 automated tests covering pure unit, repository, integration, concurrency, security, and financial invariants.
- High developer confidence for maintenance and refactoring.
- Complete regression suite runs in ~2 minutes against real containerized infrastructure.

### Negative / Trade-offs
- Integration tests require a running Docker daemon for Testcontainers.
- Concurrency tests allocate multiple worker threads and HikariCP database connections, requiring sufficient host memory.

---

## Phase 16 Boundary Note
Phase 15 is strictly limited to comprehensive verification and test hardening. Operational dashboards, metric exporters, APM tracing instrumentation, and Prometheus alerting rules are reserved exclusively for Phase 16.
