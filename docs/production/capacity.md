# Production Capacity and Sizing Guidelines

**Document Version**: 1.0  
**Phase**: Phase 17 — Production Hardening  

---

## 1. Workload Sizing & Resource Bounds

| Component | Resource Parameter | Production Baseline | Maximum Bound | Sizing Rationale |
|---|---|---|---|---|
| **JVM Runtime** | Memory (`-Xmx` / Container RAM) | 2 GB heap (4 GB container) | 8 GB heap | 75% container allocation via `MaxRAMPercentage=75.0` ensures no cgroup OOM kills. |
| **HikariCP** | Connection Pool Size | 20 connections per pod | 50 connections per pod | PostgreSQL performs best when total connections across all pods ≤ `2 * core_count + effective_spindle_count`. |
| **Tomcat** | Worker Threads | 200 threads | 400 threads | Sized to handle concurrent I/O without excessive context-switch overhead. |
| **Tomcat** | Max Body Size | 2 MB | 5 MB | Prevents memory denial-of-service via huge payloads. |
| **Outbox Relay** | Batch Size | 50 events | 100 events | Prevents long transaction hold times on `outbox_events` table locks. |
| **Outbox Relay** | Poll Interval | 200 ms | 1000 ms | Low latency publishing while bounding DB query frequency. |
| **Kafka Consumers** | `max.poll.records` | 50 records | 200 records | Ensures consumer poll loop completes well within `max.poll.interval.ms` (300s). |
| **Admin Pagination** | Page Size | 20 items default | 100 items max | Hard clamped via `PageUtils.clamp(...)` to eliminate OOM risks during large queries. |

---

## 2. Horizontal Scaling Guidelines

- Application instances are stateless and horizontally scalable behind any standard Layer 7 load balancer.
- PostgreSQL primary is scaled vertically for writes (NVMe SSD storage, high IOPS). Read replicas can serve read-only reporting if required in future phases.
- Outbox relay instances coordinate seamlessly across multiple application pods using atomic database row-level locking (`lease_owner` + `lease_expires_at`).
