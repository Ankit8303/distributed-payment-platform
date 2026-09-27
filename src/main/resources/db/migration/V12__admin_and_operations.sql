-- Phase 14: Administrative & Operations Layer
-- Schema Migration: V12__admin_and_operations.sql

CREATE TABLE admin_audit_logs (
    id UUID PRIMARY KEY,
    actor_user_id UUID NOT NULL,
    actor_role VARCHAR(50) NOT NULL,
    action VARCHAR(100) NOT NULL,
    resource_type VARCHAR(50) NOT NULL,
    resource_id VARCHAR(100) NOT NULL,
    reason VARCHAR(500),
    correlation_id VARCHAR(100),
    request_id VARCHAR(100),
    before_state VARCHAR(255),
    after_state VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    metadata TEXT
);

CREATE INDEX idx_admin_audit_logs_actor ON admin_audit_logs(actor_user_id);
CREATE INDEX idx_admin_audit_logs_action ON admin_audit_logs(action);
CREATE INDEX idx_admin_audit_logs_resource ON admin_audit_logs(resource_type, resource_id);
CREATE INDEX idx_admin_audit_logs_created_at ON admin_audit_logs(created_at DESC);
CREATE INDEX idx_admin_audit_logs_correlation ON admin_audit_logs(correlation_id);
