-- =============================================================================
-- V11__notification_orchestration.sql
-- Phase 13: Event-Driven Notification Orchestration Engine
-- =============================================================================

CREATE TABLE notification_templates (
    id UUID PRIMARY KEY,
    template_code VARCHAR(100) NOT NULL,
    channel VARCHAR(50) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    version INT NOT NULL DEFAULT 1,
    subject VARCHAR(255),
    body_template TEXT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_notification_template UNIQUE (template_code, channel, version),
    CONSTRAINT chk_template_channel CHECK (channel IN ('EMAIL', 'SMS', 'WEBHOOK'))
);

CREATE TABLE notifications (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    aggregate_id VARCHAR(100) NOT NULL,
    recipient VARCHAR(255) NOT NULL,
    channel VARCHAR(50) NOT NULL,
    template_code VARCHAR(100) NOT NULL,
    template_version INT NOT NULL DEFAULT 1,
    status VARCHAR(50) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    max_attempts INT NOT NULL DEFAULT 5 CHECK (max_attempts > 0),
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_worker_id VARCHAR(100),
    lease_expires_at TIMESTAMP WITH TIME ZONE,
    rendered_subject VARCHAR(255),
    rendered_body TEXT,
    provider_reference VARCHAR(255),
    last_error VARCHAR(1000),
    correlation_id VARCHAR(100),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    sent_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_notification_event_recipient UNIQUE (event_id, channel, recipient),
    CONSTRAINT chk_notification_status CHECK (status IN ('PENDING', 'PROCESSING', 'SENT', 'RETRY_REQUIRED', 'FAILED', 'SUPPRESSED')),
    CONSTRAINT chk_notification_channel CHECK (channel IN ('EMAIL', 'SMS', 'WEBHOOK'))
);

CREATE TABLE notification_deliveries (
    id UUID PRIMARY KEY,
    notification_id UUID NOT NULL REFERENCES notifications(id) ON DELETE CASCADE,
    attempt_number INT NOT NULL CHECK (attempt_number > 0),
    worker_id VARCHAR(100) NOT NULL,
    channel VARCHAR(50) NOT NULL,
    status VARCHAR(50) NOT NULL,
    provider_status VARCHAR(50),
    http_status_code INT,
    error_message VARCHAR(1000),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE webhook_subscriptions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    target_url VARCHAR(500) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    secret VARCHAR(100),
    active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_webhook_subscription UNIQUE (user_id, target_url, event_type)
);

CREATE INDEX idx_notifications_status_next ON notifications (status, next_attempt_at);
CREATE INDEX idx_notifications_lease ON notifications (lease_worker_id, lease_expires_at);
CREATE INDEX idx_notifications_event_id ON notifications (event_id);
CREATE INDEX idx_notification_deliveries_notif_id ON notification_deliveries (notification_id);
CREATE INDEX idx_webhook_subscriptions_user ON webhook_subscriptions (user_id);
CREATE INDEX idx_webhook_subscriptions_event ON webhook_subscriptions (event_type, active);
