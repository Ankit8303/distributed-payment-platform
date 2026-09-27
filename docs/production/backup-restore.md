# Production Backup and Restore Readiness Specification

**Document Version**: 1.0  
**Phase**: Phase 17 — Production Hardening  
**Target Database**: PostgreSQL 16 (Authoritative Financial Source of Truth)  

---

## 1. Executive Summary & Objective

In the Distributed Payment & Ledger Platform, **PostgreSQL is the single source of financial truth**. Neither Kafka nor Redis holds authoritative financial state; loss of Kafka topics or Redis keys is recoverable from PostgreSQL, but loss or corruption of PostgreSQL destroys ledger history.

This document establishes the concrete operational requirements, schedules, retention rules, point-in-time recovery (PITR) procedures, verification cadence, and disaster recovery targets (RPO and RTO).

---

## 2. Recovery Objectives

| Metric | Target | Rationale |
|---|---|---|
| **Recovery Point Objective (RPO)** | **≤ 5 minutes** | PostgreSQL Continuous WAL (Write-Ahead Logging) archiving ensures transactions committed up to 5 minutes before disaster can be replayed. |
| **Recovery Time Objective (RTO)** | **≤ 60 minutes** | Base backup decompression, WAL replay to point-in-time, and application reconnect within 1 hour. |

---

## 3. Backup Strategy & Scheduling

### 3.1 Continuous WAL Archiving (Continuous PITR)
- **Archive Command**: PostgreSQL `archive_command` or dedicated tools (`pgBackRest` / `WAL-G`).
- **Archive Frequency**: Triggered immediately upon 16MB WAL segment fill, or forced after `archive_timeout = 300` (5 minutes).
- **Target Storage**: Geographically replicated object storage (encrypted at rest with AES-256 / SSE-KMS).

### 3.2 Full Physical Backups
- **Frequency**: Daily at 02:00 UTC (off-peak).
- **Format**: Binary base backup (`pg_basebackup` or `pgBackRest backup --type=full`).
- **Retention**:
  - Daily backups: Retained for 30 days.
  - Weekly backups: Retained for 12 weeks.
  - Monthly backups: Retained for 7 years (statutory financial regulatory audit compliance).

### 3.3 Logical Schema Dumps (Secondary Redundancy)
- **Tool**: `pg_dump --schema-only` and `pg_dump --data-only --exclude-table-data=outbox_events`.
- **Frequency**: Weekly snapshot for schema migration verification and cross-version portability testing.

---

## 4. Encryption & Access Control

1. **At Rest**: Backups in cold/hot storage are encrypted with dedicated KMS keys with strict IAM policies.
2. **In Transit**: TLS 1.3 enforced for backup transfer (`sslmode=verify-full`).
3. **Least Privilege**: Application runtime users (`payment_ledger`) cannot read or overwrite backup storage buckets. Dedicated backup service accounts hold append-only write permissions.

---

## 5. Step-by-Step Restore Procedure

### Phase A: Environment Preparation
1. Isolate the target PostgreSQL instance from client traffic. Stop the application services or redirect ingress.
2. Provision a clean data directory `/var/lib/postgresql/data` with correct ownership (`postgres:postgres`, permissions `0700`).

### Phase B: Base Backup Extraction
```bash
# Example restore with pgBackRest or manual extraction
pgbackrest --stanza=payment_ledger --type=time "--target=2026-09-25 14:30:00+00" restore
```
Or with standard tar base backup:
```bash
tar -xzvf base_backup_20260925.tar.gz -C /var/lib/postgresql/data/
```

### Phase C: Point-In-Time Configuration
In `/var/lib/postgresql/data/postgresql.auto.conf`:
```properties
restore_command = 'cp /mnt/wal_archive/%f %p'
recovery_target_time = '2026-09-25 14:30:00 UTC'
recovery_target_action = 'promote'
```
Create signal file:
```bash
touch /var/lib/postgresql/data/recovery.signal
```

### Phase D: Startup & Promotion
1. Start PostgreSQL: `systemctl start postgresql`
2. Monitor WAL replay in `postgresql.log` until recovery reaches target time and promotes instance to primary.
3. Validate connection: `pg_isready -U payment_ledger -d payment_ledger`

---

## 6. Post-Restore Data Integrity Verification

Before allowing application traffic onto the restored database, execute the following authoritative SQL checks:

```sql
-- 1. Double-Entry Balance Integrity: Ensure ALL ledger transactions are balanced
SELECT t.id, t.type,
       SUM(CASE WHEN e.direction = 'DEBIT' THEN e.amount_minor ELSE 0 END) AS debits,
       SUM(CASE WHEN e.direction = 'CREDIT' THEN e.amount_minor ELSE 0 END) AS credits
FROM ledger_transactions t
JOIN ledger_entries e ON e.transaction_id = t.id
GROUP BY t.id, t.type
HAVING SUM(CASE WHEN e.direction = 'DEBIT' THEN e.amount_minor ELSE 0 END) !=
       SUM(CASE WHEN e.direction = 'CREDIT' THEN e.amount_minor ELSE 0 END);
-- Expected result: 0 rows

-- 2. Materialized Balance vs Ledger Derivation Check
SELECT a.id, a.account_number, a.materialized_balance_minor,
       COALESCE(SUM(CASE WHEN e.direction = 'CREDIT' THEN e.amount_minor ELSE -e.amount_minor END), 0) AS derived_balance
FROM accounts a
LEFT JOIN ledger_entries e ON e.account_id = a.id
GROUP BY a.id, a.account_number, a.materialized_balance_minor
HAVING a.materialized_balance_minor != COALESCE(SUM(CASE WHEN e.direction = 'CREDIT' THEN e.amount_minor ELSE -e.amount_minor END), 0);
-- Expected result: 0 rows

-- 3. Outbox Integrity
SELECT status, count(*) FROM outbox_events GROUP BY status;
```

---

## 7. Automated Restore Drills & Verification Cadence

- **Automated Verification Drill**: Every Sunday at 04:00 UTC, an automated job restores the latest base backup + WAL into an ephemeral staging database, executes the 3 integrity verification queries above, and logs the drill outcome.
- **Drill Failure Alerting**: If the automated restore drill fails or takes > 45 minutes, a P1 alert is paged to the Platform Operations team.
