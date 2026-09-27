-- Phase 9: Transactional Outbox Pattern for Zero Dual-Write Inconsistency
CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id VARCHAR(255) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    schema_version VARCHAR(20) NOT NULL DEFAULT '1.0',
    topic VARCHAR(100) NOT NULL,
    partition_key VARCHAR(255) NOT NULL,
    correlation_id UUID,
    causation_id VARCHAR(255),
    payload JSONB NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    published_at TIMESTAMPTZ,
    last_error TEXT,
    locked_by VARCHAR(100),
    locked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING', 'PROCESSING', 'PUBLISHED', 'FAILED'))
);

-- Partial index for high-performance outbox polling with SKIP LOCKED
CREATE INDEX idx_outbox_pending_polling ON outbox_events(next_attempt_at ASC, created_at ASC)
WHERE status IN ('PENDING', 'PROCESSING');

-- Index for aggregate lookup and audit inspection
CREATE INDEX idx_outbox_aggregate ON outbox_events(aggregate_type, aggregate_id);
