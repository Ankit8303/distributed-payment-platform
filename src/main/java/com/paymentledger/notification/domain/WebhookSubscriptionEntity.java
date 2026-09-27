package com.paymentledger.notification.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "webhook_subscriptions")
public class WebhookSubscriptionEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "target_url", nullable = false, length = 500)
    private String targetUrl;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "secret", length = 100)
    private String secret;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WebhookSubscriptionEntity() {
    }

    public WebhookSubscriptionEntity(UUID userId, String targetUrl, String eventType, String secret) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.targetUrl = targetUrl;
        this.eventType = eventType;
        this.secret = secret;
        this.active = true;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getTargetUrl() {
        return targetUrl;
    }

    public void setTargetUrl(String targetUrl) {
        this.targetUrl = targetUrl;
        this.updatedAt = Instant.now();
    }

    public String getEventType() {
        return eventType;
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
        this.updatedAt = Instant.now();
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
        this.updatedAt = Instant.now();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
