# Operational Runbook: Database Disaster Recovery & Point-In-Time Restore

**Severity:** SEV-1  
**Target Subsystem:** PostgreSQL 16 Backup & Restore Pipeline (WAL-G / pg_dump)

---

## 1. Symptoms
- Catastrophic database hardware failure, unrecoverable data corruption, or accidental drop.
- Loss of primary database cluster requiring complete restore from backup.

## 2. Detection
- Total database cluster unavailability; failover standby unavailable.
- Inability to recover using standard replication mechanisms.

## 3. Diagnosis
- Identify the most recent verified full base backup and continuous Write-Ahead Log (WAL) archive.
- Determine target Point-In-Time (recovery target time prior to corruption event).

## 4. Commands
```bash
# 1. Stop application traffic immediately (prevent writes to corrupted instance)
kubectl scale deployment/payment-ledger-app --replicas=0

# 2. Restore base backup to clean staging/recovery host
pg_restore -h $RESTORE_HOST -U $DB_USER -d payment_ledger_db -v /backups/latest_verified.dump

# 3. Apply WAL archives to target recovery point
# Configure recovery.signal and postgresql.conf with restore_command

# 4. Verify database consistency after recovery
psql -h $RESTORE_HOST -U $DB_USER -d payment_ledger_db -c "SELECT count(*) FROM ledger_entries;"
```

## 5. Safe Actions
- Stop all incoming application traffic (scale replicas to 0) before initiating restore.
- Perform restore into an isolated environment first to validate backup integrity.
- Execute the Financial Ledger Integrity Auditor (`LedgerIntegrityAuditor`) immediately upon database startup BEFORE routing customer traffic.

## 6. Unsafe Actions
- **NEVER** route live customer traffic to the restored database before running the financial balance audit.
- **NEVER** overwrite existing corrupted disks/volumes without taking a forensic snapshot first.

## 7. Rollback
- Maintain previous database snapshot if restore fails to start.

## 8. Verification
- Verify `flyway_schema_history` table matches expected migration version.
- Verify double-entry balance:
  `SELECT SUM(CASE WHEN entry_type = 'DEBIT' THEN amount ELSE -amount END) FROM ledger_entries;` equals 0.
- Verify zero orphan transactions exist.

## 9. Post-Incident Checks
- Reconcile transactions against payment gateway settlement statements for any transactions processed during the outage window.
- Resume traffic gradually (canary rollout).
