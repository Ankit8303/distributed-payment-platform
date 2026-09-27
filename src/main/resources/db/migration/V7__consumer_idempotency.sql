-- Phase 8: Durable Consumer Message Deduplication for Idempotent Kafka Consumers
CREATE TABLE consumed_messages (
    consumer_group VARCHAR(100) NOT NULL,
    message_id UUID NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_consumed_messages PRIMARY KEY (consumer_group, message_id)
);

CREATE INDEX idx_consumed_messages_processed ON consumed_messages(processed_at DESC);

-- Phase 8: Persistent Consumer Audit Side-Effects
CREATE TABLE payment_event_audits (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id UUID NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    aggregate_id VARCHAR(255) NOT NULL,
    payload_json TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_payment_event_audits_event UNIQUE (event_id)
);

CREATE INDEX idx_payment_event_audits_aggregate ON payment_event_audits(aggregate_id);
