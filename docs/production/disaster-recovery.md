# Production Disaster Recovery Runbook

**Document Version**: 1.0  
**Phase**: Phase 17 — Production Hardening  

---

## 1. Disaster Recovery Objectives & Scope

This runbook outlines the operational procedures for complete primary site loss, regional cloud outages, and authoritative state reconstruction.

- **Authoritative Data Layer**: PostgreSQL 16
- **Auxiliary Transports**: Apache Kafka 3.8
- **Auxiliary Caching**: Redis 7
- **RPO**: ≤ 5 minutes (WAL replay target)
- **RTO**: ≤ 60 minutes (Complete restore and traffic cutover)

---

## 2. Disaster Scenarios & Execution Playbooks

### Playbook DR-1: Complete Primary Region Database Loss
1. **Declare Disaster**: Incident Commander activates DR team and logs incident start.
2. **Halt Primary Ingress**: Disable DNS or CDN routing to degraded primary cluster to prevent split-brain transactions.
3. **Provision Target Database**:
   - In secondary region, provision target PostgreSQL instance matching production hardware specifications.
4. **Restore PostgreSQL to Most Recent Consistent Point**:
   - Fetch most recent full backup from cross-region replicated storage.
   - Replay WAL archive to target timestamp (PITR).
   - Execute Authoritative Integrity Verification queries (Double-Entry Balance, Materialized Balance reconciliation).
5. **Re-provision Kafka & Redis Infrastructure**:
   - Kafka and Redis topics/caches in DR region are initialized clean.
   - Kafka consumer groups reset to `earliest` for audit and notification consumers.
6. **Deploy Application Instances in DR Region**:
   - Spin up platform instances pointing to restored PostgreSQL, new Kafka, new Redis.
   - Verify `/actuator/health/readiness` is `UP`.
7. **Reconciliation & Catchup**:
   - Trigger `ReconciliationWorker` to review any in-flight payments that were in `CAPTURING` or `PENDING_RECONCILIATION` when disaster struck.
   - Run Outbox Relay to publish historical events from `outbox_events` to the fresh Kafka cluster.
8. **DNS Cutover & Traffic Ramp**:
   - Update global DNS (e.g. Route 53 / Cloudflare) to route customer traffic to DR region.
   - Monitor error rates, payment latency, and ledger balance checks.

### Playbook DR-2: Total Kafka Cluster Loss
1. Since PostgreSQL is the financial authority and event outbox is transactionally persisted in `outbox_events`, total loss of the Kafka cluster does **NOT** destroy financial state.
2. Provision a new Kafka cluster.
3. Create required topics (`payment.events`, `payment.events.dlq`, `account.events`, `account.events.dlq`, `refund.events`).
4. In PostgreSQL, reset `outbox_events.status = 'PENDING'` for events requiring downstream notification.
5. `OutboxRelayScheduler` automatically publishes all events to the new Kafka cluster.

### Playbook DR-3: Total Redis Cluster Loss
1. Provision a clean Redis instance or cluster.
2. Update application configuration to point to new Redis host.
3. Zero financial data recovery needed; cache warms organically on read operations, rate limiting counters reset safely.
