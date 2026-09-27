package com.paymentledger.outbox.domain;

import java.util.UUID;

/**
 * Internal application event fired when an outbox event is persisted.
 * Used by TransactionalEventListener (AFTER_COMMIT) for near zero-latency relay.
 */
public record OutboxEventCreatedEvent(UUID eventId) {}
