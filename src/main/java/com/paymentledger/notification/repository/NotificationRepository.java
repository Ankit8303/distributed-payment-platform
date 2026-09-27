package com.paymentledger.notification.repository;

import com.paymentledger.notification.domain.NotificationChannel;
import com.paymentledger.notification.domain.NotificationEntity;
import com.paymentledger.notification.domain.NotificationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface NotificationRepository extends JpaRepository<NotificationEntity, UUID> {

    Optional<NotificationEntity> findByEventIdAndChannelAndRecipient(UUID eventId, NotificationChannel channel, String recipient);

    List<NotificationEntity> findByEventId(UUID eventId);

    List<NotificationEntity> findByAggregateId(String aggregateId);

    Page<NotificationEntity> findByStatus(NotificationStatus status, Pageable pageable);

    long countByStatus(NotificationStatus status);

    @Query(value = """
        SELECT * FROM notifications
        WHERE (
            (status IN ('PENDING', 'RETRY_REQUIRED') AND next_attempt_at <= :now AND (lease_expires_at IS NULL OR lease_expires_at < :now))
            OR
            (status = 'PROCESSING' AND lease_expires_at < :now)
        )
        ORDER BY next_attempt_at ASC
        LIMIT :limit
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<NotificationEntity> claimEligibleNotificationsNative(@Param("now") Instant now, @Param("limit") int limit);
}
