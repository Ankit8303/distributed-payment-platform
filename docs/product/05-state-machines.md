# State Machines Specification

## 1. Principles of Platform State Machines
All financial lifecycle transitions are governed by strict, deterministic finite-state machines. State transitions are non-reentrant, fully auditable, and executed within transactional boundaries.

Rules:
- Forbidden transitions must raise an explicit domain exception (`InvalidStateTransitionException`) and abort immediately.
- Terminal states are immutable: no transition can occur out of a terminal state.
- External ambiguity (network timeout, lost connection) must NEVER default to a terminal failure (`FAILED`). Ambiguity enters `PENDING_RECONCILIATION` awaiting deterministic verification.

---

## 2. Payment State Machine

### 2.1 States
- `CREATED`: Initial payment entity persisted; validations passed.
- `AUTHORIZING`: Request dispatched to external payment gateway for authorization.
- `AUTHORIZED`: External gateway confirmed authorization (funds reserved at issuer).
- `CAPTURING`: Settlement capture request dispatched to external gateway.
- `SETTLED`: **(Terminal Success)** Payment funds captured; double-entry ledger posted; outbox event emitted.
- `DECLINED`: **(Terminal Failure)** External gateway explicitly declined authorization (e.g. invalid card, fraud rule).
- `FAILED`: **(Terminal Failure)** Unrecoverable internal failure or provider rejection prior to ledger posting.
- `EXPIRED`: **(Terminal Failure)** Authorization reservation expired without capture.
- `PENDING_RECONCILIATION`: **(Indeterminate State)** Provider capture call timed out or socket dropped; external state unknown.

### 2.2 Payment State Transition Diagram
```
                     ┌───────────────┐
                     │    CREATED    │
                     └───────┬───────┘
                             │ dispatch authorize
                             ▼
                     ┌───────────────┐
                     │  AUTHORIZING  │
                     └───────┬───────┘
          provider decline   │   provider auth success
         ┌───────────────────┴───────────────────┐
         ▼                                       ▼
  ┌──────────────┐                       ┌───────────────┐
  │   DECLINED   │ (Terminal)            │  AUTHORIZED   │
  └──────────────┘                       └───────┬───────┘
                                                 │ dispatch capture
                                                 ▼
                                         ┌───────────────┐
                                         │   CAPTURING   │
                                         └───────┬───────┘
                          network timeout        │   provider capture success
        ┌────────────────────────────────────────┼────────────────────────────────────────┐
        ▼                                        ▼                                        ▼
┌────────────────────────┐              ┌────────────────┐                       ┌────────────────┐
│ PENDING_RECONCILIATION │              │     FAILED     │ (Terminal)            │    SETTLED     │ (Terminal)
└───────────┬────────────┘              └────────────────┘                       └────────────────┘
            │
            ├─ active status query / webhook confirms capture ───────────► SETTLED
            └─ active status query / webhook confirms void or decline ──► FAILED
```

### 2.3 Detailed Transition Matrix

| Current State | Target State | Triggering Action | Actor | Pre-Conditions / Validations | Side Effects & Invariants | Emitted Event |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| *None* | `CREATED` | Initiate Payment API | `CUSTOMER` | Valid accounts, positive amount, active accounts | Idempotency record created in `IN_PROGRESS` | `PaymentCreated` |
| `CREATED` | `AUTHORIZING` | Gateway Dispatch | `SYSTEM` | Payment in `CREATED` status | Generates provider payload with correlation ID | - |
| `AUTHORIZING` | `AUTHORIZED` | Provider Auth Success | `SYSTEM` / Webhook | Provider returns authorization token | Stores `providerReference` | `PaymentAuthorized` |
| `AUTHORIZING` | `DECLINED` | Provider Auth Decline | `SYSTEM` / Webhook | Provider returns decline code | Idempotency marked `FAILED`; terminal state | `PaymentDeclined` |
| `AUTHORIZED` | `CAPTURING` | Capture Command | `SYSTEM` | Payment in `AUTHORIZED` status | Dispatches capture request to provider | - |
| `CAPTURING` | `SETTLED` | Provider Capture Success | `SYSTEM` / Webhook | Provider returns capture confirmation | **POSTS DOUBLE-ENTRY LEDGER**; updates balances; marks idempotency `COMPLETED` | `PaymentSettled` |
| `CAPTURING` | `PENDING_RECONCILIATION` | Network Socket Timeout / 504 Gateway Timeout | `SYSTEM` | HTTP timeout on capture call; no response | **DO NOT POST LEDGER**; schedule immediate status lookup worker | `PaymentPendingReconciliation` |
| `PENDING_RECONCILIATION` | `SETTLED` | Status Query / Webhook Confirms Capture | `SYSTEM` / Webhook | Verified external capture proof | **POSTS DOUBLE-ENTRY LEDGER**; marks idempotency `COMPLETED` | `PaymentSettled` |
| `PENDING_RECONCILIATION` | `FAILED` | Status Query / Webhook Confirms Decline | `SYSTEM` / Webhook | Verified external non-existence or decline | Marks idempotency `FAILED`; no ledger posting | `PaymentFailed` |
| `AUTHORIZED` | `EXPIRED` | Auth TTL Expiry | `SYSTEM / SCHEDULER` | Capture not called within 7 days | Releases hold; terminal state | `PaymentExpired` |

### 2.4 Forbidden Transitions
- `SETTLED` $\to$ `FAILED`, `DECLINED`, `CREATED` (Violates ledger immutability).
- `DECLINED` $\to$ `SETTLED`, `AUTHORIZED` (Terminal failure cannot be revived).
- `FAILED` $\to$ `SETTLED` (Terminal failure cannot be revived).
- `CAPTURING` $\to$ `FAILED` directly on socket timeout (Violates non-failure on ambiguity rule).

---

## 3. Refund State Machine

### 3.1 States
- `REQUESTED`: Refund requested; initial eligibility check passed.
- `PROCESSING`: Refund request dispatched to external payment gateway.
- `SETTLED`: **(Terminal Success)** Provider confirmed refund; compensating ledger posted; outbox event emitted.
- `FAILED`: **(Terminal Failure)** Provider rejected refund or merchant balance insufficient.
- `PENDING_RECONCILIATION`: **(Indeterminate State)** Provider timeout during refund dispatch.

### 3.2 Detailed Transition Matrix

| Current State | Target State | Triggering Action | Actor | Pre-Conditions / Validations | Side Effects & Invariants | Emitted Event |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| *None* | `REQUESTED` | Issue Refund API | `MERCHANT` | Original payment `SETTLED`; $\sum \text{Refunds} + \text{amount} \le \text{payment.amount}$ | Idempotency record `IN_PROGRESS`; row lock on `Payment` | `RefundRequested` |
| `REQUESTED` | `PROCESSING` | Gateway Dispatch | `SYSTEM` | Refund in `REQUESTED` status | Maps internal refund ID to provider reference | - |
| `PROCESSING` | `SETTLED` | Provider Refund Success | `SYSTEM` / Webhook | Provider confirms refund execution | **POSTS COMPENSATING LEDGER TRANSACTION**; adjusts balances; idempotency `COMPLETED` | `RefundSettled` |
| `PROCESSING` | `FAILED` | Provider Decline | `SYSTEM` / Webhook | Provider explicitly rejects refund | Idempotency marked `FAILED`; no ledger entries | `RefundFailed` |
| `PROCESSING` | `PENDING_RECONCILIATION` | Network Socket Timeout | `SYSTEM` | No HTTP response from provider gateway | Schedules status query; no ledger entries | `RefundPendingReconciliation` |
| `PENDING_RECONCILIATION` | `SETTLED` | Status Query Confirms Refund | `SYSTEM` | External confirmation verified | Posts compensating ledger transaction | `RefundSettled` |
| `PENDING_RECONCILIATION` | `FAILED` | Status Query Confirms Failure | `SYSTEM` | External confirmation of non-execution | Marks refund `FAILED`; no ledger entries | `RefundFailed` |

---

## 4. Account Lifecycle State Machine

### 4.1 States
- `PENDING_VERIFICATION`: Account created; awaiting KYC / verification check.
- `ACTIVE`: Fully operational; permitted to send/receive funds.
- `FROZEN`: Administratively restricted; all debit attempts and non-compensating credits blocked.
- `CLOSED`: **(Terminal)** Account permanently deactivated; balance must be exactly 0.

### 4.2 Detailed Transition Matrix

| Current State | Target State | Triggering Action | Actor | Pre-Conditions / Validations | Side Effects & Invariants | Emitted Event |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| *None* | `PENDING_VERIFICATION` | User Registration | `CUSTOMER` / `MERCHANT` | Valid user entity created | Default operational account created | `AccountCreated` |
| `PENDING_VERIFICATION` | `ACTIVE` | Verification Approval | `SYSTEM` / `ADMIN` | Identity checks passed | Account activated for financial operations | `AccountActivated` |
| `ACTIVE` | `FROZEN` | Freeze Account API | `ADMIN / OPERATIONS` | Mandatory audit reason supplied | Debits blocked; append-only audit log created | `AccountFrozen` |
| `FROZEN` | `ACTIVE` | Unfreeze Account API | `ADMIN / OPERATIONS` | Mandatory resolution notes supplied | Debits restored; append-only audit log created | `AccountUnfrozen` |
| `ACTIVE` / `FROZEN` | `CLOSED` | Close Account API | `ADMIN` / `CUSTOMER` | **Current balance must be exactly 0** | Account terminated; no future transactions allowed | `AccountClosed` |

---

## 5. Reconciliation Run State Machine

### 5.1 States
- `SCHEDULED`: Reconciliation job queued for execution.
- `RUNNING`: Actively parsing provider file, comparing ledger records, and detecting discrepancies.
- `COMPLETED`: **(Terminal Success)** Run finished; discrepancies categorized and recorded.
- `FAILED`: **(Terminal Failure)** Run aborted due to unreadable file format or database failure.

### 5.2 Discrepancy Status Lifecycle
- `OPEN` $\to$ `INVESTIGATING` $\to$ `RESOLVED` (via balanced compensating transaction) or `ESCALATED` (legal / fraud review).
- Critical Rule: Discrepancy resolution NEVER mutates or deletes posted ledger entries.
