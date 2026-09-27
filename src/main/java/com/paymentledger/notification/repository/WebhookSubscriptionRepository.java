package com.paymentledger.notification.repository;

import com.paymentledger.notification.domain.WebhookSubscriptionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface WebhookSubscriptionRepository extends JpaRepository<WebhookSubscriptionEntity, UUID> {

    List<WebhookSubscriptionEntity> findByUserId(UUID userId);

    List<WebhookSubscriptionEntity> findByEventTypeInAndActiveTrue(List<String> eventTypes);
}
