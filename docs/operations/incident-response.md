# Incident Response Framework & Operational Runbooks

**Governing Phase**: Phase 20 — Complete Production-Readiness Verification  
**Audience**: On-Call SREs, Incident Commanders, Platform Engineers  
**Status**: APPROVED OPERATIONAL SOP  

---

## 1. Incident Classification & Severity Matrix

| Severity Level | Definition | Impact Scope | Initial Response SLA | Escalation Target |
|---|---|---|---|---|
| **SEV-1 (Critical)** | Core financial path down; data corruption risk; security breach; ledger imbalance. | Total payment processing halt or unbalanced ledger transactions. | **< 15 minutes** | VP of Engineering, Lead SRE, Legal/Security |
| **SEV-2 (Major)** | Major functionality degraded; auxiliary system down (Kafka/Redis); elevated error rate (> 2%). | Degraded latency; outbox buffering; rate limiting bypassed. | **< 30 minutes** | Staff SRE, Domain Tech Lead |
| **SEV-3 (Moderate)** | Non-critical component degraded; single merchant affected; background reconciliation delayed. | Operational dashboards degraded; delayed notifications. | **< 2 hours** | On-Call Engineer |
| **SEV-4 (Minor)** | Minor cosmetic defect; non-impacting administrative search slowness. | Administrative UI cosmetic glitch; log formatting warnings. | **Next Business Day** | Product Team |

---

## 2. Incident Command Team Roles

- **Incident Commander (IC)**: Owns the incident lifecycle, coordinates resources, makes release/rollback decisions, and prevents distraction.
- **Technical Lead (TL)**: Leads diagnosis, formulates technical mitigation hypotheses, and oversees live debugging.
- **Communications Lead (CL)**: Drafts internal executive status updates and public customer communication.
- **Scribe**: Records real-time actions, metric snapshots, timeline events, and commands for postmortem analysis.

---

## 3. Incident Lifecycle Stages

1. **Detection & Triage**: Alert fires via Prometheus/Alertmanager; on-call engineer verifies user impact.
2. **Declaration**: Formally declare incident severity in `#incident-war-room`; assign IC, TL, CL.
3. **Containment & Mitigation**: Prioritize customer safety and financial correctness over root-cause discovery.
4. **Verification**: Confirm metrics, error rates, and financial invariants return to baseline.
5. **Recovery & Stand-Down**: Drain outbox buffers, verify reconciliation, and officially close incident.
6. **Blameless Postmortem**: Publish root-cause analysis (RCA), timeline, and action items within 48 hours.

---

## 4. Operational Incident Runbooks (13 Core Scenarios)

### Runbook 1: Payment Failures (Elevated 5xx Error Rate)
- **Symptoms**: `http_server_requests_seconds_count{status=~"5.."}` spikes on `/api/v1/payments`.
- **Diagnosis**: Check container logs for `PaymentException`, `DatabaseException`, or `HikariCP` connection timeouts.
- **Mitigation**:
  1. If connection timeouts: inspect PostgreSQL active connection count and slow queries.
  2. If provider error: verify gateway status and switch to fallback mock/secondary route if available.
  3. Verify idempotency records prevent duplicate charges.

### Runbook 2: Payments Stuck in Reconciliation (`PENDING_RECONCILIATION`)
- **Symptoms**: `payments{status="PENDING_RECONCILIATION"}` count increasing beyond normal threshold (> 50).
- **Diagnosis**: Query database for oldest un-reconciled payments:
  ```sql
  SELECT id, status, updated_at FROM payments WHERE status = 'PENDING_RECONCILIATION' ORDER BY updated_at ASC LIMIT 10;
  ```
- **Mitigation**:
  1. Trigger manual reconciliation worker run: `POST /api/v1/admin/reconciliation/trigger`.
  2. Inspect provider settlement report ingest status.
  3. Verify ledger posting engine creates balanced compensating journal entries upon resolution.

### Runbook 3: Ledger Inconsistency Alert
- **Symptoms**: Prometheus alert `LedgerImbalanceDetected` fires (`ledger_imbalance_total > 0`).
- **Diagnosis**: IMMEDIATE SEV-1. Run double-entry audit query:
  ```sql
  SELECT transaction_id, SUM(CASE WHEN entry_type='DEBIT' THEN amount ELSE -amount END) 
  FROM ledger_entries GROUP BY transaction_id HAVING SUM(...) <> 0;
  ```
- **Mitigation**:
  1. Place platform in read-only maintenance mode immediately to halt new writes.
  2. Trace root cause (DB constraint failure or corrupted transaction).
  3. Post-corrective immutable journal entries; NEVER manually delete or edit existing ledger rows.

### Runbook 4: Kafka Outage / Broker Unavailability
- **Symptoms**: `org.apache.kafka.common.errors.TimeoutException` in application logs.
- **Diagnosis**: Confirm Kafka broker status (`docker compose ps kafka` or broker health check).
- **Mitigation**:
  1. Verify platform financial processing continues! Transactional outbox retains events in PostgreSQL.
  2. Restart Kafka broker.
  3. Observe `OutboxRelayScheduler` automatically draining accumulated records at ~210 events/sec.

### Runbook 5: Redis Outage / Sentinel Partition
- **Symptoms**: `io.lettuce.core.RedisConnectionException` in application logs.
- **Diagnosis**: Confirm Redis process status (`redis-cli ping`).
- **Mitigation**:
  1. Verify platform automatically degrades to PostgreSQL database fallback for account reads.
  2. Verify rate limiting operates in configured `FAIL_OPEN` or `FAIL_CLOSED` mode.
  3. Restart Redis container; cold cache will repopulate naturally from database reads.

### Runbook 6: Database Outage / Connection Pool Exhaustion
- **Symptoms**: `SQLTransientConnectionException: Connection is not available, request timed out after 30000ms`.
- **Diagnosis**: Check PostgreSQL container health, disk space, and active connections (`pg_stat_activity`).
- **Mitigation**:
  1. Identify long-running locks:
     ```sql
     SELECT pid, query, age(clock_timestamp(), query_start) FROM pg_stat_activity WHERE state != 'idle' ORDER BY age DESC;
     ```
  2. Terminate blocking rogue queries: `SELECT pg_cancel_backend(pid);`.
  3. If instance crashed: follow [`backup-restore.md`](file:///c:/Users/Ankit/Downloads/payment-ledger-platform-complete-agent-kit/docs/operations/backup-restore.md) cold restore SOP.

### Runbook 7: Outbox Backlog Accumulation
- **Symptoms**: `outbox_pending_records_total` exceeds 500 for over 5 minutes.
- **Diagnosis**: Inspect `outbox_events` table for high retry counts:
  ```sql
  SELECT status, count(*), max(retry_count) FROM outbox_events GROUP BY status;
  ```
- **Mitigation**:
  1. If Kafka is up, verify relay worker threads are active and not stuck in stale lease.
  2. Stale leases are automatically reclaimed after `lease-seconds: 30`.
  3. If persistent Poison Pill event: flag as `DEAD_LETTER` for investigation; resume relay.

### Runbook 8: High API Latency (p95 > 250ms on Payments)
- **Symptoms**: Alert `PaymentLatencyElevated` fires.
- **Diagnosis**: Check HikariCP connection wait time vs payment provider response duration.
- **Mitigation**:
  1. Check CPU and memory utilization on application and database hosts.
  2. Check for database row-lock contention on popular merchant accounts.
  3. Shed non-critical load (e.g. throttle rate limits on background/admin search endpoints).

### Runbook 9: High Error Rate (General 5xx Spikes)
- **Symptoms**: Error budget consumption rate exceeds 10% in a 1-hour window.
- **Diagnosis**: Inspect Grafana error distribution by endpoint and HTTP status.
- **Mitigation**:
  1. Filter logs by correlation ID on failing requests.
  2. If caused by recent release: trigger immediate container rollback to previous stable digest.

### Runbook 10: Payment Provider Total Outage
- **Symptoms**: Provider returns HTTP 502/503/504 on 100% of outbound gateway calls.
- **Diagnosis**: Check provider status page and network connectivity to gateway endpoints.
- **Mitigation**:
  1. The platform automatically marks payments as `PENDING_RECONCILIATION` without holding DB connections.
  2. Notify customers of temporary processing delay.
  3. As soon as provider recovers, reconciliation workers resolve pending transactions.

### Runbook 11: Security Incident (Credential / Token Compromise)
- **Symptoms**: Unauthorized token usage, suspicious IP access patterns, or leaked secret reported.
- **Diagnosis**: Inspect access logs for compromised user ID or API key.
- **Mitigation**:
  1. Revoke refresh token families immediately:
     ```sql
     UPDATE refresh_tokens SET revoked = true WHERE user_id = :compromised_id;
     ```
  2. If master JWT secret is compromised: rotate `JWT_SECRET` in environment variables and perform rolling restart (invalidating all active sessions).
  3. Freeze affected accounts via `POST /api/v1/admin/accounts/{id}/freeze`.

### Runbook 12: Failed Deployment / Rollback
- **Symptoms**: Application fails liveness/readiness probes after new image rollout.
- **Diagnosis**: Check container startup logs for Flyway migration mismatch or missing environment variables.
- **Mitigation**:
  1. Execute rollback procedure in [`controlled-rollout.md`](file:///c:/Users/Ankit/Downloads/payment-ledger-platform-complete-agent-kit/docs/operations/controlled-rollout.md).
  2. Revert container image tag to previous stable build.
  3. Forward-compatible database schema ensures older application version runs safely.

### Runbook 13: Emergency Database Restore
- **Symptoms**: Total database data corruption or unrecoverable disk failure.
- **Diagnosis**: SEV-1 declared. Follow [`backup-restore.md`](file:///c:/Users/Ankit/Downloads/payment-ledger-platform-complete-agent-kit/docs/operations/backup-restore.md) exactly.
- **Mitigation**:
  1. Provision clean target PostgreSQL 16 instance.
  2. Run `pg_restore` from the latest verified backup dump.
  3. Run financial balance audit before routing user traffic.
