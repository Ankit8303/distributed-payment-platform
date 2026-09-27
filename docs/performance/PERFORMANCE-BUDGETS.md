# Evidence-Based Performance Budgets & SLO Thresholds

**Governing Phase**: Phase 19 — Load / Performance Testing & Capacity Modeling  
**Date**: 2026-09-25  
**Status**: ENFORCED PERFORMANCE BUDGETS  

---

## 1. Overview & Policy

Performance budgets define strict, evidence-based latency, throughput, and error boundaries for all critical operations on the Distributed Payment & Ledger Platform.

Budgets are calibrated directly against empirical measurements established in [BASELINE.md](file:///c:/Users/Ankit/Downloads/payment-ledger-platform-complete-agent-kit/docs/performance/BASELINE.md) and [CAPACITY-MODEL.md](file:///c:/Users/Ankit/Downloads/payment-ledger-platform-complete-agent-kit/docs/performance/CAPACITY-MODEL.md).

---

## 2. API Performance Budgets & SLO Matrix

| Endpoint / Critical Path | Green (Optimal) | Yellow (Warning) | Red (SLO Breach) | Error Budget (5xx/Timeout) |
|---|---|---|---|---|
| **Health Liveness & Readiness** | p95 < 25ms, p99 < 50ms | p95 25–50ms | p95 > 50ms | < 0.01% |
| **Authentication (Login + JWT)** | p95 < 100ms, p99 < 200ms | p95 100–180ms | p95 > 180ms | < 0.10% |
| **Account Read (Redis Cache)** | p95 < 15ms, p99 < 40ms | p95 15–35ms | p95 > 35ms | < 0.05% |
| **Account Read (Postgres DB)** | p95 < 35ms, p99 < 80ms | p95 35–70ms | p95 > 70ms | < 0.05% |
| **Payment Creation (Financial Path)** | p95 < 250ms, p99 < 500ms | p95 250–400ms | p95 > 400ms | < 0.05% |
| **Payment Idempotent Replay** | p95 < 30ms, p99 < 60ms | p95 30–60ms | p95 > 60ms | < 0.01% |
| **Refund Creation (Compensating)** | p95 < 250ms, p99 < 500ms | p95 250–400ms | p95 > 400ms | < 0.05% |
| **Merchant Payout Creation** | p95 < 280ms, p99 < 550ms | p95 280–420ms | p95 > 420ms | < 0.05% |
| **Reconciliation Discovery** | p95 < 200ms, p99 < 400ms | p95 200–350ms | p95 > 350ms | < 0.10% |
| **Admin Paginated Investigation** | p95 < 150ms, p99 < 300ms | p95 150–250ms | p95 > 250ms | < 0.10% |

*Note*: Expected business responses (e.g. HTTP 409 Conflict on idempotency collision, HTTP 400 on insufficient funds) do **NOT** count against the infrastructure error budget.

---

## 3. Automated Performance Regression Thresholds

The following thresholds represent an enforced **engineering regression policy** (rather than a mathematical proof), established to guard against gradual performance degradation across release candidates:

1. **Latency Regression Ceiling**:
   - The p95 latency for any critical API must not regress by more than **10.0%** compared to the established baseline.
   - The p99 latency must not regress by more than **15.0%**.
2. **Throughput Degradation Ceiling**:
   - Measured sustainable financial transaction throughput (TPS) under 100 concurrent VUs must not drop by more than **10.0%** (minimum acceptable: **165 TPS** against the 185 TPS sustainable baseline).
3. **Database Connection Hold Time**:
   - Mean transaction duration in PostgreSQL must remain **below 80ms**.
4. **Zero Financial Violations**:
   - **0.00% Tolerance**: Any test execution that results in an unbalanced ledger transaction (`SUM(debit) != SUM(credit)`), an orphan ledger entry, a duplicate financial mutation, or a deadlock **fails the build immediately**.
