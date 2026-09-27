# Performance & Load Test Plan

Measure rather than claim capacity.

Scenarios:
1. authentication
2. payment creation
3. duplicate idempotent requests
4. concurrent payment attempts
5. transaction history reads
6. refund creation
7. Kafka consumer throughput
8. reconciliation workload

Capture:
- throughput
- p50/p95/p99 latency
- CPU/memory
- DB connection utilization
- DB query latency
- Kafka lag
- error rate

Record workload assumptions and hardware/environment for every result.
