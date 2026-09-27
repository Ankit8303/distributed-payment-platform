CREATE TABLE refunds (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id UUID NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(50) NOT NULL,
    reason VARCHAR(500),
    provider_reference VARCHAR(255),
    compensating_ledger_transaction_id UUID,
    failure_reason VARCHAR(500),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_refunds_payment FOREIGN KEY (payment_id) REFERENCES payments(id) ON DELETE RESTRICT,
    CONSTRAINT fk_refunds_ledger_tx FOREIGN KEY (compensating_ledger_transaction_id) REFERENCES ledger_transactions(id) ON DELETE RESTRICT,
    CONSTRAINT ck_refunds_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT ck_refunds_status CHECK (status IN ('REQUESTED', 'PROCESSING', 'SETTLED', 'FAILED', 'PENDING_RECONCILIATION'))
);

CREATE INDEX idx_refunds_payment_id ON refunds(payment_id, created_at DESC);
CREATE INDEX idx_refunds_provider_ref ON refunds(provider_reference) WHERE provider_reference IS NOT NULL;
CREATE INDEX idx_refunds_status ON refunds(status);

CREATE TABLE reversals (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id UUID NOT NULL UNIQUE,
    amount_minor BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(50) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    compensating_ledger_transaction_id UUID,
    failure_reason VARCHAR(500),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_reversals_payment FOREIGN KEY (payment_id) REFERENCES payments(id) ON DELETE RESTRICT,
    CONSTRAINT fk_reversals_ledger_tx FOREIGN KEY (compensating_ledger_transaction_id) REFERENCES ledger_transactions(id) ON DELETE RESTRICT,
    CONSTRAINT ck_reversals_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT ck_reversals_status CHECK (status IN ('COMPLETED', 'FAILED', 'PENDING_RECONCILIATION'))
);

CREATE INDEX idx_reversals_payment_id ON reversals(payment_id);

CREATE TABLE payouts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id UUID NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(50) NOT NULL,
    provider_reference VARCHAR(255),
    compensating_ledger_transaction_id UUID,
    failure_reason VARCHAR(500),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_payouts_account FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE RESTRICT,
    CONSTRAINT fk_payouts_ledger_tx FOREIGN KEY (compensating_ledger_transaction_id) REFERENCES ledger_transactions(id) ON DELETE RESTRICT,
    CONSTRAINT ck_payouts_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT ck_payouts_status CHECK (status IN ('REQUESTED', 'PROCESSING', 'SETTLED', 'FAILED', 'PENDING_RECONCILIATION'))
);

CREATE INDEX idx_payouts_account_id ON payouts(account_id, created_at DESC);

CREATE TABLE financial_adjustments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_account_id UUID NOT NULL,
    target_account_id UUID NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    operator_id UUID NOT NULL,
    compensating_ledger_transaction_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_adjustments_source FOREIGN KEY (source_account_id) REFERENCES accounts(id) ON DELETE RESTRICT,
    CONSTRAINT fk_adjustments_target FOREIGN KEY (target_account_id) REFERENCES accounts(id) ON DELETE RESTRICT,
    CONSTRAINT fk_adjustments_operator FOREIGN KEY (operator_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_adjustments_ledger_tx FOREIGN KEY (compensating_ledger_transaction_id) REFERENCES ledger_transactions(id) ON DELETE RESTRICT,
    CONSTRAINT ck_adjustments_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT ck_adjustments_distinct_accounts CHECK (source_account_id != target_account_id)
);

CREATE INDEX idx_adjustments_operator ON financial_adjustments(operator_id, created_at DESC);
