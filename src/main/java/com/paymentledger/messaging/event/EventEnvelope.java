package com.paymentledger.messaging.event;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Standard CloudEvents-inspired immutable event envelope (v1.0).
 *
 * @param <T> Domain event payload type
 */
public record EventEnvelope<T>(
        @JsonProperty("eventId") UUID eventId,
        @JsonProperty("eventType") String eventType,
        @JsonProperty("occurredAt") Instant occurredAt,
        @JsonProperty("aggregateType") String aggregateType,
        @JsonProperty("aggregateId") String aggregateId,
        @JsonProperty("schemaVersion") String schemaVersion,
        @JsonProperty("correlationId") UUID correlationId,
        @JsonProperty("causationId") String causationId,
        @JsonProperty("payload") T payload
) {
    public static final String CURRENT_SCHEMA_VERSION = "1.0";

    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(aggregateType, "aggregateType must not be null");
        Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        Objects.requireNonNull(schemaVersion, "schemaVersion must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
    }

    public static <T> EventEnvelope<T> create(String eventType, String aggregateType, String aggregateId, UUID correlationId, String causationId, T payload) {
        return new EventEnvelope<>(
                UUID.randomUUID(),
                eventType,
                Instant.now(),
                aggregateType,
                aggregateId,
                CURRENT_SCHEMA_VERSION,
                correlationId != null ? correlationId : UUID.randomUUID(),
                causationId,
                payload
        );
    }
}
