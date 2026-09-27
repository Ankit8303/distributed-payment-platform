package com.paymentledger.notification.repository;

import com.paymentledger.notification.domain.NotificationDeliveryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface NotificationDeliveryRepository extends JpaRepository<NotificationDeliveryEntity, UUID> {

    List<NotificationDeliveryEntity> findByNotificationIdOrderByAttemptNumberAsc(UUID notificationId);
}
