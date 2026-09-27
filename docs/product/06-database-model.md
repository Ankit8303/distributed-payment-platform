# Database Model Specification

## 1. Relational Architecture & Database Engine
- **Target Engine**: PostgreSQL 16+
- **Primary Schema Principle**: PostgreSQL is the single, authoritative financial source of truth.
- **Data Integrity Layer**: Financial consistency is guaranteed via declarative database constraints, foreign keys with strict referential integrity (`ON DELETE RESTRICT`), unique indexes, check constraints, and row-level immutability triggers.

---

## 2. Table Specifications

### 2.1 `users`
Stores authenticated platform identities and system actors.
```sql
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(50) NOT NULL, -- 'CUSTOMER', 'MERCHANT', 'ADMIN', 'SYSTEM'
    status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE', -- 'ACTIVE', 'SUSPENDED', 'DEACTIVATED'
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT ck_users_role CHECK (role IN ('CUSTOMER', 'MERCHANT', 'ADMIN', 'SYSTEM')),
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DEACTIVATED'))
);
```

### 2.2 `accounts`
Stores operational and system financial accounts.
```sql
CREATE TABLE accounts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_number VARCHAR(64) NOT NULL,
    owner_id UUID NOT NULL,
    account_type VARCHAR(50) NOT NULL, -- 'CUSTOMER', 'MERCHANT', 'INTERNAL_SETTLEMENT', 'FEES', 'ESCROW'
    currency VARCHAR(3) NOT NULL, -- ISO 4217 (e.g. 'USD', 'EUR')
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING_VERIFICATION', -- 'PENDING_VERIFICATION', 'ACTIVE', 'FROZEN', 'CLOSED'
    materialized_balance_minor BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_accounts_account_number UNIQUE (account_number),
    CONSTRAINT fk_accounts_owner FOREIGN KEY (owner_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT ck_accounts_currency CHECK (LENGTH(currency) = 3),
    CONSTRAINT ck_accounts_type CHECK (account_type IN ('CUSTOMER', 'MERCHANT', 'INTERNAL_SETTLEMENT', 'FEES', 'ESCROW')),
    CONSTRAINT ck_accounts_status CHECK (status IN ('PENDING_VERIFICATION', 'ACTIVE', 'FROZEN', 'CLOSED')),
    -- Customer operating accounts must strictly remain non-negative
    CONSTRAINT ck_accounts_customer_balance CHECK (account_type != 'CUSTOMER' OR materialized_balance_minor >= 0)
);

CREATE INDEX idx_accounts_owner_id ON accounts(owner_id);
CREATE INDEX idx_accounts_type_status ON accounts(account_type, status);
```

### 2.3 `payments`
Tracks payment authorization, capture lifecycle, and external references.
```sql
CREATE TABLE payments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key VARCHAR(255) NOT NULL,
    idempotency_scope VARCHAR(255) NOT NULL,
    payer_account_id UUID NOT NULL,
    payee_account_id UUID NOT NULL,
    amount_minor BIGINT NOT NULL,
    fee_amount_minor BIGINT NOT NULL DEFAULT 0,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(50) NOT NULL, -- 'CREATED', 'AUTHORIZING', 'AUTHORIZED', 'CAPTURING', 'SETTLED', 'DECLINED', 'FAILED', 'EXPIRED', 'PENDING_RECONCILIATION'
    provider_reference VARCHAR(255),
    failure_reason VARCHAR(500),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_payments_payer FOREIGN KEY (payer_account_id) REFERENCES accounts(id) ON DELETE RESTRICT,
    CONSTRAINT fk_payments_payee FOREIGN KEY (payee_account_id) REFERENCES accounts(id) ON DELETE RESTRICT,
    CONSTRAINT ck_payments_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT ck_payments_fee_non_negative CHECK (fee_amount_minor >= 0 AND fee_amount_minor < amount_minor),
    CONSTRAINT ck_payments_distinct_accounts CHECK (payer_account_id != payee_account_id),
    CONSTRAINT ck_payments_status CHECK (status IN (
        'CREATED', 'AUTHORIZING', 'AUTHORIZED', 'CAPTURING', 'SETTLED',
        'DECLINED', 'FAILED', 'EXPIRED', 'PENDING_RECONCILIATION'
    ))
);

CREATE INDEX idx_payments_payer_created ON payments(payer_account_id, created_at DESC);
CREATE INDEX idx_payments_payee_created ON payments(payee_account_id, created_at DESC);
CREATE INDEX idx_payments_provider_ref ON payments(provider_reference) WHERE provider_reference IS NOT NULL;
CREATE INDEX idx_payments_pending_reconciliation ON payments(status) WHERE status = 'PENDING_RECONCILIATION';
```

### 2.4 `ledger_transactions`
Root container for balanced double-entry financial events. Immutable once posted.
```sql
CREATE TABLE ledger_transactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_type VARCHAR(50) NOT NULL, -- 'PAYMENT', 'REFUND', 'FEE', 'PAYOUT', 'SYSTEM_ADJUSTMENT'
    source_reference_id UUID NOT NULL,
    source_reference_type VARCHAR(50) NOT NULL, -- 'PAYMENT', 'REFUND', 'RECONCILIATION_DIFFERENCE'
    currency VARCHAR(3) NOT NULL,
    description VARCHAR(500) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING', -- 'PENDING', 'POSTED', 'REJECTED'
    posted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_ledger_transactions_type CHECK (transaction_type IN ('PAYMENT', 'REFUND', 'FEE', 'PAYOUT', 'SYSTEM_ADJUSTMENT')),
    CONSTRAINT ck_ledger_transactions_status CHECK (status IN ('PENDING', 'POSTED', 'REJECTED'))
);

CREATE INDEX idx_ledger_tx_source ON ledger_transactions(source_reference_type, source_reference_id);
CREATE INDEX idx_ledger_tx_created ON ledger_transactions(created_at DESC);
```

### 2.5 `ledger_entries`
Immutable individual accounting line items representing debits and credits.
```sql
CREATE TABLE ledger_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ledger_transaction_id UUID NOT NULL,
    account_id UUID NOT NULL,
    direction VARCHAR(10) NOT NULL, -- 'DEBIT', 'CREDIT'
    amount_minor BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    sequence_number BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_ledger_entries_tx FOREIGN KEY (ledger_transaction_id) REFERENCES ledger_transactions(id) ON DELETE RESTRICT,
    CONSTRAINT fk_ledger_entries_account FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE RESTRICT,
    CONSTRAINT ck_ledger_entries_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_ledger_entries_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT uq_ledger_entries_account_seq UNIQUE (account_id, sequence_number)
);

CREATE INDEX idx_ledger_entries_account_created ON ledger_entries(account_id, created_at DESC);
CREATE INDEX idx_ledger_entries_tx_id ON ledger_entries(ledger_transaction_id);
```

### 2.6 `refunds`
Tracks full and partial compensating reversals.
```sql
CREATE TABLE refunds (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id UUID NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(50) NOT NULL, -- 'REQUESTED', 'PROCESSING', 'SETTLED', 'FAILED', 'PENDING_RECONCILIATION'
    reason VARCHAR(500) NOT NULL,
    provider_reference VARCHAR(255),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_refunds_payment FOREIGN KEY (payment_id) REFERENCES payments(id) ON DELETE RESTRICT,
    CONSTRAINT ck_refunds_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT ck_refunds_status CHECK (status IN ('REQUESTED', 'PROCESSING', 'SETTLED', 'FAILED', 'PENDING_RECONCILIATION'))
);

CREATE INDEX idx_refunds_payment_id ON refunds(payment_id);
CREATE INDEX idx_refunds_provider_ref ON refunds(provider_reference) WHERE provider_reference IS NOT NULL;
```

### 2.7 `idempotency_records`
Durable database gate for deterministic request handling.
```sql
CREATE TABLE idempotency_records (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id UUID NOT NULL,
    operation VARCHAR(100) NOT NULL, -- e.g. 'PAYMENT_CREATE', 'REFUND_CREATE'
    idempotency_key VARCHAR(255) NOT NULL,
    request_hash VARCHAR(64) NOT NULL, -- SHA-256 of normalized request
    status VARCHAR(50) NOT NULL, -- 'IN_PROGRESS', 'COMPLETED', 'FAILED'
    response_status_code INT,
    response_body TEXT,
    resource_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_idempotency_scope UNIQUE (actor_id, operation, idempotency_key),
    CONSTRAINT ck_idempotency_status CHECK (status IN ('IN_PROGRESS', 'COMPLETED', 'FAILED'))
);

CREATE INDEX idx_idempotency_lookup ON idempotency_records(actor_id, operation, idempotency_key);
CREATE INDEX idx_idempotency_expires_at ON idempotency_records(expires_at);
```

### 2.8 `outbox_events`
Transactional outbox ensuring zero dual-write inconsistency between database and Kafka.
```sql
CREATE TABLE outbox_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id VARCHAR(255) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING', -- 'PENDING', 'PUBLISHED', 'FAILED'
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED'))
);

-- Partial index for high-performance outbox polling with SKIP LOCKED
CREATE INDEX idx_outbox_pending_polling ON outbox_events(created_at ASC) 
WHERE status = 'PENDING';
```

### 2.9 `reconciliation_runs` & `reconciliation_differences`
```sql
CREATE TABLE reconciliation_runs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_name VARCHAR(100) NOT NULL,
    window_start TIMESTAMPTZ NOT NULL,
    window_end TIMESTAMPTZ NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'SCHEDULED', -- 'SCHEDULED', 'RUNNING', 'COMPLETED', 'FAILED'
    total_processed INT NOT NULL DEFAULT 0,
    total_matched INT NOT NULL DEFAULT 0,
    total_discrepancies INT NOT NULL DEFAULT 0,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_reconciliation_status CHECK (status IN ('SCHEDULED', 'RUNNING', 'COMPLETED', 'FAILED'))
);

CREATE TABLE reconciliation_differences (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id UUID NOT NULL,
    payment_id UUID,
    discrepancy_type VARCHAR(50) NOT NULL, -- 'MISSING_INTERNAL', 'MISSING_PROVIDER', 'AMOUNT_MISMATCH', 'CURRENCY_MISMATCH', 'STATUS_MISMATCH', 'DUPLICATE', 'TIMING_DIFFERENCE', 'UNKNOWN'
    internal_amount_minor BIGINT,
    provider_amount_minor BIGINT,
    internal_status VARCHAR(50),
    provider_status VARCHAR(50),
    status VARCHAR(50) NOT NULL DEFAULT 'OPEN', -- 'OPEN', 'INVESTIGATING', 'RESOLVED', 'ESCALATED'
    resolution_notes TEXT,
    compensating_transaction_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_diff_run FOREIGN KEY (run_id) REFERENCES reconciliation_runs(id) ON DELETE RESTRICT,
    CONSTRAINT fk_diff_payment FOREIGN KEY (payment_id) REFERENCES payments(id) ON DELETE RESTRICT,
    CONSTRAINT fk_diff_comp_tx FOREIGN KEY (compensating_transaction_id) REFERENCES ledger_transactions(id) ON DELETE RESTRICT,
    CONSTRAINT ck_diff_type CHECK (discrepancy_type IN (
        'MISSING_INTERNAL', 'MISSING_PROVIDER', 'AMOUNT_MISMATCH', 'CURRENCY_MISMATCH',
        'STATUS_MISMATCH', 'DUPLICATE', 'TIMING_DIFFERENCE', 'UNKNOWN'
    )),
    CONSTRAINT ck_diff_status CHECK (status IN ('OPEN', 'INVESTIGATING', 'RESOLVED', 'ESCALATED'))
);

CREATE INDEX idx_diff_run_status ON reconciliation_differences(run_id, status);
```

### 2.10 `audit_logs`
Append-only tamper-evident audit record for administrative and sensitive lifecycle changes.
```sql
CREATE TABLE audit_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id UUID NOT NULL,
    actor_role VARCHAR(50) NOT NULL,
    action VARCHAR(100) NOT NULL,
    entity_type VARCHAR(100) NOT NULL,
    entity_id UUID NOT NULL,
    old_state JSONB,
    new_state JSONB,
    ip_address VARCHAR(45) NOT NULL,
    user_agent VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_entity ON audit_logs(entity_type, entity_id);
CREATE INDEX idx_audit_actor ON audit_logs(actor_id, created_at DESC);
```

---

## 3. Database Triggers for Immutable Financial History
To enforce **Invariant 2** at the engine level, PostgreSQL triggers block any SQL `UPDATE` or `DELETE` on posted entries:
```sql
CREATE OR REPLACE FUNCTION prevent_posted_ledger_mutation()
RETURNS TRIGGER AS $$
BEGIN
    IF (TG_OP = 'DELETE') THEN
        RAISE EXCEPTION 'CANNOT_MODIFY_POSTED_LEDGER: Deleting ledger records is strictly forbidden.'
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF (TG_OP = 'UPDATE') THEN
        RAISE EXCEPTION 'CANNOT_MODIFY_POSTED_LEDGER: Updating posted ledger records is strictly forbidden.'
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_immutable_ledger_entries
BEFORE UPDATE OR DELETE ON ledger_entries
FOR EACH ROW EXECUTE FUNCTION prevent_posted_ledger_mutation();

CREATE TRIGGER trg_immutable_audit_logs
BEFORE UPDATE OR DELETE ON audit_logs
FOR EACH ROW EXECUTE FUNCTION prevent_posted_ledger_mutation();
```

---

## 4. Flyway Migration Strategy
- Migrations are versioned sequentially: `V1__init_schema.sql`, `V2__add_indexes.sql`, etc.
- Migration files are stored under `src/main/resources/db/migration/`.
- Every migration must be repeatable and backward-compatible. Destructive operations (`DROP TABLE`, `ALTER TABLE ... DROP COLUMN`) are strictly forbidden without an approved ADR.
