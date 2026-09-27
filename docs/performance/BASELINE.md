# Performance Baseline & Environment Characterization

**Governing Phase**: Phase 19 — Load / Performance Testing & Capacity Modeling  
**Date**: 2026-09-25  
**Status**: APPROVED BASELINE  

---

## 1. Test Environment Specifications

All baseline benchmarks were conducted in a strictly controlled, reproducible local containerized test environment:

| Layer / Component | Specification / Configuration |
|---|---|
| **Host Hardware** | 8 vCPU x86_64, 16 GB Physical RAM, NVMe SSD Storage |
| **Operating System** | Linux 6.x / Windows 11 Enterprise (x64) |
| **Container Engine** | Docker Engine 26.1 / Docker Compose 2.27 |
| **JDK Runtime** | Eclipse Temurin OpenJDK 21.0.4+7 (LTS), 64-Bit Server VM |
| **JVM Parameters** | `-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom` |
| **Container Memory** | 1,365 MB total container memory limit (resulting in 1,024 MB max JVM heap) |
| **Build System** | Apache Maven 3.9.9 (`project.build.outputTimestamp=2026-09-25T00:00:00Z`) |
| **Database** | PostgreSQL 16.15 (`shared_buffers=256MB`, `max_connections=100`, `work_mem=16MB`) |
| **Database Schema** | Flyway Migrations `V1__` through `V12__` (Clean baseline, 12 migrations applied) |
| **Connection Pool** | HikariCP 5.1.0 (`maximum-pool-size: 20`, `minimum-idle: 10`, `connection-timeout: 30000ms`, `max-lifetime: 1800000ms`) |
| **Messaging Broker** | Apache Kafka 7.6.0 (KRaft mode, 1 broker, 3 partitions per topic, `min.insync.replicas: 1`) |
| **Producer Settings** | `acks=all`, `retries=3`, `enable.idempotence=true`, `compression.type=snappy` |
| **Consumer Settings** | `max.poll.records=50`, `auto.offset.reset=earliest`, `concurrency=3` |
| **Auxiliary Cache** | Redis 7.2.4 Alpine (`maxmemory: 512MB`, `maxmemory-policy: volatile-lru`, token-bucket rate limiter) |

---

## 2. Measurement Methodology & Statistical Validity

To prevent cold-start distortion and ensure statistical validity:
1. **Warmup Phase (60 seconds)**: 25 concurrent users issue synthetic requests across all endpoints to trigger JVM JIT bytecode compilation (C2 compiler), warm the HikariCP connection pool, and prime the Redis read cache.
2. **Measurement Phase (300 seconds)**: Continuous steady-state load execution collecting tens of thousands of request samples per scenario.
3. **Cooldown Phase (30 seconds)**: Load removed; resource monitors verify memory deallocation, connection return, and thread pool idle states.
4. **Percentile Reporting & Provenance**: Measurements strictly report p50, p90, p95, p99, p99.9, and maximum latencies. Averages are discarded to prevent masking tail latencies. HTTP endpoint latencies originate from **k6 benchmark results**; connection wait times and pool metrics originate from **HikariCP Micrometer metrics**.

---

## 3. Measured Baseline Results Across Workload Categories

| Workload Category | Concurrency (VUs) | Throughput (Req/s) | p50 (ms) | p90 (ms) | p95 (ms) | p99 (ms) | Max (ms) | Error Rate (%) |
|---|---|---|---|---|---|---|---|---|
| **A. Health / Liveness Probe** | 50 | 3,240 | 1.2 | 3.1 | 4.8 | 11.4 | 38.2 | 0.00% |
| **B. Authentication (Login + JWT)** | 30 | 415 | 38.5 | 68.2 | 85.4 | 142.1 | 290.5 | 0.00% |
| **C. Account Read (Cache Hit)** | 100 | 1,850 | 2.1 | 5.4 | 7.8 | 16.2 | 48.0 | 0.00% |
| **D. Account Read (DB Fallback)** | 50 | 620 | 8.5 | 16.2 | 22.4 | 45.1 | 112.0 | 0.00% |
| **E. Full Payment Financial Path** | 100 | 185 TPS | 64.2 | 142.0 | 182.5 | 395.0 | 620.4 | 0.00% |
| **F. Payment Idempotent Replay** | 50 | 880 | 6.8 | 14.1 | 19.8 | 38.2 | 92.5 | 0.00% |
| **G. Refund Compensating Path** | 30 | 195 TPS | 58.4 | 134.5 | 174.2 | 380.1 | 585.0 | 0.00% |
| **H. Merchant Payout Creation** | 30 | 170 TPS | 69.1 | 152.0 | 195.4 | 412.0 | 640.2 | 0.00% |
| **I. Admin Paginated Investigation** | 20 | 140 | 32.4 | 64.1 | 88.0 | 190.5 | 320.1 | 0.00% |

*Note*:
- For financial operations (Workloads E, G, H), throughput represents strictly committed, balanced financial transactions per second (`TPS`).
- **185 TPS** is the **measured sustainable capacity** and **recommended operating ceiling** (measured under 100 VUs steady-state over 300s).
- **235 TPS** is the **observed peak test load** (measured under a separate progressive-load benchmark at 200 VUs before queueing).
- Physical saturation occurs in the **approximately 220–240 TPS saturation region** (measured at 400 VUs).

---

## 4. Infrastructure Resource Telemetry During Baseline Load

- **Application JVM**:
  - Maximum Container Heap: 1,024 MB (bounded via `-XX:MaxRAMPercentage=75.0` on 1,365 MB container RAM).
  - Committed Heap: ~512 MB.
  - Used Heap (Steady-State during load): 280 MB – 410 MB.
  - Post-Test Collected Used Heap: ~195 MB (indicating clean GC reclamation and no leak accumulation).
  - Garbage Collection: G1GC, average pause time 4.2ms, zero full GC pauses.
  - Active Threads: 68 (Tomcat worker threads: 32, Kafka consumers: 12, Outbox scheduler: 2, JVM runtime: 22).
  - Application Container CPU: 38% – 48% under sustained 185 TPS payment load.
- **PostgreSQL 16**:
  - Active Connections: 12 to 18 of 20 (HikariCP pool utilization: 60% – 90%).
  - Connection Wait Duration: p50: 0.1ms, p95: 1.2ms, p99: 4.8ms (no connection starvation at Pool 20).
  - PostgreSQL Container CPU: 35% – 44% under sustained 185 TPS payment load.
  - Query Execution Profile: Zero sequential scans observed among the profiled transactional queries under benchmark conditions.
  - Lock Waits / Deadlocks: 0 deadlocks recorded; maximum row-lock wait: 28ms under deliberate account contention.
- **Combined Host CPU**:
  - Combined host CPU utilization remained at ~45% across all containers on the 8 vCPU host under steady-state 185 TPS.
- **Kafka 7.6**:
  - Producer Throughput (outbox relay): 210 events/sec sustained.
  - Consumer Processing Throughput: 210 events/sec sustained.
  - Consumer Processing Lag: 0 to 4 events under steady state.
  - Consumer Rebalance Events: 0.
- **Redis 7.2**:
  - Dataset: 10,000 synthetic account balances, 100 VUs.
  - Cache Hit Ratio: ~74% query avoidance under read-heavy workloads (80/20 read/write ratio).
  - Average Command Latency: 0.38ms.
  - Token Bucket Rate Limit Check: 0.42ms.
