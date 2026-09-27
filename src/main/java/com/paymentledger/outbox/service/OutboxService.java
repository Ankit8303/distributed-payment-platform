package com.paymentledger.outbox.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentledger.messaging.event.EventEnvelope;
import com.paymentledger.outbox.domain.OutboxEventEntity;
import com.paymentledger.outbox.domain.OutboxStatus;
import com.paymentledger.outbox.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.paymentledger.outbox.domain.OutboxEventCreatedEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for managing outbox event persistence, claiming, and lifecycle state transitions.
 */
@Service
public class OutboxService {

    private static final Logger log = LoggerFactory.getLogger(OutboxService.class);

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher applicationEventPublisher;

    public OutboxService(OutboxEventRepository outboxEventRepository, ObjectMapper objectMapper) {
        this(outboxEventRepository, objectMapper, null);
    }

    @Autowired
    public OutboxService(OutboxEventRepository outboxEventRepository,
                         ObjectMapper objectMapper,
                         @Autowired(required = false) ApplicationEventPublisher applicationEventPublisher) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    /**
     * Persists an outbox event within the CALLER'S active database transaction (REQUIRED propagation).
     * Guarantees atomic commit or rollback alongside the caller's business entity mutations.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public OutboxEventEntity saveEvent(String aggregateType,
                                      String aggregateId,
                                      String eventType,
                                      String topic,
                                      String partitionKey,
                                      UUID correlationId,
                                      String causationId,
                                      Object payload) {
        try {
            UUID eventId = UUID.randomUUID();
            String payloadJson = objectMapper.writeValueAsString(payload);

            OutboxEventEntity outboxEvent = new OutboxEventEntity(
                    eventId,
                    aggregateType,
                    aggregateId,
                    eventType,
                    EventEnvelope.CURRENT_SCHEMA_VERSION,
                    topic,
                    partitionKey,
                    correlationId,
                    causationId,
                    payloadJson
            );

            OutboxEventEntity saved = outboxEventRepository.save(outboxEvent);
            if (applicationEventPublisher != null) {
                applicationEventPublisher.publishEvent(new OutboxEventCreatedEvent(saved.getId()));
            }
            log.info("Persisted transactional outbox event {} [{}] for aggregate {}/{} into topic {}",
                    eventId, eventType, aggregateType, aggregateId, topic);
            return saved;
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize outbox event payload for {}/{}", aggregateType, aggregateId, e);
            throw new IllegalArgumentException("Failed to serialize outbox payload", e);
        }
    }

    /**
     * Claims a batch of pending or stale-lease outbox events for publishing.
     * Uses row-level pessimistic locking with SKIP LOCKED.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<OutboxEventEntity> claimBatch(String workerId, int limit, Duration leaseDuration) {
        Instant now = Instant.now();
        Instant staleThreshold = now.minus(leaseDuration);

        List<OutboxEventEntity> eligible = outboxEventRepository.findEligibleForClaim(now, staleThreshold, limit);
        for (OutboxEventEntity event : eligible) {
            event.claim(workerId);
        }
        return outboxEventRepository.saveAllAndFlush(eligible);
    }

    /**
     * Marks an outbox event as successfully PUBLISHED.
     * Uses REQUIRES_NEW for independent, immediate status commitment.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markPublished(UUID eventId) {
        Optional<OutboxEventEntity> opt = outboxEventRepository.findById(eventId);
        if (opt.isPresent()) {
            OutboxEventEntity entity = opt.get();
            entity.markPublished();
            outboxEventRepository.saveAndFlush(entity);
            log.info("Outbox event {} [{}] marked as PUBLISHED", eventId, entity.getEventType());
        }
    }

    /**
     * Marks an outbox event as failed, scheduling a future retry with backoff or marking FAILED.
     * Uses REQUIRES_NEW for independent, immediate status commitment.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID eventId, String errorMessage, Duration retryBackoff, int maxRetries) {
        Optional<OutboxEventEntity> opt = outboxEventRepository.findById(eventId);
        if (opt.isPresent()) {
            OutboxEventEntity entity = opt.get();
            boolean permanent = entity.getAttemptCount() >= maxRetries;
            Instant nextAttempt = permanent ? null : Instant.now().plus(retryBackoff);
            entity.markFailed(errorMessage, nextAttempt, permanent);
            outboxEventRepository.saveAndFlush(entity);
            log.warn("Outbox event {} [{}] marked as {} (attempt {}/{}): {}",
                    eventId, entity.getEventType(), permanent ? "FAILED" : "PENDING_RETRY",
                    entity.getAttemptCount(), maxRetries, errorMessage);
        }
    }

    @Transactional(readOnly = true)
    public Optional<OutboxEventEntity> findById(UUID id) {
        return outboxEventRepository.findById(id);
    }

    @Transactional(readOnly = true)
    public long countByStatus(OutboxStatus status) {
        return outboxEventRepository.countByStatus(status);
    }
}
