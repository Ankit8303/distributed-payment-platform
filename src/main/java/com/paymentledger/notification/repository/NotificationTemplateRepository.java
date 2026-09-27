package com.paymentledger.notification.repository;

import com.paymentledger.notification.domain.NotificationChannel;
import com.paymentledger.notification.domain.NotificationTemplateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface NotificationTemplateRepository extends JpaRepository<NotificationTemplateEntity, UUID> {

    Optional<NotificationTemplateEntity> findByTemplateCodeAndChannelAndVersion(String templateCode, NotificationChannel channel, int version);

    Optional<NotificationTemplateEntity> findFirstByTemplateCodeAndChannelAndActiveTrueOrderByVersionDesc(String templateCode, NotificationChannel channel);

    List<NotificationTemplateEntity> findByEventTypeAndActiveTrue(String eventType);
}
