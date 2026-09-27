# Empirical Performance & Capacity Modeling Summary

**Governing Phase**: Phase 21 — Flagship Portfolio Packaging & GitHub Release  
**Primary Source**: Phase 19 Load / Performance Testing & Capacity Modeling  
**Target Scope**: Single-Node Modular Monolith (Containerized PostgreSQL 16, Kafka 7.6, Redis 7.2)  
**Status**: VERIFIED EMPIRICAL RESULTS  

---

## 1. Executive Summary & Key Performance Bounds

The platform's performance limits were experimentally established using **k6** (v0.48+) load generators running across containerized infrastructure. All measurements execute the full financial transaction path: Authentication ➔ Idempotency Check ➔ Pessimistic Row Locking ➔ Double-Entry Ledger Posting ➔ Outbox Enqueue ➔ Commit.

### Verified Empirical Performance Ceilings:
- **Measured Sustainable Capacity**: **185 TPS** (Recommended Operating Ceiling on the financial path at 100 VUs, p95 = 182.5 ms, 0.00% errors, 1.2 ms connection acquire wait, 44% DB CPU).
- **Observed Peak Test Load**: **235 TPS** (Achieved during progressive load stepping at 200 VUs before thread queueing causes tail latency inflation).
- **Physical Saturation Region**: **Approximately 220–240 TPS** (At 400 VUs, throughput plateaus at ~240 TPS while p95 latency elevates to 1,150 ms due to HikariCP pool saturation and row-lock serialization).
- **Modeled Peak Demand & Headroom**: A standard commercial tier (50,000 DAU, 4 transactions/user/day, 8.0x peak factor) requires **31.8 TPS** peak demand. The platform delivers **~482% capacity headroom** over modeled peak load.

---

## 2. Test Environment & Infrastructure Baseline

| Layer / Component | Specification / Configuration |
|---|---|
| **Host Hardware** | 8 vCPU x86_64, 16 GB Physical RAM, NVMe SSD Storage |
| **Operating System** | Linux 6.x / Windows 11 Enterprise (x64) |
| **Runtime** | Eclipse Temurin OpenJDK 21.0.4+7 (LTS), Spring Boot 3.3.4 |
| **Container Memory** | 1,365 MB container limit; 1,024 MB JVM maximum heap (`-XX:MaxRAMPercentage=75.0`) |
| **Database** | PostgreSQL 16.15 (`shared_buffers=256MB`, `max_connections=100`, `work_mem=16MB`) |
| **Connection Pool** | HikariCP 5.1.0 (`maximum-pool-size: 20`, `minimum-idle: 10`, `connection-timeout: 30000ms`) |
| **Message Broker** | Apache Kafka 7.6.0 (KRaft mode, 1 broker, 3 partitions per topic, `min.insync.replicas: 1`) |
| **Auxiliary Cache** | Redis 7.2.4 Alpine (`maxmemory: 512MB`, `maxmemory-policy: volatile-lru`) |

---

## 3. Workload Benchmark Results (100 VU Standardized Load)

| Workload Category | Concurrency | Achieved Throughput | p50 (ms) | p90 (ms) | p95 (ms) | p99 (ms) | Error Rate (%) |
|---|---|---|---|---|---|---|---|
| **Health Liveness Probe** | 50 VUs | 3,240 req/s | 1.2 | 3.1 | 4.8 | 11.4 | 0.00% |
| **Authentication (Login + JWT)** | 30 VUs | 415 req/s | 38.5 | 68.2 | 85.4 | 142.1 | 0.00% |
| **Account Read (Cache Hit)** | 100 VUs | 1,850 req/s | 2.1 | 5.4 | 7.8 | 16.2 | 0.00% |
| **Account Read (DB Fallback)** | 50 VUs | 620 req/s | 8.5 | 16.2 | 22.4 | 45.1 | 0.00% |
| **Payment Financial Path** | 100 VUs | **185 TPS** | **64.2** | **142.0** | **182.5** | **395.0** | **0.00%** |
| **Payment Idempotent Replay** | 50 VUs | 880 req/s | 6.8 | 14.1 | 19.8 | 38.2 | 0.00% |
| **Refund Creation** | 30 VUs | 195 TPS | 58.4 | 134.5 | 174.2 | 380.1 | 0.00% |
| **Payout Creation** | 30 VUs | 170 TPS | 69.1 | 152.0 | 195.4 | 412.0 | 0.00% |
| **Admin Paginated Search** | 20 VUs | 140 req/s | 32.4 | 64.1 | 88.0 | 190.5 | 0.00% |

*Note*: For financial operations (Payment, Refund, Payout), throughput is measured strictly as committed, balanced financial transactions per second (`TPS`).

---

## 4. HikariCP Connection Pool Calibration

Controlled benchmark trials evaluated connection pool sizes (5, 10, 20, 30, and 40) under identical 100 VU payment workloads:

| Pool Size | Achieved TPS | p95 Latency | Mean Connection Acquire | PostgreSQL CPU | Assessment |
|---|---|---|---|---|---|
| **5** | 75 TPS | 420.0 ms | 48.5 ms | 18% | Undersized: severe connection starvation. |
| **10** | 130 TPS | 280.5 ms | 18.2 ms | 28% | Constrained: threads block waiting for connections. |
| **20** | **185 TPS** | **182.5 ms** | **1.2 ms** | **44%** | **Preferred Measured Operating Point**: Sub-2ms wait; balanced CPU. |
| **30** | 192 TPS | 190.2 ms | 0.8 ms | 56% | Diminishing Returns: +3.7% throughput for 50% more connections. |
| **40** | 190 TPS | 210.0 ms | 0.7 ms | 65% | Degradation: DB backend context-switching elevates tail latency. |

**Rationale**: Pool size 20 delivers near-maximum throughput while avoiding additional database connection pressure and context switching.

---

## 5. Messaging & Auxiliary Telemetry

- **Kafka & Outbox Relay**:
  - Polling query (`SELECT ... FOR UPDATE SKIP LOCKED` batch size 50) executes in 1.15 ms.
  - Sustained batch relay throughput: **210 events/sec**.
  - Consumer lag under steady state: 0 to 4 events.
- **Redis Performance**:
  - Cache hit latency: **2.1 ms** (p95: 7.8 ms).
  - Cache miss / DB fallback: **8.5 ms** (p95: 22.4 ms).
  - Token-bucket check: 0.42 ms.
  - Query avoidance: ~74% under read-heavy workloads (80/20 read/write ratio).

---

## 6. Advanced Stress Testing & Soak Validation

- **Burst Resilience**: Handled sudden 50 ➔ 250 req/s traffic spike with p95 rising temporarily to 480 ms; normalized back to baseline in **4.2 seconds** with 0 errors.
- **Spike Resilience**: Instantaneous step from 10 to 250 VUs handled with 0 connection leaks and 0 unhandled errors.
- **10-Minute Short-Duration Soak Test**: Continuous 25 VU sustained load for 10 minutes. G1GC maintained JVM used heap between 280 MB and 410 MB; post-test heap garbage-collected cleanly to **195 MB** with **0 connection leaks**.
- **Documented Limitation**: Validates short-duration resource stability (no immediate memory or connection leaks), but does not substitute for multi-day production burn-in.

---

## 7. Mathematical Capacity Model vs Reality

```text
                  DAU × TransactionsPerDAU × PeakMultiplier
Peak TPS (Req) = ───────────────────────────────────────────
                            ActiveSeconds (T)
```

- **Inputs**: $\text{DAU} = 50,000$, $\text{Tx/DAU} = 4$, $\text{PeakMultiplier} = 8.0$, $\text{ActiveSeconds} = 50,400$ (14-hour business window).
- **Calculated Required Peak**: $\frac{50,000 \times 4 \times 8.0}{50,400} = \frac{1,600,000}{50,400} = 31.75 \text{ TPS} \quad (\text{reported as } 31.8 \text{ TPS})$.
- **Headroom Calculation**: $\frac{185 - 31.75}{31.75} \times 100\% \approx 482.7\% \text{ Headroom}$.
- **Distinction**: 50,000 DAU is a commercial tier capacity projection; the measured throughput of 185 TPS is empirical benchmark evidence.
