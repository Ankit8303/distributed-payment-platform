-- =============================================================================
-- Migration: V1__baseline_infrastructure.sql
-- Description: Baseline PostgreSQL infrastructure and extensions.
-- Phase: Phase 1 — Bootstrap & Infrastructure Foundation
-- Invariant: Business domain tables are strictly deferred to Phase 2.
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- Comment on database metadata schema
COMMENT ON EXTENSION "uuid-ossp" IS 'UUID generator functions for platform identifiers';
COMMENT ON EXTENSION "pgcrypto" IS 'Cryptographic functions for hashing and UUID generation';
