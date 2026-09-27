-- =============================================================================
-- V10__reconciliation_engine.sql
-- Phase 12: Internal & External Provider Reconciliation & Consistency Engine
-- =============================================================================

CREATE TABLE reconciliation_cases (
    id UUID PRIMARY KEY,
    operation_type VARCHAR(50) NOT NULL,
    operation_id UUID NOT NULL,
    provider_reference VARCHAR(255),
    local_status VARCHAR(50) NOT NULL,
    provider_status VARCHAR(50),
    discrepancy_type VARCHAR(100),
    reconciliation_status VARCHAR(50) NOT NULL,
    resolution VARCHAR(500),
    attempt_count INT NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    max_attempts INT NOT NULL DEFAULT 5 CHECK (max_attempts > 0),
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_worker_id VARCHAR(100),
    lease_expires_at TIMESTAMP WITH TIME ZONE,
    last_error VARCHAR(1000),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    resolved_at TIMESTAMP WITH TIME ZONE,
    correlation_id VARCHAR(100),
    CONSTRAINT uq_reconciliation_operation UNIQUE (operation_type, operation_id),
    CONSTRAINT chk_reconciliation_status CHECK (reconciliation_status IN ('OPEN', 'IN_PROGRESS', 'RETRY_REQUIRED', 'RESOLVED', 'MANUAL_REVIEW')),
    CONSTRAINT chk_operation_type CHECK (operation_type IN ('PAYMENT', 'REFUND', 'PAYOUT', 'REVERSAL'))
);

CREATE TABLE reconciliation_attempts (
    id UUID PRIMARY KEY,
    reconciliation_case_id UUID NOT NULL REFERENCES reconciliation_cases(id) ON DELETE CASCADE,
    attempt_number INT NOT NULL CHECK (attempt_number > 0),
    worker_id VARCHAR(100) NOT NULL,
    provider_status VARCHAR(50),
    discrepancy_type VARCHAR(100),
    action_taken VARCHAR(100) NOT NULL,
    status VARCHAR(50) NOT NULL,
    error_message VARCHAR(1000),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_reconciliation_cases_status_next ON reconciliation_cases (reconciliation_status, next_attempt_at);
CREATE INDEX idx_reconciliation_cases_lease ON reconciliation_cases (lease_worker_id, lease_expires_at);
CREATE INDEX idx_reconciliation_attempts_case_id ON reconciliation_attempts (reconciliation_case_id);
