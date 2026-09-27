# System Requirements Specification

## 1. Document Overview & Purpose
This document establishes the authoritative system requirements for the **Distributed Payment & Ledger Platform**. It delineates system boundaries, actor permissions, functional capabilities, operational workflows, failure modes, explicit non-goals, and architectural assumptions. Implementation agents must adhere strictly to these specifications without inventing business rules or altering platform boundaries.

---

## 2. Actors & Permissions Boundary

The platform recognizes four distinct actors with strictly partitioned capabilities:

### 2.1 CUSTOMER
- **Role & Scope**: End-user who holds an active individual customer account to purchase goods/services or transfer funds.
- **Permitted Actions**:
  - Register an account, authenticate (obtain JWT), and manage credentials.
  - View own account details, current ledger-derived balance, and transaction history.
  - Initiate payments from their funded account to designated merchant accounts using an idempotency key.
  - Request full or partial refunds for eligible payments made by their account within permissible timeframes.
  - Receive real-time or asynchronous event notifications regarding their transactions.
- **Prohibited Actions**:
  - Cannot view, access, or modify accounts or transactions belonging to other customers or merchants (strict IDOR enforcement).
  - Cannot initiate payments from or refunds to accounts they do not own.
  - Cannot create or modify administrative, fee, or system settlement accounts.
  - Cannot force balance adjustments or override payment status.

### 2.2 MERCHANT
- **Role & Scope**: Commercial entity that receives payments for products or services and initiates customer refunds.
- **Permitted Actions**:
  - Register merchant profile, complete verification onboarding, and obtain API credentials.
  - View merchant settlement account details, accumulated balance, fee deductions, and historical ledger statements.
  - Initiate customer refunds against settled payments received by the merchant up to the captured transaction amount.
  - Register and configure webhook endpoints to receive asynchronous transaction settlement events.
  - Export settlement and reconciliation reports for specified date windows.
- **Prohibited Actions**:
  - Cannot access accounts or financial data of competing merchants or unrelated customers.
  - Cannot refund more than the net captured payment amount.
  - Cannot directly mutate account balances or execute un-balanced ledger transactions.
  - Cannot bypass platform transaction fees or provider settlement rules.

### 2.3 ADMIN / OPERATIONS
- **Role & Scope**: Privileged platform operators responsible for compliance, system health, dispute investigation, and operational maintenance.
- **Permitted Actions**:
  - Search, inspect, and audit all accounts, payments, ledger transactions, audit logs, and reconciliation runs.
  - Administratively freeze and unfreeze customer or merchant accounts in response to fraud alerts, legal holds, or compliance investigations.
  - Trigger ad-hoc reconciliation runs and inspect detected discrepancies between internal ledger records and external provider reports.
  - Post explicitly documented, dual-party balanced compensating adjustments to resolve verified reconciliation discrepancies (must specify reason, ticket reference, balanced counterparty account, and full audit metadata).
  - Query system metrics, health probes, dead-letter queues, and outbox relay performance.
- **Prohibited Actions (Strict Invariant)**:
  - **Cannot arbitrarily edit account balances**: There is NO `PUT /accounts/{id}/balance` or direct SQL mutation. Balances are derived solely from immutable double-entry entries.
  - **Cannot delete or truncate financial ledger history**: All mistakes must be corrected via compensating transactions.
  - **Cannot initiate payments on behalf of customers** without valid customer authorization and idempotency credentials.
  - **Cannot bypass authentication or audit logging**: Every administrative action is immutably logged with actor identity, timestamp, IP address, and state diff.

### 2.4 SYSTEM / SCHEDULER
- **Role & Scope**: Internal background processes running within the platform runtime.
- **Permitted Actions**:
  - Polling and dispatching pending transactional outbox events to Apache Kafka.
  - Polling and retrying unacknowledged or `PENDING_RECONCILIATION` external provider operations.
  - Ingesting external provider settlement files and executing automated daily reconciliation routines.
  - Pruning expired idempotency records beyond the active retention window.

---

## 3. System Boundaries & Core Functional Capabilities

### 3.1 Authentication & Security (`auth`)
- User registration and login issuing stateless JWT access tokens and secure, rotatable refresh tokens.
- Secure credential management using strong hashing (Argon2id or BCrypt strength 12+).
- Role-Based Access Control (RBAC) with method-level security and strict resource-level ownership validation on every endpoint.

### 3.2 Account Lifecycle (`account`)
- Supported Account Types:
  1. `CUSTOMER`: Individual operating account.
  2. `MERCHANT`: Commercial settlement account.
  3. `INTERNAL_SETTLEMENT`: System clearing account representing external payment provider obligations.
  4. `FEES`: Platform revenue account collecting transaction fees.
  5. `ESCROW`: Temporary holding account for disputed or multi-phase transactions.
- Lifecycle States: `PENDING_VERIFICATION`, `ACTIVE`, `FROZEN`, `CLOSED`.
- Controlled status transitions: Frozen accounts cannot initiate debits or receive non-compensating credits.

### 3.3 Payment Processing (`payment`)
- Client submits payment request with mandatory `Idempotency-Key` header and standardized JSON payload.
- Payment execution coordinates through the `PaymentProviderAdapter`:
  - Step 1: Pre-authorization validation (account status, idempotency uniqueness, currency validation).
  - Step 2: Provider authorization/capture invocation.
  - Step 3: Atomic database transaction: update payment state to `SETTLED`, post balanced double-entry ledger entries, and insert `PaymentSettled` outbox event.
- Resilient handling of provider timeouts: Transitions to `PENDING_RECONCILIATION` without premature marking as failed.

### 3.4 Immutable Double-Entry Ledger (`ledger`)
- Authoritative source of financial truth.
- Every transaction contains $\ge 2$ entries sharing identical currency where $\sum \text{Debits} == \sum \text{Credits}$.
- Entries are strictly append-only; `UPDATE` and `DELETE` statements are barred.
- Balances are mathematically derived from ledger entries; materialized balances in `accounts` are strictly cached optimizations verified against entry history.

### 3.5 Refunds & Reversals (`refund`)
- Supports full and partial refunds against previously `SETTLED` payments.
- Cumulative refund validation: $\sum \text{Refunds} \le \text{Captured Payment Amount}$.
- Every refund posts an explicit compensating double-entry ledger transaction reversing the initial flow of funds (Customer credited, Merchant debited, Provider settlement cleared).

### 3.6 Financial Reconciliation (`reconciliation`)
- Automated and on-demand ingestion of external payment provider settlement reports.
- Comprehensive discrepancy categorization: `MISSING_INTERNAL`, `MISSING_PROVIDER`, `AMOUNT_MISMATCH`, `CURRENCY_MISMATCH`, `STATUS_MISMATCH`, `DUPLICATE`, `TIMING_DIFFERENCE`, `UNKNOWN`.
- Discrepancies generate persistent investigation records and operational alerts without altering existing posted ledger entries.

### 3.7 Transactional Outbox & Messaging (`outbox`, `messaging`)
- Business state mutations and outbox event persistence occur within the same ACID database transaction.
- Background outbox publisher dispatches events to Apache Kafka with at-least-once delivery guarantees.
- Consumer idempotency mechanisms ensure safe retries and deduplication.

### 3.8 Audit Logging & Compliance (`admin`)
- Immutable, append-only audit trail capturing all administrative, financial status, and account freeze actions.
- Full context capture: actor ID, role, action, target entity, timestamp, IP address, and payload difference.

---

## 4. Workflows

### 4.1 Business Workflows
1. **Customer Checkout & Instant Settlement**:
   - Customer initiates checkout $\to$ API verifies idempotency $\to$ Adapter calls provider $\to$ Provider returns Success $\to$ DB transaction commits Payment `SETTLED`, creates balanced Ledger entries, and inserts Outbox event $\to$ Customer receives HTTP 201 $\to$ Outbox relay publishes `PaymentSettled` to Kafka $\to$ Notification service alerts Customer and Merchant.
2. **Merchant Initiated Refund**:
   - Merchant requests refund $\to$ API verifies ownership and refundable amount $\to$ Adapter calls provider refund endpoint $\to$ DB transaction commits Refund `SETTLED`, appends compensating Ledger entries, and inserts Outbox event $\to$ Merchant receives HTTP 200.

### 4.2 Failure & Recovery Workflows
1. **Provider Network Timeout (Ambiguous Outcome)**:
   - Application sends capture request $\to$ HTTP socket times out after 10s $\to$ Application enters `PENDING_RECONCILIATION` $\to$ Scheduled background reconciliation worker queries provider status using payment idempotency reference $\to$ Upon confirmation, worker resumes transaction to `SETTLED` (or `FAILED` if provider confirmed decline).
2. **Application Crash During Outbox Dispatch**:
   - Transaction commits in PostgreSQL $\to$ Outbox row marked `PENDING` $\to$ Application crashes before Kafka publish $\to$ Upon restart, Outbox worker reads un-dispatched rows via `SELECT ... FOR UPDATE SKIP LOCKED` and publishes to Kafka.

### 4.3 Operational & Administrative Workflows
1. **Suspicious Activity Account Freeze**:
   - Security operator identifies fraud pattern $\to$ Operator issues `POST /api/v1/admin/accounts/{id}/freeze` with mandatory justification $\to$ Account transitions to `FROZEN` $\to$ Append-only audit log recorded $\to$ Any in-flight debit attempts are rejected immediately with `422 Unprocessable Entity`.
2. **Reconciliation Discrepancy Resolution**:
   - Daily reconciliation run identifies `AMOUNT_MISMATCH` due to unforeseen provider fee variation $\to$ Discrepancy logged as `OPEN` $\to$ Operator reviews evidence and issues authorized compensating adjustment transaction crediting/debiting fee expense account $\to$ Discrepancy marked `RESOLVED`.

---

## 5. Explicit Non-Goals
The following capabilities are deliberately outside the scope of this platform:
1. **Raw Card Data Storage (PAN / CVV)**: The platform does not store or process raw Primary Account Numbers or security codes, eliminating heavy PCI-DSS Level 1 cardholder data environment requirements. Tokenization is delegated to external payment providers.
2. **Arbitrary Balance Modification**: No endpoint, tool, or database script allows setting an account balance directly without balanced double-entry accounting records.
3. **Destructive History Truncation**: No deletion of historical accounts, payments, or ledger transactions to "clean up" errors.
4. **Microservice Decomposition**: The platform will not be decomposed into independent microservices, Kubernetes clusters, or distributed service meshes during this phase.
5. **Multi-Currency Real-Time FX Trading**: Multi-currency conversion via real-time market exchange engines is excluded. Every ledger transaction is strictly single-currency. Multi-currency settlements require dedicated intermediary clearing accounts.
6. **Dual Approval (Four-Eyes Principle)**: Dual administrative approval workflows for adjustments are an explicit non-goal for this baseline phase, though all administrative actions are strictly audited and restricted.

---

## 6. Assumptions & External Dependencies
- **PostgreSQL 16+**: Authoritative financial data store providing ACID transaction guarantees and serializable/repeatable read isolation levels where needed.
- **Apache Kafka 3.x**: Distributed commit log providing partitioned, durable transport for asynchronous domain events.
- **Redis 7.x**: In-memory data store utilized exclusively as an auxiliary cache, token bucket rate-limiter, and fast idempotency pre-filter.
- **External Payment Provider**: Third-party payment gateway supplying payment authorization, capture, and settlement reporting via HTTPS/TLS 1.3.
- **Data Durability & Retention Policy**:
  - *Financial Integrity Requirement*: No committed financial transaction may be silently lost or duplicated.
  - *Infrastructure RPO*: Target RPO is TBD based on the selected PostgreSQL durability, backup, replication, and disaster-recovery architecture.
  - *Data Retention*: Production retention periods must be determined according to applicable jurisdiction, regulatory requirements, contractual requirements, business policy, and provider requirements. The platform engineering specification supports configurable retention rather than hardcoded universal limits.
