CREATE TABLE ledger_transactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_type VARCHAR(50) NOT NULL,
    source_reference_id UUID NOT NULL,
    source_reference_type VARCHAR(50) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    description VARCHAR(500) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    posted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_ledger_transactions_type CHECK (transaction_type IN ('PAYMENT', 'REFUND', 'FEE', 'PAYOUT', 'SYSTEM_ADJUSTMENT')),
    CONSTRAINT ck_ledger_transactions_status CHECK (status IN ('PENDING', 'POSTED', 'REJECTED'))
);

CREATE UNIQUE INDEX uq_ledger_tx_source ON ledger_transactions(source_reference_type, source_reference_id);
CREATE INDEX idx_ledger_tx_created ON ledger_transactions(created_at DESC);

CREATE TABLE ledger_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ledger_transaction_id UUID NOT NULL,
    account_id UUID NOT NULL,
    direction VARCHAR(10) NOT NULL,
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
