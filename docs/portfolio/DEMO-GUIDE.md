# Live System Demonstration Guide (10–15 Minute Interview Flow)

**Governing Phase**: Phase 21 — Flagship Portfolio Packaging & GitHub Release  
**Target Audience**: Interviewers, Reviewers, Engineering Hiring Managers  
**Prerequisites**: Stack running via `docker compose up -d` and application running on port `8080`.  

---

## Overview

This guide provides a structured 10-part, 15-minute live technical walkthrough demonstrating the core financial integrity, idempotency, concurrency, resilience, and operational capabilities of the platform using real API endpoints.

---

### Demo 1: User Authentication & JWT Issuance
- **Objective**: Demonstrate secure authentication and token issuance.
- **Request**:
  ```bash
  curl -s -X POST http://localhost:8080/api/v1/auth/login \
    -H "Content-Type: application/json" \
    -d '{"username": "customer1@example.com", "password": "Password123!"}'
  ```
- **Expected Result**: HTTP 200 OK with `accessToken` (HMAC-SHA256, 15m expiry) and `refreshToken`.
- **What to Notice**: Password verified via BCrypt strength 12; refresh token persisted as SHA-256 hash in database.

---

### Demo 2: Account Balance Query & Auxiliary Cache
- **Objective**: Retrieve account details and observe cache behavior.
- **Request**:
  ```bash
  curl -s -X GET http://localhost:8080/api/v1/accounts/11111111-1111-1111-1111-111111111111/balance \
    -H "Authorization: Bearer $TOKEN"
  ```
- **Expected Result**: HTTP 200 OK with `availableBalanceMinor: 100000` ($1,000.00 USD) and `currency: "USD"`.
- **What to Notice**: Sub-3ms response served from Redis read cache. If Redis is down, automatically falls back to PostgreSQL.

---

### Demo 3: Critical Payment & Ledger Settlement
- **Objective**: Execute a real-time fund transfer between two customer accounts.
- **Request**:
  ```bash
  curl -s -X POST http://localhost:8080/api/v1/payments \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: pay-demo-001" \
    -d '{
      "payerAccountId": "11111111-1111-1111-1111-111111111111",
      "payeeAccountId": "22222222-2222-2222-2222-222222222222",
      "amountMinor": 2500,
      "currency": "USD",
      "description": "Coffee and pastry"
    }'
  ```
- **Expected Result**: HTTP 201 Created with `status: "SETTLED"`, `paymentId`, and `ledgerTransactionId`.
- **What to Notice**: Payment state machine transitions `CREATED` ➔ `CAPTURING` ➔ `SETTLED`. Exactly $25.00 moved.

---

### Demo 4: Double-Entry Ledger Verification
- **Objective**: Verify that the payment produced perfectly balanced double-entry ledger entries.
- **Request**:
  ```bash
  curl -s -X GET http://localhost:8080/api/v1/ledger/transactions/$LEDGER_TX_ID \
    -H "Authorization: Bearer $TOKEN"
  ```
- **Expected Result**: Exactly two balanced entries:
  - Account 1 (Payer): `DEBIT` 2,500 minor units ($25.00).
  - Account 2 (Payee): `CREDIT` 2,500 minor units ($25.00).
- **What to Notice**: $\sum \text{Debits} == \sum \text{Credits}$. Entries are append-only and immutable.

---

### Demo 5: Durable Idempotency Replay
- **Objective**: Demonstrate that repeating an identical payment request does NOT double-charge.
- **Request**: Repeat the exact request from **Demo 3** with identical `Idempotency-Key: pay-demo-001`.
- **Expected Result**: HTTP 201 Created with the exact same response body and `paymentId`.
- **What to Notice**: Idempotency filter intercepts the request; zero additional ledger entries are created. The payer balance remains exactly $975.00, proving double-charge immunity.

---

### Demo 6: Concurrent Opposing Transfers (Deadlock Avoidance)
- **Objective**: Verify that bidirectional concurrent transfers (`A ➔ B` and `B ➔ A`) execute with 0 deadlocks.
- **Request**:
  Execute 20 parallel transfers alternating between Account A and Account B using a background bash loop or Apache Bench (`ab`):
  ```bash
  for i in {1..10}; do
    curl -s -X POST http://localhost:8080/api/v1/payments -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: dead-demo-A-$i" -H "Content-Type: application/json" -d '{"payerAccountId":"11111111-1111-1111-1111-111111111111","payeeAccountId":"22222222-2222-2222-2222-222222222222","amountMinor":100,"currency":"USD"}' &
    curl -s -X POST http://localhost:8080/api/v1/payments -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: dead-demo-B-$i" -H "Content-Type: application/json" -d '{"payerAccountId":"22222222-2222-2222-2222-222222222222","payeeAccountId":"11111111-1111-1111-1111-111111111111","amountMinor":100,"currency":"USD"}' &
  done; wait
  ```
- **Expected Result**: 100% of requests succeed with HTTP 201; 0 database deadlock exceptions (`PSQLException: deadlock detected`).
- **What to Notice**: The `UUID.compareTo` deterministic lock acquisition order completely prevents circular wait conditions.

---

### Demo 7: Payment Gateway Timeout & Reconciliation
- **Objective**: Demonstrate graceful handling when an external payment provider experiences latency.
- **Request**: Issue payment with synthetic header triggering simulated 5,000ms provider timeout.
- **Expected Result**: HTTP 202 Accepted with `status: "PENDING_RECONCILIATION"`.
- **What to Notice**: Database transaction is NOT held during the 5s timeout. The payment awaits automated reconciliation without holding database connections or creating unbalanced ledger entries.

---

### Demo 8: Kafka Broker Outage & Outbox Buffering
- **Objective**: Demonstrate that payment processing continues safely when Kafka is completely offline.
- **Setup & Request**:
  1. Stop Kafka: `docker compose stop kafka`
  2. Issue payment request from Demo 3 with new idempotency key `pay-demo-kafka-down`.
- **Expected Result**: Payment succeeds with HTTP 201 Created and commits to PostgreSQL!
- **What to Notice**: The event is buffered in the PostgreSQL `outbox_events` table (`status = 'PENDING'`). Restart Kafka (`docker compose start kafka`); the `OutboxRelayScheduler` automatically catches up at 210 events/sec.

---

### Demo 9: Redis Outage & Automatic Database Fallback
- **Objective**: Demonstrate zero downtime and zero data loss during a cache outage.
- **Setup & Request**:
  1. Stop Redis: `docker compose stop redis`
  2. Request account balance from Demo 2.
- **Expected Result**: HTTP 200 OK returned with accurate balance.
- **What to Notice**: Application catches Redis connection exception and automatically falls back to PostgreSQL. Latency rises slightly (+6.4 ms), but error rate is 0.00%.

---

### Demo 10: Administrative Investigation & Account Freeze
- **Objective**: Inspect administrative controls and audit enforcement.
- **Request**:
  ```bash
  curl -s -X POST http://localhost:8080/api/v1/admin/accounts/11111111-1111-1111-1111-111111111111/freeze \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -H "Content-Type: application/json" \
    -d '{"reason": "Suspicious velocity investigation"}'
  ```
- **Expected Result**: HTTP 200 OK with `status: "FROZEN"`. Subsequent payment attempts against this account immediately return HTTP 403 Forbidden (`ACCOUNT_FROZEN`).
- **What to Notice**: Audit event is persisted; admin cannot arbitrarily edit balances, preserving non-repudiation.
