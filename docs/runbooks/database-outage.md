# Operational Runbook: Database Outage & Connection Pool Starvation

**Severity:** SEV-1  
**Target Subsystem:** PostgreSQL 16 Cluster / HikariCP Pool

---

## 1. Symptoms
- Application returning HTTP 500 errors on all mutating routes.
- Stack trace: `SQLTransientConnectionException: HikariPool-1 - Connection is not available, request timed out after 30000ms`.
- Actuator `/actuator/health` reporting status `DOWN` (db component failure).

## 2. Detection
- Alert: `DatabaseDown` or `HikariPoolActiveConnectionsMaxed`.
- Prometheus metric: `hikaricp_connections_pending > 10` or `hikaricp_connections_timeout_total > 0`.

## 3. Diagnosis
- Check PostgreSQL server process status and resource usage (CPU, disk I/O, disk space).
- Check active locking and long-running transactions:
  ```sql
  SELECT pid, now() - xact_start AS duration, query, state 
  FROM pg_stat_activity 
  WHERE state != 'idle' ORDER BY duration DESC;
  ```
- Look for connection exhaustion caused by unclosed connections or missing timeouts.

## 4. Commands
```bash
# Check database server connectivity
pg_isready -h $DB_HOST -p 5432 -U $DB_USER

# Terminate slow / stuck non-system queries (> 2 minutes)
psql $DATABASE_URL -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE state != 'idle' AND now() - xact_start > interval '2 minutes' AND pid <> pg_backend_pid();"
```

## 5. Safe Actions
- Gracefully scale down non-critical background consumers (e.g. outbox polling or analytics workers) to free connection pool slots for core payment traffic.
- Failover to read-replica / hot standby if the primary node hardware is degraded.
- Clear temporary disk space on DB host if disk is > 95% full.

## 6. Unsafe Actions
- **NEVER** increase pool size beyond PostgreSQL `max_connections` limit (will trigger kernel OOM).
- **NEVER** kill PostgreSQL process with `kill -9` (causes shared memory corruption and crash recovery replay).
- **NEVER** run `VACUUM FULL` during peak traffic hours.

## 7. Rollback
- If pool exhaustion was caused by a newly deployed bad query (e.g., table scan without index), initiate immediate deployment rollback.

## 8. Verification
- Verify `/actuator/health` returns HTTP 200 with `status: UP`.
- Verify HikariCP metric `hikaricp_connections_active` returns to normal baseline.

## 9. Post-Incident Checks
- Run financial integrity auditor to ensure no transactions were left in an inconsistent half-committed state.
- Inspect slow query log to identify unindexed queries.
