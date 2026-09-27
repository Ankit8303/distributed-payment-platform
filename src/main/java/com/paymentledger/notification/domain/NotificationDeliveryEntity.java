package com.paymentledger.notification.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notification_deliveries")
public class NotificationDeliveryEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "notification_id", nullable = false)
    private UUID notificationId;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @Column(name = "worker_id", nullable = false, length = 100)
    private String workerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 50)
    private NotificationChannel channel;

    @Column(name = "status", nullable = false, length = 50)
    private String status;

    @Column(name = "provider_status", length = 50)
    private String providerStatus;

    @Column(name = "http_status_code")
    private Integer httpStatusCode;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected NotificationDeliveryEntity() {
    }

    public NotificationDeliveryEntity(UUID notificationId, int attemptNumber, String workerId,
                                      NotificationChannel channel, String status, String providerStatus,
                                      Integer httpStatusCode, String errorMessage) {
        this.id = UUID.randomUUID();
        this.notificationId = notificationId;
        this.attemptNumber = attemptNumber;
        this.workerId = workerId;
        this.channel = channel;
        this.status = status;
        this.providerStatus = providerStatus;
        this.httpStatusCode = httpStatusCode;
        this.errorMessage = errorMessage != null && errorMessage.length() > 1000 ? errorMessage.substring(0, 1000) : errorMessage;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getNotificationId() {
        return notificationId;
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public String getWorkerId() {
        return workerId;
    }

    public NotificationChannel getChannel() {
        return channel;
    }

    public String getStatus() {
        return status;
    }

    public String getProviderStatus() {
        return providerStatus;
    }

    public Integer getHttpStatusCode() {
        return httpStatusCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
