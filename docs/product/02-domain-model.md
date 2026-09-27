# Domain Model Specification

## 1. Domain Architecture & Aggregate Boundaries
The platform domain is structured using Domain-Driven Design (DDD) principles. Aggregates define explicit transactional consistency boundaries. Inter-aggregate interactions that require strict financial atomicity are orchestrated within the application service layer via database ACID transactions.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                             Payment Aggregate                               │
│  - Payment (Root)                                                           │
│  - IdempotencyReference (Value Object)                                      │
│  - Money (Value Object: amount + currency)                                  │
└──────────────────────────────────────┬──────────────────────────────────────┘
                                       │ (Triggers on CAPTURE)
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                             Ledger Aggregate                                │
│  - LedgerTransaction (Root: balanced container)                             │
│  - LedgerEntry (Entities: immutable accounting line items)                  │
│  - Money (Value Object)                                                     │
└──────────────────────────────────────┬──────────────────────────────────────┘
                                       │ (Enforces Invariants Against)
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                             Account Aggregate                               │
│  - Account (Root: status, owner, currency, type)                            │
│  - AccountType (Enum with explicit accounting semantics)                    │
│  - MaterializedBalance (Optimization Entity, reconcilable to ledger)        │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Core Value Objects

### 2.1 Money
- **Definition**: Represents an exact monetary amount in a specific currency.
- **Attributes**:
  - `amount`: `long` (64-bit integer signed minor units, e.g. 1000 = $10.00 USD, 100 = 100 JPY).
  - `currency`: `CurrencyCode` (ISO 4217 three-letter code, e.g. `USD`, `EUR`, `GBP`).
- **Invariants**:
  - Floating-point calculations (`float`, `double`) are strictly prohibited.
  - Arithmetic operations (`add`, `subtract`, `multiply`, `allocate`) must enforce identical currencies. Attempting to add USD to EUR throws `CurrencyMismatchException`.
  - Non-negative assertions where applicable (`isPositive()`, `isZero()`, `isGreaterThanOrEqual()`).
  - Allocation/division operations must distribute remainders deterministically down to 1 minor unit without losing fractional cents.

### 2.2 CurrencyCode
- **Definition**: Standardized ISO 4217 currency representation.
- **Attributes**: `code: String` (3 uppercase alphabetic characters), `minorUnitScale: int` (e.g. 2 for USD/EUR, 0 for JPY, 3 for BHD).

---

## 3. Account Aggregate & Accounting Semantics

### 3.1 Account Entity
- **Attributes**:
  - `id`: `UUID` (Primary Key).
  - `accountNumber`: `String` (Unique human-readable account identifier).
  - `ownerId`: `UUID` (Reference to User or Platform entity).
  - `accountType`: `AccountType` (Enum: `CUSTOMER`, `MERCHANT`, `INTERNAL_SETTLEMENT`, `FEES`, `ESCROW`).
  - `currency`: `CurrencyCode`.
  - `status`: `AccountStatus` (`PENDING_VERIFICATION`, `ACTIVE`, `FROZEN`, `CLOSED`).
  - `materializedBalanceMinor`: `long` (Operational cache optimization, reconcilable to ledger entries).
  - `version`: `long` (Optimistic concurrency version token).
  - `createdAt`: `Instant`.
  - `updatedAt`: `Instant`.

### 3.2 Account-Type Accounting Semantics Matrix
The platform strictly enforces the following accounting semantics for each account type:

| Account Type | Primary Purpose | Legal / Economic Owner | Allowed Debits | Allowed Credits | May Balance Be Negative? | Who May Create? | Who May Freeze? | In Customer/Merchant Balance View? |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **`CUSTOMER`** | Operating account for individual buyers | Verified Customer (`User`) | Purchases, Transfers Out, Authorized Fees | Deposits, Inbound Transfers, Refunds Received | **NO**. Balance must strictly remain $\ge 0$. | System during Customer Registration | Admin / Operations | **YES** (Customer Balance) |
| **`MERCHANT`** | Commercial settlement account for sellers | Verified Merchant (`User`) | Payouts to Bank, Customer Refunds Issued, Platform Processing Fees | Settled Customer Purchases, Disputed Funds Won | **NO** unless explicit overdraft agreement is bound to merchant contract. Default $\ge 0$. | System during Merchant Onboarding | Admin / Operations | **YES** (Merchant Portal Balance) |
| **`INTERNAL_SETTLEMENT`** | Platform clearing account tracking receivables/payables with external payment processors (e.g. Stripe/Adyen) | Platform Treasury | Settlement funds disbursed to platform bank account, provider fees | Customer capture transactions confirmed by external provider | **YES**. Cleared as a technical asset/liability ledger clearing account. | Platform Bootstrap Migration Only | Admin (Emergency System Halt only) | **NO** (Internal Treasury / Operations only) |
| **`FEES`** | Platform revenue account accumulating gross transaction margins | Platform Corporation | Operational fee rebates, partner rev-share disbursements, tax sweeps | Platform transaction fee cut deducted from settled payments | **NO**. Normal credit balance representing accumulated earned platform income ($\ge 0$). | Platform Bootstrap Migration Only | Admin (Emergency System Halt only) | **NO** (Financial Operations only) |
| **`ESCROW`** | Segregated custody account holding contested or multi-phase funds pending fulfillment or dispute resolution | Custodian / Platform Escrow | Dispute resolution release to buyer or seller | Pre-funded purchase holds, chargeback dispute retentions | **NO**. Strictly non-negative ($\ge 0$). Must reconcile exactly with active open disputes/holds. | Platform Bootstrap Migration Only | Admin / Operations | **NO** (Segregated Holding Account) |

### 3.3 Authoritative vs Materialized Balance Contract
- **Authoritative Balance Rule**: The immutable history of posted ledger entries in `ledger_entries` is the **sole authoritative financial truth**.
- **Materialized Balance Role**: `accounts.materialized_balance_minor` is an indexed cache column to enable rapid $O(1)$ pre-authorization checks.
- **Integrity Rule**: If a race condition, system crash, or data divergence occurs, the sum of historical ledger entries supersedes `accounts.materialized_balance_minor`. The platform provides automated reconciliation routines to detect divergence and update the cache column to match ledger truth.

---

## 4. Payment Aggregate

### 4.1 Payment Entity (Aggregate Root)
- **Attributes**:
  - `id`: `UUID` (Primary Key).
  - `idempotencyKey`: `String` (Client-supplied idempotency key).
  - `idempotencyScope`: `String` (Compound scope: `actorId:operation`).
  - `payerAccountId`: `UUID` (Foreign key to `accounts`, debtor in checkout).
  - `payeeAccountId`: `UUID` (Foreign key to `accounts`, creditor merchant).
  - `amountMinor`: `long` (Payment gross amount in minor units).
  - `feeAmountMinor`: `long` (Platform fee deducted from gross amount).
  - `currency`: `CurrencyCode`.
  - `status`: `PaymentStatus` (`CREATED`, `AUTHORIZING`, `AUTHORIZED`, `CAPTURING`, `SETTLED`, `FAILED`, `DECLINED`, `EXPIRED`, `PENDING_RECONCILIATION`).
  - `providerReference`: `String` (External payment processor reference ID).
  - `failureReason`: `String` (Structured failure code / explanation if declined/failed).
  - `version`: `long` (Optimistic locking version).
  - `createdAt`: `Instant`.
  - `updatedAt`: `Instant`.
- **Invariants**:
  - Payer and Payee accounts must possess the identical currency as the payment amount.
  - Payer account cannot be equal to Payee account.
  - Amount must be strictly positive (`amountMinor > 0`). Fee must be non-negative (`feeAmountMinor >= 0`) and strictly less than `amountMinor`.
  - Double-entry ledger transaction is posted **strictly on confirmed CAPTURE / SETTLED state**.

---

## 5. Ledger Aggregate

### 5.1 LedgerTransaction Entity (Aggregate Root)
- **Attributes**:
  - `id`: `UUID` (Primary Key).
  - `transactionType`: `LedgerTransactionType` (`PAYMENT`, `REFUND`, `FEE`, `PAYOUT`, `SYSTEM_ADJUSTMENT`).
  - `sourceReferenceId`: `UUID` (Foreign key to originating entity, e.g. `payment.id` or `refund.id`).
  - `sourceReferenceType`: `String` (`PAYMENT`, `REFUND`, `RECONCILIATION_ADJUSTMENT`).
  - `description`: `String` (Human-readable accounting memo).
  - `status`: `LedgerTransactionStatus` (`PENDING`, `POSTED`, `REJECTED`).
  - `postedAt`: `Instant` (Timestamp when transaction successfully balanced and posted).
  - `createdAt`: `Instant`.
- **Invariants**:
  - **Balanced Rule**: $\sum \text{Debits} == \sum \text{Credits}$ for all child entries.
  - **Single-Currency Invariant**: All child entries must share the identical currency. Multi-currency transactions must be split via intermediate currency clearing accounts.
  - **Immutability Rule**: Once `status == POSTED`, the transaction and all associated `ledger_entries` are immutable. No updates or deletions are permitted under any circumstances.

### 5.2 LedgerEntry Entity
- **Attributes**:
  - `id`: `UUID` (Primary Key).
  - `ledgerTransactionId`: `UUID` (Foreign key to parent `ledger_transactions`).
  - `accountId`: `UUID` (Foreign key to `accounts`).
  - `direction`: `EntryDirection` (`DEBIT`, `CREDIT`).
  - `amountMinor`: `long` (Positive integer minor units, strictly $> 0$).
  - `currency`: `CurrencyCode`.
  - `sequenceNumber`: `long` (Strict monotonically increasing sequence number per account).
  - `createdAt`: `Instant`.
- **Invariants**:
  - `amountMinor` must be strictly positive.
  - Immutable accounting fact: no `running_balance` column is authoritative. Entries record what occurred, not the cached balance state.

---

## 6. Refund Aggregate

### 6.1 Refund Entity
- **Attributes**:
  - `id`: `UUID` (Primary Key).
  - `paymentId`: `UUID` (Foreign key to original settled `payments.id`).
  - `idempotencyKey`: `String`.
  - `amountMinor`: `long` (Refund amount in minor units, strictly $> 0$).
  - `currency`: `CurrencyCode`.
  - `status`: `RefundStatus` (`REQUESTED`, `PROCESSING`, `SETTLED`, `FAILED`, `PENDING_RECONCILIATION`).
  - `reason`: `String` (Customer / Merchant reason for refund).
  - `providerReference`: `String` (External processor refund reference).
  - `version`: `long`.
  - `createdAt`: `Instant`.
  - `updatedAt`: `Instant`.
- **Invariants**:
  - Original payment must be in `SETTLED` status.
  - Cumulative refund rule: $\sum \text{Refunds(paymentId)} \le \text{payment.amountMinor}$.
  - Creates a compensating `LedgerTransaction` reversing funds: Merchant debited, Customer credited, Provider settlement adjusted.

---

## 7. Supporting Entities & Infrastructure Domain

### 7.1 IdempotencyRecord Entity
- **Attributes**:
  - `id`: `UUID` (Primary Key).
  - `actorId`: `UUID` (Identity of the caller).
  - `operation`: `String` (e.g. `PAYMENT_CREATE`, `REFUND_CREATE`).
  - `idempotencyKey`: `String` (Client-supplied key).
  - `requestHash`: `String` (SHA-256 hash of normalized request body and URI).
  - `status`: `IdempotencyStatus` (`IN_PROGRESS`, `COMPLETED`, `FAILED`).
  - `responseStatusCode`: `Integer` (HTTP status code cached for replay).
  - `responseBody`: `String` (Cached JSON response payload).
  - `resourceId`: `UUID` (Created resource ID, e.g. `payment.id`).
  - `createdAt`: `Instant`.
  - `expiresAt`: `Instant` (Configurable retention TTL).
- **Invariants**:
  - Unique constraint on `(actorId, operation, idempotencyKey)`.

### 7.2 OutboxEvent Entity
- **Attributes**:
  - `id`: `UUID` (Primary Key).
  - `aggregateType`: `String` (`PAYMENT`, `REFUND`, `ACCOUNT`, `RECONCILIATION`).
  - `aggregateId`: `String` (Business entity ID).
  - `eventType`: `String` (`PaymentSettled`, `RefundSettled`, etc.).
  - `payload`: `String` (CloudEvents-inspired JSON envelope).
  - `status`: `OutboxStatus` (`PENDING`, `PUBLISHED`, `FAILED`).
  - `retryCount`: `int`.
  - `nextRetryAt`: `Instant`.
  - `publishedAt`: `Instant`.
  - `createdAt`: `Instant`.

### 7.3 Reconciliation Entities
- **`ReconciliationRun`**:
  - `id`: `UUID`, `providerName`: `String`, `windowStart`: `Instant`, `windowEnd`: `Instant`, `status`: `RunStatus` (`SCHEDULED`, `RUNNING`, `COMPLETED`, `FAILED`), `totalProcessed`: `int`, `totalMatched`: `int`, `totalDiscrepancies`: `int`, `startedAt`: `Instant`, `completedAt`: `Instant`.
- **`ReconciliationDifference`**:
  - `id`: `UUID`, `runId`: `UUID`, `paymentId`: `UUID`, `discrepancyType`: `DiscrepancyType` (`MISSING_INTERNAL`, `MISSING_PROVIDER`, `AMOUNT_MISMATCH`, `CURRENCY_MISMATCH`, `STATUS_MISMATCH`, `DUPLICATE`, `TIMING_DIFFERENCE`, `UNKNOWN`), `internalAmountMinor`: `Long`, `providerAmountMinor`: `Long`, `status`: `DifferenceStatus` (`OPEN`, `INVESTIGATING`, `RESOLVED`, `ESCALATED`), `resolutionNotes`: `String`, `compensatingTransactionId`: `UUID`.

### 7.4 AuditLog Entity
- **Attributes**:
  - `id`: `UUID`, `actorId`: `UUID`, `actorRole`: `String`, `action`: `String`, `entityType`: `String`, `entityId`: `UUID`, `oldStateJson`: `String`, `newStateJson`: `String`, `ipAddress`: `String`, `userAgent`: `String`, `timestamp`: `Instant`.
- **Invariants**: Strictly append-only. No updates or deletions allowed.

---

## 8. End-to-End Financial Traceability Chain
Every financial mutation across the entire platform lifecycle must preserve complete correlation linkage:
$$\text{requestId / correlationId} \longrightarrow \text{paymentId} \longrightarrow \text{ledgerTransactionId} \longrightarrow \text{ledgerEntry.id (Debits & Credits)} \longrightarrow \text{outboxEvent.id} \longrightarrow \text{Kafka Message (header: correlationId)} \longrightarrow \text{consumer processing trace}$$
This guarantees instantaneous bi-directional auditability between API requests, database rows, distributed Kafka partitions, and consumer logs.
