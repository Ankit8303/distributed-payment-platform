# Platform Capacity Model & Sizing Derivations

**Governing Phase**: Phase 19 — Load / Performance Testing & Capacity Modeling  
**Date**: 2026-09-25  
**Status**: APPROVED CAPACITY MODEL  

---

## 1. Executive Summary & Core Terminological Distinction

To prevent speculative capacity claims and conflation between user counts and throughput, this model strictly distinguishes:
1. **Registered User Accounts**: Database entities stored durably on disk (scale: millions).
2. **Daily Active Users (DAU)**: Unique users who initiate at least one transactional or account session within a 24-hour window (scale: tens of thousands).
3. **Concurrent Active Users (VUs)**: Simulated clients with HTTP requests actively in-flight simultaneously (scale: hundreds).
4. **General HTTP Throughput (Req/s)**: Total incoming HTTP requests per second across all endpoints including health checks and cached reads (scale: thousands).
5. **Financial Transaction Velocity (TPS)**: Authoritative, balanced double-entry ledger mutations committed durably to PostgreSQL (scale: hundreds).

*Rule*: A benchmark measuring financial TPS does NOT equate to concurrent users, nor does it support claims of "supports X million active users" without explicit modeling of user request frequency and daily distribution.

---

## 2. Mathematical Traffic Derivation

### 2.1 The Peak Capacity Formula
```text
                  DAU × TransactionsPerDAU × PeakMultiplier
Peak TPS (Req) = ───────────────────────────────────────────
                            ActiveSeconds (T)
```

Where:
- $\text{DAU} = 50,000$ (Daily Active Users in a standard commercial tier deployment).
- $\text{TransactionsPerDAU} = 4$ transactions/user/day.
- $\text{PeakMultiplier} = 8.0$ (Captures concentrated traffic bursts such as payday processing, flash sales, and end-of-day merchant settlement).
- $\text{ActiveSeconds} = 14 \text{ hours} \times 3,600 \text{ s/hr} = 50,400 \text{ seconds}$ (The active daylight business operating window).

### 2.2 Numerical Calculation & Traceability
1. **Numerator (Peak Day Equivalent Volume)**:
   $$\text{Numerator} = 50,000 \times 4 \times 8.0 = 1,600,000 \text{ transactions (peak day equivalent)}$$
2. **Denominator (Active Operating Window)**:
   $$\text{Denominator} = 50,400 \text{ seconds}$$
3. **Calculated Required Peak Throughput**:
   $$\text{Peak Required TPS} = \frac{1,600,000}{50,400} = 31.746... \approx 31.75 \text{ TPS} \quad (\text{reported as } 31.8 \text{ TPS})$$

### 2.3 Measured Capacity Headroom
- **Headroom Definition**:
  $$\text{Headroom} = \frac{\text{Capacity} - \text{Demand}}{\text{Demand}} \times 100\%$$
- **Parameters**:
  - Measured Sustainable Capacity ($\text{Capacity}$) = **185 TPS** (Recommended Operating Ceiling, measured at 100 VUs steady state over 300s; a separate progressive-load benchmark observed 235 TPS peak at 200 VUs).
  - Modeled Peak Demand ($\text{Demand}$) = **31.746 TPS** (or **31.8 TPS** rounded).
- **Calculation**:
  $$\text{Headroom} = \frac{185 - 31.746}{31.746} \times 100\% = \frac{153.254}{31.746} \times 100\% \approx 482.75\% \approx 482.7\%$$
  $$(\text{Using } 31.8 \text{ TPS: } \frac{185 - 31.8}{31.8} \times 100\% = \frac{153.2}{31.8} \times 100\% \approx 481.76\% \approx 482\%)$$
- **Conclusion**: The measured sustainable capacity of 185 TPS provides **~482% headroom** above the modeled peak commercial requirement of 31.8 TPS.

---

## 3. Resource Dimensioning & Sizing Formulas

### 3.1 Database Connection Pool Sizing (HikariCP)
Database connection pool sizing is guided by Little's Law:
$$\text{Active DB Connections} = \text{Peak TPS} \times \text{Average Transaction Duration (seconds)}$$

- **Measured Average Transaction Duration**: $64.2 \text{ ms} = 0.0642 \text{ s}$ (Includes account row-lock, balance query, ledger insert, outbox write, and commit).
- **Connections Required at Modeled Peak (31.8 TPS)**:
  $$\text{Required Connections} = 31.8 \times 0.0642 \approx 2.04 \text{ connections}$$
- **Configured Maximum Pool Size**: **20 connections**.
- **Preferred Measured Operating Point**:
  Pool size 20 sustains 185 TPS with average connection acquire wait time of 1.2 ms and PostgreSQL CPU at 44%. While Pool 30 sustains 192 TPS (+3.7%), it requires 50% more open database connections and raises PostgreSQL CPU to 56% with diminishing returns.

### 3.2 Kafka Event Streaming Capacity
- **Event Multiplier**: Each payment lifecycle produces an average of 2.5 events (e.g. `PaymentCreated`, `PaymentSettled`, `OutboxRelayed`).
- **Required Kafka Event Rate at Peak (31.8 TPS)**:
  $$\text{Event Rate} = 31.8 \times 2.5 \approx 79.5 \text{ events/sec}$$
- **Measured Outbox Relay Sustainable Throughput**: **210 events/sec** (batch size 50, 1000ms poll interval).
- **Headroom**: $164\%$ over peak event generation.

### 3.3 Redis Auxiliary Cache Sizing
- **Payload Size per Account Record**: 1.8 KB (JSON serialization + token bucket metadata).
- **Active Working Set**: 50,000 DAU $\times 1.8 \text{ KB} = 90,000 \text{ KB} \approx 88 \text{ MB}$.
- **Configured Redis RAM (`maxmemory`)**: **512 MB** (volatile-LRU eviction).
- **Memory Headroom**: $480\%$ buffer for auxiliary keys, distributed rate-limit token buckets, and idempotency locks.

---

## 4. Saturation Points & Safe Operating Limits

Empirical load stepping identified the exact operating zones:

| Operating Zone | Throughput Range (TPS) | Concurrency (VUs) | p95 Latency | HikariCP Active | CPU Utilization | Operating Status |
|---|---|---|---|---|---|---|
| **GREEN (Optimal)** | 0 – 140 TPS | 10 – 75 VUs | < 160 ms | 6 – 14 | 15% – 45% | **Safe Operating Region**: Sub-millisecond connection acquire times, 0 queueing. |
| **YELLOW (Elevated)** | 140 – 210 TPS | 75 – 150 VUs | 160 – 350 ms | 15 – 19 | 45% – 70% | **Elevated Load**: Connection pool approaching 90% utilization; locks resolve normally. |
| **RED (Saturation)** | > 220 TPS | > 180 VUs | > 500 ms | 20 (Saturated) | > 80% | **Saturation Region**: HikariCP connection wait times escalate, request queueing begins. |

### The Empirical Bottleneck:
Empirical profiling confirms that **PostgreSQL HikariCP connection pool capacity (20 connections)** and **pessimistic row-lock serialization on high-contention accounts** represent the physical saturation region, occurring around **220–240 TPS**. CPU, network I/O, and Redis remain well below exhaustion limits.
