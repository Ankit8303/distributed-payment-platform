package com.paymentledger.notification.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationEntityTest {

    @Test
    @DisplayName("Notification constructor initializes default PENDING state, attemptCount 0, and maxAttempts 5")
    void defaultInitialization() {
        UUID eventId = UUID.randomUUID();
        NotificationEntity notification = new NotificationEntity(
                eventId, "PaymentSettled", "AGG-1", "user@example.com",
                NotificationChannel.EMAIL, "TEMPLATE_V1", 1,
                "Subject", "Body", "corr-1"
        );

        assertThat(notification.getEventId()).isEqualTo(eventId);
        assertThat(notification.getEventType()).isEqualTo("PaymentSettled");
        assertThat(notification.getRecipient()).isEqualTo("user@example.com");
        assertThat(notification.getChannel()).isEqualTo(NotificationChannel.EMAIL);
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(notification.getAttemptCount()).isEqualTo(0);
        assertThat(notification.getMaxAttempts()).isEqualTo(5);
        assertThat(notification.getNextAttemptAt()).isNotNull();
    }

    @Test
    @DisplayName("claim sets status to PROCESSING and assigns lease worker and expiration")
    void claimLease() {
        NotificationEntity notification = new NotificationEntity(
                UUID.randomUUID(), "PaymentSettled", "AGG-1", "user@example.com",
                NotificationChannel.EMAIL, "TEMPLATE_V1", 1,
                "Subject", "Body", "corr-1"
        );

        notification.claim("worker-alpha", Duration.ofSeconds(30));

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.PROCESSING);
        assertThat(notification.getLeaseWorkerId()).isEqualTo("worker-alpha");
        assertThat(notification.getLeaseExpiresAt()).isNotNull();
    }

    @Test
    @DisplayName("markSent sets status to SENT, sets sentAt, and clears lease")
    void markSent() {
        NotificationEntity notification = new NotificationEntity(
                UUID.randomUUID(), "PaymentSettled", "AGG-1", "user@example.com",
                NotificationChannel.EMAIL, "TEMPLATE_V1", 1,
                "Subject", "Body", "corr-1"
        );
        notification.claim("worker-alpha", Duration.ofSeconds(30));
        notification.markSent("provider-msg-123");

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(notification.getProviderReference()).isEqualTo("provider-msg-123");
        assertThat(notification.getSentAt()).isNotNull();
        assertThat(notification.getLeaseWorkerId()).isNull();
        assertThat(notification.getLeaseExpiresAt()).isNull();
    }

    @Test
    @DisplayName("scheduleRetry increments attempts and transitions to RETRY_REQUIRED then FAILED when max reached")
    void scheduleRetryAndTerminalFailure() {
        NotificationEntity notification = new NotificationEntity(
                UUID.randomUUID(), "PaymentSettled", "AGG-1", "user@example.com",
                NotificationChannel.EMAIL, "TEMPLATE_V1", 1,
                "Subject", "Body", "corr-1"
        );

        for (int i = 1; i <= 4; i++) {
            notification.claim("worker-1", Duration.ofSeconds(10));
            notification.scheduleRetry(Duration.ofSeconds(2), "Transient network error " + i);
            assertThat(notification.getAttemptCount()).isEqualTo(i);
            assertThat(notification.getStatus()).isEqualTo(NotificationStatus.RETRY_REQUIRED);
            assertThat(notification.getLastError()).contains("Transient network error " + i);
        }

        // 5th attempt reaches maxAttempts (5) -> terminal FAILED
        notification.claim("worker-1", Duration.ofSeconds(10));
        notification.scheduleRetry(Duration.ofSeconds(2), "5th failed attempt");
        assertThat(notification.getAttemptCount()).isEqualTo(5);
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.FAILED);
    }

    @Test
    @DisplayName("markTerminalFailure immediately sets status to FAILED")
    void terminalFailure() {
        NotificationEntity notification = new NotificationEntity(
                UUID.randomUUID(), "PaymentSettled", "AGG-1", "user@example.com",
                NotificationChannel.EMAIL, "TEMPLATE_V1", 1,
                "Subject", "Body", "corr-1"
        );
        notification.claim("worker-1", Duration.ofSeconds(10));
        notification.markTerminalFailure("Permanent 4xx bad recipient");

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(notification.getLastError()).isEqualTo("Permanent 4xx bad recipient");
    }

    @Test
    @DisplayName("prepareAdminRetry resets notification to PENDING and clears lease")
    void prepareAdminRetry() {
        NotificationEntity notification = new NotificationEntity(
                UUID.randomUUID(), "PaymentSettled", "AGG-1", "user@example.com",
                NotificationChannel.EMAIL, "TEMPLATE_V1", 1,
                "Subject", "Body", "corr-1"
        );
        notification.markTerminalFailure("Permanent error");
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.FAILED);

        notification.prepareAdminRetry();
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(notification.getLastError()).isNull();
        assertThat(notification.getLeaseWorkerId()).isNull();
    }
}
