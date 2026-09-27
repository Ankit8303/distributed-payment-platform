# Payment Processing & Ledger Settlement Lifecycle

**Governing Phase**: Phase 21 — Flagship Portfolio Packaging & GitHub Release  
**Domain**: Financial Transaction Processing & Double-Entry Accounting  
**Status**: VERIFIED SETTLEMENT LIFECYCLE  

---

## 1. End-to-End Payment Sequence Diagram

The diagram below details the complete execution path for a financial payment from incoming HTTP request to asynchronous Kafka notification:

```mermaid
sequenceDiagram
    autonumber
    actor Client as Customer / Merchant
    participant Sec as Security / Idempotency Filter
    participant PS as PaymentService
    participant PGW as External Payment Gateway
    participant AR as AccountRepository (DB)
    participant LE as LedgerPostingEngine
    participant OB as OutboxService (DB)
    participant DB as PostgreSQL 16 (ACID Tx)
    participant K as Apache Kafka

    Client->>Sec: POST /api/v1/payments (Payload, Idempotency-Key)
    Sec->>DB: Query idempotency_records WHERE key = :key
    alt Idempotency Key Already Processed
        Sec-->>Client: Return Cached Response (HTTP 200/201)
    else First-Time Request
        Sec->>DB: Insert idempotency_record (status: 'IN_FLIGHT')
        Sec->>PS: processPayment(request)
        
        Note over PS,PGW: Non-Blocking External Call (No DB Tx Held)
        PS->>PGW: Authorize & Capture Charge
        alt Gateway Timeout / Network Ambiguity
            PS->>DB: Save Payment(status: PENDING_RECONCILIATION)
            PS-->>Client: Return HTTP 202 Accepted (Pending Reconciliation)
        else Gateway Success
            Note over PS,DB: Begin Database Transaction Boundary (@Transactional)
            PS->>DB: Save Payment(status: CAPTURING)
            
            Note over PS,AR: Deterministic Row Locking (UUID.compareTo)
            PS->>AR: Lock Account A (FOR UPDATE)
            PS->>AR: Lock Account B (FOR UPDATE)
            
            PS->>LE: postDoubleEntryJournal(...)
            Note over LE: Enforce Invariant: SUM(debit) == SUM(credit)
            LE->>DB: INSERT INTO ledger_transactions (id, status: POSTED)
            LE->>DB: INSERT INTO ledger_entries (Account A, DEBIT, amount)
            LE->>DB: INSERT INTO ledger_entries (Account B, CREDIT, amount)
            
            PS->>OB: enqueueEvent(PaymentSettledEvent)
            OB->>DB: INSERT INTO outbox_events (status: PENDING)
            
            PS->>DB: Save Payment(status: SETTLED)
            Note over DB: COMMIT TRANSACTION (All updates durable)
            
            PS-->>Client: Return HTTP 201 Created (Payment Settled)
            
            Note over DB,K: Asynchronous Outbox Relay (Decoupled Worker)
            loop Every 1000ms
                OB->>DB: SELECT ... FOR UPDATE SKIP LOCKED LIMIT 50
                OB->>K: Publish to payment.events (acks=all)
                OB->>DB: UPDATE outbox_events SET status = 'PUBLISHED'
            end
        end
    end
```

---

## 2. Payment State Machine Transitions

Every payment progresses through a strictly deterministic finite state machine (FSM) defined in `PaymentState.java`:

```mermaid
stateDiagram-v2
    [*] --> CREATED: Request Received
    CREATED --> AUTHORIZING: Gateway Request Initiated
    AUTHORIZING --> AUTHORIZED: Provider Approval
    AUTHORIZING --> DECLINED: Provider Rejection / Insufficient Funds
    AUTHORIZING --> PENDING_RECONCILIATION: Gateway Timeout (504/Read Timeout)
    AUTHORIZED --> CAPTURING: Lock Accounts in UUID Order
    CAPTURING --> SETTLED: Ledger Balanced & Outbox Enqueued
    CAPTURING --> FAILED: Account Frozen / Balance Constraint Violation
    PENDING_RECONCILIATION --> SETTLED: Reconciliation Engine Verified Settlement
    PENDING_RECONCILIATION --> FAILED: Reconciliation Engine Verified Failure
    DECLINED --> [*]
    FAILED --> [*]
    SETTLED --> [*]
```

---

## 3. Critical Financial Invariants Enforced During Settlement

1. **Deterministic Concurrency Control**:
   When transferring funds between `Account A` and `Account B`, locks are always acquired in ascending lexicographical UUID order:
   ```java
   UUID firstLock = accountA.getId().compareTo(accountB.getId()) < 0 ? accountA.getId() : accountB.getId();
   UUID secondLock = accountA.getId().compareTo(accountB.getId()) < 0 ? accountB.getId() : accountA.getId();
   accountRepository.findByIdForUpdate(firstLock);
   accountRepository.findByIdForUpdate(secondLock);
   ```
   *Guarantee*: Opposing concurrent transfers (`A ➔ B` and `B ➔ A`) acquire locks in identical sequence, completely eliminating circular wait conditions and guaranteeing **0 deadlocks**.

2. **Immutable Double-Entry Ledger Posting**:
   A payment settlement always creates:
   - Exactly one `LedgerTransaction` (`status = 'POSTED'`).
   - Exactly one debit entry: `amountMinor` debited from Payer Account.
   - Exactly one credit entry: `amountMinor` credited to Payee Account.
   - Database constraint verification: $\text{Total Debits} - \text{Total Credits} == 0$.

3. **Atomic Outbox Enqueue**:
   The `PaymentSettledEvent` is written to the `outbox_events` table in PostgreSQL within the **exact same database transaction** that posts the ledger entries. This guarantees that an event is never published to Kafka if the database transaction rolls back, and an event is never lost if the application crashes immediately following commit.
