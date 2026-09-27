package com.paymentledger.notification.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notification_templates")
public class NotificationTemplateEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "template_code", nullable = false, length = 100)
    private String templateCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 50)
    private NotificationChannel channel;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "version", nullable = false)
    private int version = 1;

    @Column(name = "subject", length = 255)
    private String subject;

    @Column(name = "body_template", nullable = false, columnDefinition = "TEXT")
    private String bodyTemplate;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected NotificationTemplateEntity() {
    }

    public NotificationTemplateEntity(String templateCode, NotificationChannel channel, String eventType, int version, String subject, String bodyTemplate) {
        this.id = UUID.randomUUID();
        this.templateCode = templateCode;
        this.channel = channel;
        this.eventType = eventType;
        this.version = version;
        this.subject = subject;
        this.bodyTemplate = bodyTemplate;
        this.active = true;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getTemplateCode() {
        return templateCode;
    }

    public NotificationChannel getChannel() {
        return channel;
    }

    public String getEventType() {
        return eventType;
    }

    public int getVersion() {
        return version;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
        this.updatedAt = Instant.now();
    }

    public String getBodyTemplate() {
        return bodyTemplate;
    }

    public void setBodyTemplate(String bodyTemplate) {
        this.bodyTemplate = bodyTemplate;
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
