# Performance Test Results & Benchmarks

This directory archives summarized benchmark execution runs, raw test metrics, and empirical evidence collected during Phase 19 performance characterization.

## Stored Metrics Format

Every benchmark run produces a JSON or Markdown summary capturing:
1. **Timestamp & Git SHA**
2. **Environment & Hardware Specifications** (CPU cores, RAM, JVM options, Docker container versions)
3. **Dataset Volume** (Total accounts, pre-existing ledger entries, outbox records)
4. **Load Profile** (Virtual Users / Concurrency, Request Rate, Duration, Warmup period)
5. **Observed Latencies** (p50, p90, p95, p99, p99.9, Max)
6. **Throughput & Transaction Velocity** (Total Requests, HTTP Req/s, Successful Financial TPS)
7. **Error Breakdown** (Business 409s vs Infrastructure 5xx/Timeouts)
8. **Infrastructure Utilization** (CPU %, Heap MB, GC pauses, Hikari active/pending connections, Kafka consumer lag)

Raw gigabyte-scale logs are excluded per repository retention policies. Curated baselines and capacity models are formally maintained in `docs/performance/`.
