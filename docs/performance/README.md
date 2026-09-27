# Performance Engineering & Capacity Modeling Guide

**Governing Phase**: Phase 19 — Load / Performance Testing, Capacity Modeling & Connection-Pool Tuning  
**Architecture**: Spring Boot 3.3.4 (Java 21), PostgreSQL 16, Apache Kafka 7.6, Redis 7 (Auxiliary)  

---

## 1. Objectives & Principles

Phase 19 establishes the empirical performance bounds, query execution profiles, connection-pool saturation behavior, and mathematical capacity model for the Distributed Payment & Ledger Platform.

All performance activities adhere to the fundamental empirical loop:
```text
MEASURE ──► BASELINE ──► PROFILE ──► IDENTIFY BOTTLENECK ──► HYPOTHESIS ──► CHANGE ──► BENCHMARK ──► COMPARE ──► REGRESSION TEST ──► DOCUMENT
```

### Core Non-Negotiables
1. **Financial Correctness Over Throughput**: Invariant `SUM(debits) == SUM(credits)` must never be bypassed or weakened. No locks are removed to artificially inflate transactions per second.
2. **PostgreSQL Authority**: PostgreSQL remains the sole authoritative financial source of truth. Redis and Kafka remain auxiliary and transport systems.
3. **No Synthetic Shortcuts**: Primary benchmarks execute the full financial path: Authentication ➔ Idempotency Validation ➔ Deterministic Account Locking ➔ Double-Entry Ledger Posting ➔ Outbox Enqueue ➔ Commit.

---

## 2. Performance Documentation Directory

| Document | Purpose & Key Topics |
|---|---|
| [**BASELINE.md**](file:///c:/Users/Ankit/Downloads/payment-ledger-platform-complete-agent-kit/docs/performance/BASELINE.md) | Standardized hardware, software configurations, baseline latency distributions, and warmup methodology. |
| [**CAPACITY-MODEL.md**](file:///c:/Users/Ankit/Downloads/payment-ledger-platform-complete-agent-kit/docs/performance/CAPACITY-MODEL.md) | Mathematical capacity derivation (`Peak TPS = DAU × Tx/DAU × PeakFactor`), sizing formulas, and resource headroom. |
| [**PERFORMANCE-BUDGETS.md**](file:///c:/Users/Ankit/Downloads/payment-ledger-platform-complete-agent-kit/docs/performance/PERFORMANCE-BUDGETS.md) | Evidence-based SLO budgets across APIs, Green/Yellow/Red operating zones, and automated regression thresholds. |
| [**QUERY-PROFILING.md**](file:///c:/Users/Ankit/Downloads/payment-ledger-platform-complete-agent-kit/docs/performance/QUERY-PROFILING.md) | Detailed `EXPLAIN ANALYZE` profiles for the 10 highest-frequency queries, index evaluations, and lock dynamics. |
| [**LOAD-TESTING.md**](file:///c:/Users/Ankit/Downloads/payment-ledger-platform-complete-agent-kit/docs/performance/LOAD-TESTING.md) | Progressive concurrency benchmarks, burst/spike/soak scenarios, and HikariCP connection-pool tuning curves. |
| [**FAILURE-PERFORMANCE.md**](file:///c:/Users/Ankit/Downloads/payment-ledger-platform-complete-agent-kit/docs/performance/FAILURE-PERFORMANCE.md) | System performance under component degradation (Redis outage, Kafka lag/backpressure, provider latency). |

---

## 3. Tooling and Execution

The repository standardizes on **k6** for all HTTP load generation:
- Scenarios located in [`performance/scenarios/`](file:///c:/Users/Ankit/Downloads/payment-ledger-platform-complete-agent-kit/performance/scenarios/).
- Shell runner scripts in [`scripts/performance/`](file:///c:/Users/Ankit/Downloads/payment-ledger-platform-complete-agent-kit/scripts/performance/).
- CI automated benchmarking via [`.github/workflows/performance.yml`](file:///c:/Users/Ankit/Downloads/payment-ledger-platform-complete-agent-kit/.github/workflows/performance.yml).
