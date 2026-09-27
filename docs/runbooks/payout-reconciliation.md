# Operational Runbook: Payout Reconciliation & Ambiguity Resolution

**Severity:** SEV-2  
**Target Subsystem:** Payout Engine (`PayoutService`) / `ReconciliationJob` / Balance Reservations

---

## 1. Symptoms
- Payouts remaining in `PENDING_RECONCILIATION` or stale `PROCESSING` state for > 15 minutes.
- Customers reporting payouts initiated but balance reservation not consumed or released.
- Reconciliation worker logging persistent gateway query errors.

## 2. Detection
- Alert: `PayoutPendingReconciliationSpike` (> 50 items pending reconciliation).
- Query:
  ```sql
  SELECT id, account_id, amount, status, created_at 
  FROM payouts 
  WHERE status = 'PENDING_RECONCILIATION' AND updated_at < NOW() - INTERVAL '15 minutes';
  ```

## 3. Diagnosis
- Identify whether the external provider actually disbursed funds on the banking rail:
  - Query provider gateway portal / settlement API with provider transaction ID (`provider_reference`).
- Check reconciliation attempt history:
  ```sql
  SELECT payout_id, status, error_message, attempt_count, updated_at 
  FROM reconciliation_attempts 
  ORDER BY updated_at DESC LIMIT 20;
  ```

## 4. Commands
```bash
# Trigger immediate manual reconciliation run via admin API
curl -X POST -H "Authorization: Bearer $ADMIN_JWT" \
  http://localhost:8080/api/v1/admin/reconciliation/run

# Query reconciliation summary
curl -X GET -H "Authorization: Bearer $ADMIN_JWT" \
  http://localhost:8080/api/v1/admin/reconciliation/reports
```

## 5. Safe Actions
- Retain `ACTIVE` status on the associated `balance_reservations` until provider confirmation.
- Use `AdminReconciliationController` to trigger targeted reconciliation for the specific batch.
- If provider confirms transaction succeeded: trigger automated settlement path.
- If provider confirms transaction failed/declined: trigger automated release path.

## 6. Unsafe Actions
- **NEVER** release balance reservation while provider outcome is unknown.
- **NEVER** delete payout or balance reservation rows.
- **NEVER** re-issue payout request manually on behalf of the customer.

## 7. Rollback
- Not applicable (state machine transitions are forward-reconciled).

## 8. Verification
- Verify payout status updates to `SETTLED` or `FAILED`.
- Verify balance reservation transitions to `CONSUMED` (if settled) or `RELEASED` (if failed).
- Verify double-entry ledger entries match settled payouts.

## 9. Post-Incident Checks
- Run ledger balance verification script: ensure account available balance matches settled ledger balance minus active reservations.
