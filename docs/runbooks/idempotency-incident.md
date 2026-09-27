# Operational Runbook: Idempotency Key Conflict & Duplicate Processing

**Severity:** SEV-2  
**Target Subsystem:** Idempotency Framework (`IdempotencyRecord`, `IdempotencyFilter`, `IdempotencyService`)

---

## 1. Symptoms
- Elevated rate of HTTP 409 Conflict (`IDEMPOTENCY_KEY_PAYLOAD_MISMATCH`) errors on payments/payouts.
- Clients reporting identical requests receiving unexpected responses.
- Database unique constraint violations on `uk_idempotency_key_scope`.

## 2. Detection
- Alert: `IdempotencyConflictSpike` (> 20 conflicts per minute).
- Application logs showing `IdempotencyConflictException: Payload hash mismatch for key`.

## 3. Diagnosis
- Identify whether client applications are reusing the same `Idempotency-Key` across distinct transaction payloads:
  ```sql
  SELECT idempotency_key, count(*), array_agg(request_hash) 
  FROM idempotency_records 
  GROUP BY idempotency_key 
  HAVING count(*) > 1;
  ```
- Check if concurrent requests with the same key are arriving within milliseconds of each other.

## 4. Commands
```bash
# Query recent idempotency records for a specific key
psql $DATABASE_URL -c "SELECT id, idempotency_key, resource_type, status, created_at FROM idempotency_records WHERE idempotency_key = '$KEY';"
```

## 5. Safe Actions
- Confirm that the database unique index `uk_idempotency_key_scope` is intact and active.
- Verify that only one operation was processed by checking the associated financial entity.
- Advise client application to generate a unique UUID v4 for each distinct financial intent.

## 6. Unsafe Actions
- **NEVER** delete existing idempotency records to "allow the customer to retry". Deleting the record will cause duplicate financial execution (double debit or double payout).
- **NEVER** disable idempotency hashing in application configuration.

## 7. Rollback
- Not applicable.

## 8. Verification
- Verify that each idempotency key has exactly one financial side effect.
- Verify query:
  `SELECT count(*) FROM payments WHERE idempotency_key = '$KEY';` equals 1.

## 9. Post-Incident Checks
- Review client integration telemetry and ensure client SDK generates true UUIDs.
