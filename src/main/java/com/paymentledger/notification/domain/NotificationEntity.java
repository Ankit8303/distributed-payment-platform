package com.paymentledger.notification.domain;

import jakarta.persistence.*;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notifications")
public class NotificationEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "aggregate_id", nullable = false, length = 100)
    private String aggregateId;

    @Column(name = "recipient", nullable = false, length = 255)
    private String recipient;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 50)
    private NotificationChannel channel;

    @Column(name = "template_code", nullable = false, length = 100)
    private String templateCode;

    @Column(name = "template_version", nullable = false)
    private int templateVersion = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private NotificationStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts = 5;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "lease_worker_id", length = 100)
    private String leaseWorkerId;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "rendered_subject", length = 255)
    private String renderedSubject;

    @Column(name = "rendered_body", columnDefinition = "TEXT")
    private String renderedBody;

    @Column(name = "provider_reference", length = 255)
    private String providerReference;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "correlation_id", length = 100)
    private String correlationId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    protected NotificationEntity() {
    }

    public NotificationEntity(UUID eventId, String eventType, String aggregateId, String recipient,
                              NotificationChannel channel, String templateCode, int templateVersion,
                              String renderedSubject, String renderedBody, String correlationId) {
        this.id = UUID.randomUUID();
        this.eventId = eventId;
        this.eventType = eventType;
        this.aggregateId = aggregateId;
        this.recipient = recipient;
        this.channel = channel;
        this.templateCode = templateCode;
        this.templateVersion = templateVersion;
        this.renderedSubject = renderedSubject;
        this.renderedBody = renderedBody;
        this.correlationId = correlationId;
        this.status = NotificationStatus.PENDING;
        this.attemptCount = 0;
        this.maxAttempts = 5;
        this.nextAttemptAt = Instant.now();
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void claim(String workerId, Duration leaseDuration) {
        this.status = NotificationStatus.PROCESSING;
        this.leaseWorkerId = workerId;
        this.leaseExpiresAt = Instant.now().plus(leaseDuration);
        this.updatedAt = Instant.now();
    }

    public void markSent(String providerRef) {
        this.status = NotificationStatus.SENT;
        this.providerReference = providerRef;
        this.sentAt = Instant.now();
        this.leaseWorkerId = null;
        this.leaseExpiresAt = null;
        this.lastError = null;
        this.updatedAt = Instant.now();
    }

    public void scheduleRetry(Duration backoffDelay, String errorMessage) {
        this.attemptCount++;
        this.lastError = errorMessage != null && errorMessage.length() > 1000 ? errorMessage.substring(0, 1000) : errorMessage;
        this.leaseWorkerId = null;
        this.leaseExpiresAt = null;
        if (this.attemptCount >= this.maxAttempts) {
            this.status = NotificationStatus.FAILED;
        } else {
            this.status = NotificationStatus.RETRY_REQUIRED;
            this.nextAttemptAt = Instant.now().plus(backoffDelay);
        }
        this.updatedAt = Instant.now();
    }

    public void markTerminalFailure(String errorMessage) {
        this.attemptCount++;
        this.status = NotificationStatus.FAILED;
        this.lastError = errorMessage != null && errorMessage.length() > 1000 ? errorMessage.substring(0, 1000) : errorMessage;
        this.leaseWorkerId = null;
        this.leaseExpiresAt = null;
        this.updatedAt = Instant.now();
    }

    public void markSuppressed(String reason) {
        this.status = NotificationStatus.SUPPRESSED;
        this.lastError = reason;
        this.updatedAt = Instant.now();
    }

    public void prepareAdminRetry() {
        this.status = NotificationStatus.PENDING;
        this.nextAttemptAt = Instant.now();
        this.leaseWorkerId = null;
        this.leaseExpiresAt = null;
        this.lastError = null;
        this.updatedAt = Instant.now();
    }

    // Getters and setters
    public UUID getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public String getRecipient() {
        return recipient;
    }

    public NotificationChannel getChannel() {
        return channel;
    }

    public String getTemplateCode() {
        return templateCode;
    }

    public int getTemplateVersion() {
        return templateVersion;
    }

    public NotificationStatus getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getLeaseWorkerId() {
        return leaseWorkerId;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public String getRenderedSubject() {
        return renderedSubject;
    }

    public String getRenderedBody() {
        return renderedBody;
    }

    public String getProviderReference() {
        return providerReference;
    }

    public String getLastError() {
        return lastError;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }
}
