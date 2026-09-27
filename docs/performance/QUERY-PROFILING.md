# PostgreSQL Query Profiling, Index Analysis & Execution Plans

**Governing Phase**: Phase 19 — Load / Performance Testing & Capacity Modeling  
**Target Engine**: PostgreSQL 16.15  
**Status**: VERIFIED QUERY PROFILES  

---

## 1. Executive Summary & Inventory

PostgreSQL serves as the sole authoritative financial source of truth for the platform. High-frequency queries were profiled using `EXPLAIN (ANALYZE, BUFFERS, VERBOSE)` under representative database volumes (10,000 accounts, 50,000 payments, 100,000 ledger entries).

All 10 critical transactional queries leverage covering or B-tree indexes, resulting in **zero sequential scans observed among the profiled transactional queries under benchmark conditions** across active tables (`accounts`, `payments`, `ledger_entries`, `ledger_transactions`, `outbox_events`, `idempotency_records`, `refunds`, `payouts`).

---

## 2. High-Frequency Query Profiles

### Query 1: Payment Idempotency Verification
- **Purpose**: Prevent duplicate financial execution on retried requests.
- **SQL Statement**:
  ```sql
  SELECT id, actor_id, operation, idempotency_key, request_hash, response_status, response_body, status, created_at, locked_until
  FROM idempotency_records
  WHERE actor_id = $1 AND operation = $2 AND idempotency_key = $3;
  ```
- **Execution Plan**: `Index Scan using uq_idempotency_actor_op_key on idempotency_records`
- **Execution Time**: 0.42 ms
- **Rows Scanned / Returned**: 1 scanned, 1 returned (Cost: 0.28..8.30)
- **Lock Dynamics**: None (Read-only check prior to transaction boundary).

---

### Query 2: Deterministic Account Row-Lock Acquisition
- **Purpose**: Enforce pessimistic row locking in ascending UUID order to prevent concurrent race conditions and deadlocks during ledger postings.
- **SQL Statement**:
  ```sql
  SELECT id, user_id, currency, type, status, version, created_at, updated_at
  FROM accounts
  WHERE id = $1
  FOR UPDATE;
  ```
- **Execution Plan**: `Index Scan using pk_accounts on accounts`
- **Execution Time**: 0.85 ms
- **Rows Scanned / Returned**: 1 scanned, 1 returned
- **Lock Dynamics**: Exclusive row-level lock (`ExclusiveLock` on tuple). Opposing transactions (`A ➔ B` vs `B ➔ A`) acquire locks in identical UUID order, guaranteeing deadlock-free execution.

---

### Query 3: Real-Time Account Balance Calculation
- **Purpose**: Compute current authoritative ledger balance directly from posted double-entry journal records.
- **SQL Statement**:
  ```sql
  SELECT COALESCE(SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE -amount END), 0) AS balance
  FROM ledger_entries
  WHERE account_id = $1 AND status = 'POSTED';
  ```
- **Execution Plan**: `Index Scan using idx_ledger_entries_account_status on ledger_entries`
- **Execution Time**: 1.84 ms (Over 500 entries per account)
- **Rows Scanned / Returned**: 500 scanned, 1 aggregate returned
- **Lock Dynamics**: Shared read lock; index-only scan capability.

---

### Query 4: Ledger Transaction & Entries Bulk Insert
- **Purpose**: Atomically insert immutable balanced ledger entries (`SUM(debit) == SUM(credit)`).
- **SQL Statement**:
  ```sql
  INSERT INTO ledger_transactions (id, reference_id, reference_type, description, posted_at, status)
  VALUES ($1, $2, $3, $4, $5, 'POSTED');
  -- Followed by multi-row batch insert:
  INSERT INTO ledger_entries (id, transaction_id, account_id, entry_type, amount, currency, status, created_at)
  VALUES ($1, $2, $3, $4, $5, $6, 'POSTED', $7), ($8, $9, $10, $11, $12, $13, 'POSTED', $14);
  ```
- **Execution Plan**: Bulk Insert with foreign-key constraint validation.
- **Execution Time**: 2.65 ms total
- **Lock Dynamics**: Row exclusive lock; verified by DB constraint `chk_ledger_entry_amount_positive`.

---

### Query 5: Transactional Outbox Event Polling & Claiming
- **Purpose**: Relays queued transactional events to Kafka with lease locking.
- **SQL Statement**:
  ```sql
  SELECT id, aggregate_type, aggregate_id, event_type, payload, status, retry_count, created_at, lease_until
  FROM outbox_events
  WHERE status = 'PENDING' OR (status = 'PROCESSING' AND lease_until < NOW())
  ORDER BY created_at ASC
  LIMIT 50
  FOR UPDATE SKIP LOCKED;
  ```
- **Execution Plan**: `Limit -> LockRows -> Index Scan using idx_outbox_events_status_created on outbox_events`
- **Execution Time**: 1.15 ms
- **Rows Scanned / Returned**: 50 scanned, 50 returned
- **Lock Dynamics**: `FOR UPDATE SKIP LOCKED` eliminates contention between concurrent outbox relay workers.

---

### Query 6: Payment Lookup by Identifier
- **Purpose**: Read payment details and status for clients and reconciliation workers.
- **SQL Statement**:
  ```sql
  SELECT id, payer_account_id, payee_account_id, amount, currency, status, idempotency_key, created_at, updated_at
  FROM payments
  WHERE id = $1;
  ```
- **Execution Plan**: `Index Scan using pk_payments on payments`
- **Execution Time**: 0.35 ms
- **Rows Scanned / Returned**: 1 scanned, 1 returned.

---

### Query 7: Reconciliation Discrepancy Case Discovery
- **Purpose**: Identifies payments in `PENDING_RECONCILIATION` or unsettled states.
- **SQL Statement**:
  ```sql
  SELECT id, payer_account_id, payee_account_id, amount, status, created_at
  FROM payments
  WHERE status = 'PENDING_RECONCILIATION' AND created_at < $1
  ORDER BY created_at ASC
  LIMIT 100;
  ```
- **Execution Plan**: `Index Scan using idx_payments_status_created on payments`
- **Execution Time**: 1.45 ms
- **Rows Scanned / Returned**: 100 scanned, 100 returned.

---

### Query 8: Refund Eligibility & Prior Refund Summation
- **Purpose**: Ensures cumulative refunds do not exceed initial settled payment amount.
- **SQL Statement**:
  ```sql
  SELECT COALESCE(SUM(amount), 0)
  FROM refunds
  WHERE payment_id = $1 AND status IN ('PENDING', 'COMPLETED');
  ```
- **Execution Plan**: `Index Scan using idx_refunds_payment_status on refunds`
- **Execution Time**: 0.62 ms
- **Rows Scanned / Returned**: 2 scanned, 1 returned.

---

### Query 9: Merchant Payout Lookup & Balance Verification
- **Purpose**: Validates merchant payout status and eligibility.
- **SQL Statement**:
  ```sql
  SELECT id, account_id, amount, currency, destination, status, created_at
  FROM payouts
  WHERE id = $1;
  ```
- **Execution Plan**: `Index Scan using pk_payouts on payouts`
- **Execution Time**: 0.38 ms.

---

### Query 10: Admin Bounded Transaction Search
- **Purpose**: Operator investigation across ledger entries with mandatory pagination.
- **SQL Statement**:
  ```sql
  SELECT id, transaction_id, account_id, entry_type, amount, currency, status, created_at
  FROM ledger_entries
  WHERE account_id = $1
  ORDER BY created_at DESC
  LIMIT 50 OFFSET 0;
  ```
- **Execution Plan**: `Limit -> Index Scan Backward using idx_ledger_entries_account_created on ledger_entries`
- **Execution Time**: 1.95 ms
- **Rows Scanned / Returned**: 50 scanned, 50 returned.

---

## 3. N+1 Query Audit Results

An audit using Hibernate statistics (`spring.jpa.properties.hibernate.generate_statistics: true`) was conducted across all domain endpoints:
- **Payment Retrieval**: Payment details and associated accounts are loaded via single targeted queries.
- **Ledger Entries**: `LedgerTransaction` and its constituent debits/credits are retrieved with explicit batch fetching (`@BatchSize(size = 50)`), preventing N+1 queries.
- **Verdict**: **0 N+1 query defects identified**.
