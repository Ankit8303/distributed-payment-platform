# Operational Runbook: External Payment Provider Outage

**Severity:** SEV-2  
**Target Subsystem:** External Payment / Payout Gateway (`PaymentProvider`, `PayoutProvider`)

---

## 1. Symptoms
- Spike in HTTP 504 / 502 errors on `/api/v1/payments` and `/api/v1/payouts`.
- Payout transactions accumulating in `PENDING_RECONCILIATION` status.
- Elevated rate of gateway connection timeouts (`SocketTimeoutException`).

## 2. Detection
- Alert: `ProviderFailureRateHigh` (threshold: > 15% errors over 5 minutes).
- Micrometer metric: `rate(provider_errors[5m]) > 5`.
- Health check status on external provider adapter reporting degraded.

## 3. Diagnosis
- Inspect application logs for provider error payloads:
  `grep -i "provider" /var/log/app/payment-ledger.log | grep -E "TIMEOUT|502|503"`
- Verify external status page of payment partner (e.g. banking rail / card gateway).
- Test gateway endpoint reachability from backend host:
  `curl -iv -m 5 https://api.mock-provider.com/health`

## 4. Commands
```bash
# Check count of payouts currently in PENDING_RECONCILIATION
psql $DATABASE_URL -c "SELECT status, COUNT(*) FROM payouts GROUP BY status;"

# Check active balance reservations
psql $DATABASE_URL -c "SELECT status, count(*), sum(amount) FROM balance_reservations GROUP BY status;"
```

## 5. Safe Actions
- Retain existing reservations in `ACTIVE` state (funds remain protected).
- Allow the platform's automated Reconciliation Worker (`ReconciliationJob`) to queue items for resolution.
- Enable circuit-breaker or temporary payout throttling if gateway is completely down.

## 6. Unsafe Actions
- **NEVER** mark `PENDING_RECONCILIATION` payouts as `FAILED` or release balance reservations before the gateway confirms failure.
- **NEVER** retry payouts with new idempotency keys or new transaction IDs.
- **NEVER** modify database records manually using raw SQL updates.

## 7. Rollback
- If a bad configuration change caused the issue, revert provider client timeout or credentials in the deployment config.

## 8. Verification
- Verify that once provider restores service, reconciliation worker settles or fails pending transactions deterministically.
- Verify:
  `psql $DATABASE_URL -c "SELECT count(*) FROM payouts WHERE status = 'PENDING_RECONCILIATION';"` drops to zero.

## 9. Post-Incident Checks
- Run data integrity auditor: verify zero balance reservation mismatches.
- Audit ledger transactions to confirm every settled payout has exactly one debit/credit pair.
