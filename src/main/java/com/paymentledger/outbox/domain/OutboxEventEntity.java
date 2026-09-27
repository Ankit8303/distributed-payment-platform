package com.paymentledger.outbox.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable transactional outbox entity representing publication intent for a domain event.
 * <p>
 * Written inside the identical database transaction as business state mutations,
 * guaranteeing zero dual-write inconsistency across process crashes and broker outages.
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEventEntity {

    @Id
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, length = 100)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 255)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "schema_version", nullable = false, length = 20)
    private String schemaVersion;

    @Column(name = "topic", nullable = false, length = 100)
    private String topic;

    @Column(name = "partition_key", nullable = false, length = 255)
    private String partitionKey;

    @Column(name = "correlation_id")
    private UUID correlationId;

    @Column(name = "causation_id", length = 255)
    private String causationId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb", nullable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private OutboxStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "locked_by", length = 100)
    private String lockedBy;

    @Column(name = "locked_at")
    private Instant lockedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected OutboxEventEntity() {}

    public OutboxEventEntity(UUID id,
                             String aggregateType,
                             String aggregateId,
                             String eventType,
                             String schemaVersion,
                             String topic,
                             String partitionKey,
                             UUID correlationId,
                             String causationId,
                             String payload) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.aggregateType = Objects.requireNonNull(aggregateType, "aggregateType must not be null");
        this.aggregateId = Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        this.eventType = Objects.requireNonNull(eventType, "eventType must not be null");
        this.schemaVersion = schemaVersion != null ? schemaVersion : "1.0";
        this.topic = Objects.requireNonNull(topic, "topic must not be null");
        this.partitionKey = Objects.requireNonNull(partitionKey, "partitionKey must not be null");
        this.correlationId = correlationId;
        this.causationId = causationId;
        this.payload = Objects.requireNonNull(payload, "payload must not be null");
        this.status = OutboxStatus.PENDING;
        this.attemptCount = 0;
        this.nextAttemptAt = Instant.now();
        this.createdAt = Instant.now();
    }

    public void claim(String workerId) {
        this.status = OutboxStatus.PROCESSING;
        this.lockedBy = workerId;
        this.lockedAt = Instant.now();
        this.attemptCount++;
    }

    public void markPublished() {
        this.status = OutboxStatus.PUBLISHED;
        this.publishedAt = Instant.now();
        this.lockedBy = null;
        this.lockedAt = null;
        this.lastError = null;
    }

    public void markFailed(String errorMessage, Instant nextAttempt, boolean permanentFailure) {
        this.status = permanentFailure ? OutboxStatus.FAILED : OutboxStatus.PENDING;
        this.lastError = errorMessage;
        this.nextAttemptAt = nextAttempt != null ? nextAttempt : Instant.now();
        this.lockedBy = null;
        this.lockedAt = null;
    }

    // --- Getters ---
    public UUID getId() { return id; }
    public String getAggregateType() { return aggregateType; }
    public String getAggregateId() { return aggregateId; }
    public String getEventType() { return eventType; }
    public String getSchemaVersion() { return schemaVersion; }
    public String getTopic() { return topic; }
    public String getPartitionKey() { return partitionKey; }
    public UUID getCorrelationId() { return correlationId; }
    public String getCausationId() { return causationId; }
    public String getPayload() { return payload; }
    public OutboxStatus getStatus() { return status; }
    public int getAttemptCount() { return attemptCount; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public Instant getPublishedAt() { return publishedAt; }
    public String getLastError() { return lastError; }
    public String getLockedBy() { return lockedBy; }
    public Instant getLockedAt() { return lockedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
