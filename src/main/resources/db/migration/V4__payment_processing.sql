CREATE TABLE idempotency_records (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id UUID NOT NULL,
    operation VARCHAR(100) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    status VARCHAR(50) NOT NULL,
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

CREATE TABLE payments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key VARCHAR(255) NOT NULL,
    idempotency_scope VARCHAR(255) NOT NULL,
    payer_account_id UUID NOT NULL,
    payee_account_id UUID NOT NULL,
    amount_minor BIGINT NOT NULL,
    fee_amount_minor BIGINT NOT NULL DEFAULT 0,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(50) NOT NULL,
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
