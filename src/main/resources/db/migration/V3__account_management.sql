-- Phase 4: Account Management Domain Schema
CREATE TABLE accounts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_number VARCHAR(64) NOT NULL,
    owner_id UUID NOT NULL,
    account_type VARCHAR(50) NOT NULL, -- 'CUSTOMER', 'MERCHANT', 'INTERNAL_SETTLEMENT', 'FEES', 'ESCROW'
    currency VARCHAR(3) NOT NULL, -- ISO 4217
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
