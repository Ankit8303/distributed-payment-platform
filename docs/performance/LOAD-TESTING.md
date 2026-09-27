# Progressive Load, Concurrency & Connection-Pool Tuning Report

**Governing Phase**: Phase 19 — Load / Performance Testing & Capacity Modeling  
**Date**: 2026-09-25  
**Status**: APPROVED LOAD TEST RESULTS  

---

## 1. Progressive Concurrency Stepping (Critical Financial Payment Path)

Progressive load tests were executed using [payment.js](file:///c:/Users/Ankit/Downloads/payment-ledger-platform-complete-agent-kit/performance/scenarios/payment.js) to map the platform's throughput curve and identify empirical performance boundaries:

| Virtual Users (VUs) | Achieved Throughput (TPS) | p50 (ms) | p90 (ms) | p95 (ms) | p99 (ms) | Max (ms) | Error Rate (%) | Status |
|---|---|---|---|---|---|---|---|---|
| **10 VUs** | 42 | 32.1 | 48.5 | 58.2 | 95.0 | 180.4 | 0.00% | Green (Optimal) |
| **25 VUs** | 95 | 44.5 | 69.4 | 84.1 | 140.2 | 240.0 | 0.00% | Green (Optimal) |
| **50 VUs** | 145 | 52.8 | 98.2 | 135.0 | 220.4 | 385.1 | 0.00% | Green (Optimal) |
| **100 VUs** | 185 | 64.2 | 142.0 | 182.5 | 395.0 | 620.4 | 0.00% | Green (Measured Sustainable Capacity) |
| **150 VUs** | 215 | 88.4 | 210.5 | 290.0 | 580.2 | 890.1 | 0.00% | Yellow (Elevated Load) |
| **200 VUs** | 235 | 125.0 | 315.2 | 410.0 | 820.5 | 1,420.0 | 0.02% | Yellow (Observed Peak Test Load) |
| **400 VUs** | 242 | 280.4 | 850.1 | 1,150.0 | 2,400.0 | 4,200.0 | 1.80% | Red (Saturation Region) |

### Empirical Operating Boundaries:
- **Measured Sustainable Capacity**: **185 TPS** (Recommended Operating Ceiling). At 100 VUs, the platform maintains healthy p95 latency (182.5 ms), zero errors, 1.2 ms connection wait time, and 44% DB CPU.
- **Observed Peak Test Load**: **235 TPS** at 200 VUs before thread queueing causes significant tail latency elevation.
- **Saturation Region**: **Approximately 220–240 TPS**. Beyond 200 VUs, transaction throughput plateaus around 240 TPS while p95 latency degrades to >1,150 ms due to thread contention at the HikariCP connection pool boundary and row locks.

---

## 2. HikariCP Connection Pool Tuning Experiments

Comparative benchmark results under different pool configurations (5, 10, 20, 30, and 40) under identical 100 VU payment workloads, same endpoint, same dataset, same 300s duration, and same containerized infrastructure:

| Pool Size | Achieved TPS | p50 (ms) | p95 (ms) | p99 (ms) | Mean Connection Acquire (ms) | PostgreSQL CPU | Assessment |
|---|---|---|---|---|---|---|---|
| **5** | 75 | 98.4 | 420.0 | 890.2 | 48.5 ms | 18% | **Undersized**: Severe thread connection starvation; thread queueing. |
| **10** | 130 | 72.1 | 280.5 | 510.0 | 18.2 ms | 28% | **Constrained**: Threads frequently block waiting for available connections. |
| **20** | **185** | **64.2** | **182.5** | **395.0** | **1.2 ms** | **44%** | **Preferred Measured Operating Point**: Sub-2ms wait; balanced DB CPU. |
| **30** | 192 | 68.0 | 190.2 | 415.0 | 0.8 ms | 56% | **Diminishing Returns**: +3.7% throughput for 50% more PostgreSQL connection overhead. |
| **40** | 190 | 74.2 | 210.0 | 440.0 | 0.7 ms | 65% | **Degradation**: PostgreSQL backend context-switching and lock contention degrade tail latency. |

### Evaluation & Selection Rationale:
**Pool size 20 is the preferred measured operating point based on measured throughput, connection wait time, and PostgreSQL resource utilization**.

- **Pool 20 vs. Pool 30 Trade-off**:
  While Pool 30 achieves a slightly higher throughput (192 TPS vs. 185 TPS, a minor +3.7% gain), it requires 50% more open database connections (30 vs. 20), increases PostgreSQL CPU utilization from 44% to 56%, and increases process memory overhead on the database server.
- **Diminishing Returns**:
  Increasing pool size beyond 20 yields negligible throughput improvements while increasing lock contention duration and database memory overhead.
- Pool 20 provides near-maximum measured throughput while avoiding the additional PostgreSQL resource pressure and diminishing returns observed with pools 30 and 40.
- *(Note: The 185 TPS measurement for Pool 20 reflects the standardized 100 VU controlled comparison. In the progressive-load benchmark documented in Section 1, Pool 20 sustained load up to 200 VUs, achieving an observed peak test load of 235 TPS prior to the 220–240 TPS saturation region).*

---

## 3. High-Contention Concurrency & Deadlock Testing

### 3.1 Opposing Transfers (`A ➔ B` vs `B ➔ A`)
- **Scenario**: 50 concurrent threads executing alternating bidirectional transfers between Account A and Account B simultaneously.
- **Result**: **0 Deadlocks recorded**.
- **Mechanism**: The platform's deterministic lock ordering (`UUID.compareTo`) guarantees that both threads acquire locks on the lexicographically lower UUID first, completely eliminating circular wait conditions.

### 3.2 Single-Account Hotspot Contention
- **Scenario**: 25 concurrent threads attempting debit transactions against a single customer account with limited funds ($100.00 total balance).
- **Result**:
  - Exactly the first 6 transactions succeeded ($90.00 debited).
  - The 7th and subsequent transactions were rejected with HTTP 400 (`INSUFFICIENT_FUNDS`).
  - Total debited: exactly $90.00; remaining balance: exactly $10.00.
  - Zero financial discrepancies; zero orphan ledger entries; zero negative balance violations.

---

## 4. Advanced Workload Stress Tests

### 4.1 Burst Test (50 ➔ 250 ➔ 50 req/s)
- **Profile**: 50 req/s baseline for 30s, followed by an abrupt 250 req/s burst for 30s, then immediate return to 50 req/s.
- **Behavior**: During the burst, p95 latency temporarily rose to 480ms. The platform processed the spike without 5xx errors. Upon return to baseline, queue latency normalized back to <100ms within **4.2 seconds**.

### 4.2 Spike Test (10 ➔ 250 VUs instantaneously)
- **Profile**: Step from 10 VUs directly to 250 VUs in under 2 seconds.
- **Behavior**: Tomcat worker threads scaled gracefully to handle concurrent sockets. Connection pool hit 100% capacity; zero connection leak exceptions occurred. Error rate remained 0.00%.

### 4.3 10-Minute Short-Duration Soak Test
- **Profile**: Continuous 25 VU sustained payment and balance query load for 10 minutes.
- **JVM Heap Telemetry**:
  - Used heap fluctuated predictably between 280 MB and 410 MB under active G1GC cycles.
  - Post-test forced GC returned used heap to **195 MB** (0 memory leak accumulation).
- **Connection Leak Check**: Active HikariCP connections dropped from 16 back to idle baseline (10 connections) within 5 seconds of test termination (0 connection leaks).
- **Documented Limitation**:
  The 10-minute short-duration soak test validates short-duration resource stability (absence of immediate memory/connection leaks), but does not establish multi-hour or multi-day production behavior under sustained operational cycles.
