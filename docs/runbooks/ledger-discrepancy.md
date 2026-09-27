# Operational Runbook: Ledger Discrepancy & Balance Mismatch

**Severity:** SEV-1  
**Target Subsystem:** Core Financial Ledger (`LedgerService`, `LedgerEntry`, `LedgerTransaction`)

---

## 1. Symptoms
- Automated nightly or hourly audit report signals `LEDGER_UNBALANCED` or `BALANCE_MISMATCH`.
- Mathematical equation $\sum \text{Debits} \ne \sum \text{Credits}$ fails on a transaction ID.
- Materialized account balance does not equal the cumulative sum of entries in `ledger_entry`.

## 2. Detection
- Alert: `LedgerImbalanceDetected` or `AccountBalanceDiscrepancy`.
- Scheduled auditor execution (`LedgerIntegrityAuditor`) returns non-zero anomalies.

## 3. Diagnosis
- Identify specific transaction IDs failing double-entry balance:
  ```sql
  SELECT transaction_id, 
         SUM(CASE WHEN entry_type = 'DEBIT' THEN amount ELSE 0 END) AS total_debit,
         SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE 0 END) AS total_credit
  FROM ledger_entries
  GROUP BY transaction_id
  HAVING SUM(CASE WHEN entry_type = 'DEBIT' THEN amount ELSE 0 END) <> 
         SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE 0 END);
  ```
- Check if any materialized balance differs from entries:
  ```sql
  SELECT a.id, a.balance AS materialized_balance, COALESCE(SUM(
    CASE WHEN le.entry_type = 'CREDIT' THEN le.amount 
         WHEN le.entry_type = 'DEBIT' THEN -le.amount 
         ELSE 0 END), 0) AS computed_balance
  FROM accounts a
  LEFT JOIN ledger_entries le ON a.id = le.account_id
  GROUP BY a.id, a.balance
  HAVING a.balance <> COALESCE(SUM(
    CASE WHEN le.entry_type = 'CREDIT' THEN le.amount 
         WHEN le.entry_type = 'DEBIT' THEN -le.amount 
         ELSE 0 END), 0);
  ```

## 4. Commands
```bash
# Freeze affected account immediately to prevent further transfers
curl -X POST -H "Authorization: Bearer $ADMIN_JWT" \
  http://localhost:8080/api/v1/admin/accounts/$AFFECTED_ACCOUNT_ID/freeze
```

## 5. Safe Actions
- Freeze the affected accounts immediately via `/api/v1/admin/accounts/{id}/freeze`.
- Identify the source operational event (e.g. payment, payout, or adjustment).
- Once root cause is proven, post a compensating balanced double-entry transaction via `POST /api/v1/admin/adjustments` with a mandatory administrative justification and audit ticket reference.
- Unfreeze account after auditor confirms balance matches ledger entry sum.

## 6. Unsafe Actions
- **STRICT PROHIBITION:** NEVER execute `UPDATE accounts SET balance = ...` via direct SQL.
- **NEVER** modify or delete existing rows in `ledger_entries` (PostgreSQL trigger `trg_immutable_ledger_entries` will abort transaction).
- **NEVER** bypass audit trails.

## 7. Rollback
- Compensating financial adjustment only (double-entry reversal).

## 8. Verification
- Re-run ledger integrity query: confirm 0 unbalanced transactions.
- Verify materialized balance matches `SUM(ledger_entries)`.

## 9. Post-Incident Checks
- Full post-mortem to determine whether an application bug or database concurrency race caused the condition.
- File bug fix and add automated regression test in `LedgerIntegrationTest`.
