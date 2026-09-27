-- Phase 7: Idempotency & Concurrency Hardening
-- Enforce database-level uniqueness on payments table for idempotency scope and key

CREATE UNIQUE INDEX uq_payments_scope_key ON payments(idempotency_scope, idempotency_key);
