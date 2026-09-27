-- =============================================================================
-- Migration: V13__p2_database_integrity_hardening.sql
-- Phase: P2 — Database & Flyway Integrity Hardening
-- Description:
--   Remediation of verified P2 defects:
--
--   FINDING-P2-001 (CRITICAL): Add DB-level trigger to prevent JPA cascade deletion
--     of ledger_transactions (CascadeType.ALL in LedgerTransactionEntity allows
--     ledger_entries to be deleted through the JPA session, bypassing the
--     trg_immutable_ledger_entries trigger which only fires on direct SQL DELETE).
--     The existing trigger protects ledger_entries, but not ledger_transactions
--     themselves. This migration adds immutability protection at the
--     ledger_transactions level too.
--
--   FINDING-P2-002 (HIGH): Replace ON DELETE CASCADE on reconciliation_attempts
--     with ON DELETE RESTRICT to preserve audit trail integrity.
--     Financial audit records must never be silently deleted.
--
--   FINDING-P2-005 (MODERATE): Add FK on admin_audit_logs.actor_user_id → users(id)
--     with ON DELETE RESTRICT to prevent orphaned audit records.
--
--   FINDING-P2-008 (MODERATE): Add unique index on payouts(account_id, idempotency_key)
--     for DB-level idempotency enforcement (idempotency_key column added).
--     Note: payouts table does not have an idempotency_key column — we add one
--     as nullable (to remain backward-compatible) and create a partial unique index
--     for non-null keys. This ensures the service-level idempotency has DB backup.
--
-- Invariants preserved:
--   - No financial history is modified.
--   - No existing data is deleted.
--   - All changes are additive constraint hardening only.
--   - Flyway migration is deterministic and idempotent (IF NOT EXISTS used where applicable).
-- =============================================================================

-- -------------------------------------------------------------------------
-- FINDING-P2-001: Protect ledger_transactions from deletion (CRITICAL)
-- -------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION prevent_ledger_transaction_deletion()
RETURNS TRIGGER AS $$
BEGIN
    IF (TG_OP = 'DELETE') THEN
        RAISE EXCEPTION 'CANNOT_DELETE_LEDGER_TRANSACTION: Deleting ledger transactions is strictly forbidden.'
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_immutable_ledger_transactions
BEFORE DELETE ON ledger_transactions
FOR EACH ROW EXECUTE FUNCTION prevent_ledger_transaction_deletion();

-- -------------------------------------------------------------------------
-- FINDING-P2-002: Protect reconciliation audit trail from cascade deletion (HIGH)
-- Reconciliation attempts record the forensic audit trail of every reconciliation
-- action. They must not be automatically deleted when a case is closed/deleted.
-- Replace ON DELETE CASCADE → ON DELETE RESTRICT on reconciliation_attempts.
-- -------------------------------------------------------------------------
ALTER TABLE reconciliation_attempts
    DROP CONSTRAINT IF EXISTS reconciliation_attempts_reconciliation_case_id_fkey;

ALTER TABLE reconciliation_attempts
    ADD CONSTRAINT fk_reconciliation_attempts_case
    FOREIGN KEY (reconciliation_case_id)
    REFERENCES reconciliation_cases(id)
    ON DELETE RESTRICT;

-- -------------------------------------------------------------------------
-- FINDING-P2-005: Enforce referential integrity on admin_audit_logs (MODERATE)
-- The actor_user_id field was not FK-constrained. Operators can be deleted
-- while their audit records still reference them. This enforces ON DELETE RESTRICT.
-- -------------------------------------------------------------------------
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE constraint_name = 'fk_admin_audit_logs_actor'
          AND table_name = 'admin_audit_logs'
    ) THEN
        ALTER TABLE admin_audit_logs
            ADD CONSTRAINT fk_admin_audit_logs_actor
            FOREIGN KEY (actor_user_id)
            REFERENCES users(id)
            ON DELETE RESTRICT;
    END IF;
END;
$$;

-- -------------------------------------------------------------------------
-- FINDING-P2-008: Add DB-level idempotency column to payouts (MODERATE)
-- The payouts table has no idempotency_key column. Service-level idempotency
-- via idempotency_records is the primary gate, but adding a column and partial
-- unique index provides defence-in-depth at the DB layer.
-- Column is nullable for backward compatibility with existing payout rows.
-- -------------------------------------------------------------------------
ALTER TABLE payouts ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(255);

CREATE UNIQUE INDEX IF NOT EXISTS uq_payouts_account_idempotency_key
    ON payouts(account_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- -------------------------------------------------------------------------
-- Additional hardening: Index for payout status queries (performance)
-- -------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_payouts_status
    ON payouts(status);

CREATE INDEX IF NOT EXISTS idx_payouts_pending_reconciliation
    ON payouts(status)
    WHERE status = 'PENDING_RECONCILIATION';

-- -------------------------------------------------------------------------
-- Additional hardening: Index for idempotency_records crash recovery
-- Support querying orphaned IN_PROGRESS records for cleanup/reconciliation.
-- -------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_idempotency_in_progress_actor
    ON idempotency_records(actor_id, status)
    WHERE status = 'IN_PROGRESS';

COMMENT ON COLUMN payouts.idempotency_key IS
    'Client-supplied idempotency key for this payout request. '
    'Enables DB-level duplicate detection as defence-in-depth behind service-level idempotency_records gate. '
    'Added in V13 P2 hardening.';
