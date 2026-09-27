package com.paymentledger.notification.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentledger.account.domain.AccountEntity;
import com.paymentledger.account.repository.AccountRepository;
import com.paymentledger.auth.domain.UserEntity;
import com.paymentledger.auth.repository.UserRepository;
import com.paymentledger.messaging.event.EventEnvelope;
import com.paymentledger.notification.domain.*;
import com.paymentledger.notification.provider.*;
import com.paymentledger.notification.repository.*;
import com.paymentledger.notification.template.NotificationTemplateEngine;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notificationRepository;
    private final NotificationDeliveryRepository deliveryRepository;
    private final NotificationTemplateRepository templateRepository;
    private final WebhookSubscriptionRepository webhookSubscriptionRepository;
    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final NotificationTemplateEngine templateEngine;
    private final EmailProvider emailProvider;
    private final SmsProvider smsProvider;
    private final WebhookProvider webhookProvider;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final NotificationService self;

    public NotificationService(NotificationRepository notificationRepository,
                               NotificationDeliveryRepository deliveryRepository,
                               NotificationTemplateRepository templateRepository,
                               WebhookSubscriptionRepository webhookSubscriptionRepository,
                               AccountRepository accountRepository,
                               UserRepository userRepository,
                               NotificationTemplateEngine templateEngine,
                               EmailProvider emailProvider,
                               SmsProvider smsProvider,
                               WebhookProvider webhookProvider,
                               ObjectMapper objectMapper,
                               MeterRegistry meterRegistry,
                               @Lazy NotificationService self) {
        this.notificationRepository = notificationRepository;
        this.deliveryRepository = deliveryRepository;
        this.templateRepository = templateRepository;
        this.webhookSubscriptionRepository = webhookSubscriptionRepository;
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
        this.templateEngine = templateEngine;
        this.emailProvider = emailProvider;
        this.smsProvider = smsProvider;
        this.webhookProvider = webhookProvider;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.self = self;
    }

    /**
     * Ingests a domain event envelope and creates pending notification jobs for all matching channels and subscriptions.
     * Guaranteed to be idempotent: duplicate events will not create duplicate notification rows.
     */
    @Transactional
    public List<NotificationEntity> ingestEvent(EventEnvelope<JsonNode> envelope) {
        UUID eventId = envelope.eventId();
        String eventType = envelope.eventType();
        String aggregateId = envelope.aggregateId();
        String correlationId = envelope.correlationId() != null ? envelope.correlationId().toString() : UUID.randomUUID().toString();
        JsonNode payload = envelope.payload();

        log.info("Ingesting event for notifications: eventId={}, eventType={}, aggregateId={}", eventId, eventType, aggregateId);

        Map<String, Object> context = extractContext(payload, envelope);
        List<NotificationEntity> createdNotifications = new ArrayList<>();

        // 1. Process configured template-based notifications
        List<NotificationTemplateEntity> templates = templateRepository.findByEventTypeAndActiveTrue(eventType);
        for (NotificationTemplateEntity template : templates) {
            String recipient = resolveRecipient(template.getChannel(), context);
            if (recipient != null && !recipient.isBlank()) {
                NotificationEntity notification = createOrGetNotification(
                        eventId, eventType, aggregateId, recipient, template.getChannel(),
                        template.getTemplateCode(), template.getVersion(),
                        templateEngine.render(template.getSubject(), context),
                        templateEngine.render(template.getBodyTemplate(), context),
                        correlationId
                );
                if (notification != null) {
                    createdNotifications.add(notification);
                }
            }
        }

        // 2. Process active webhook subscriptions
        List<WebhookSubscriptionEntity> subscriptions = webhookSubscriptionRepository.findByEventTypeInAndActiveTrue(List.of(eventType, "*"));
        for (WebhookSubscriptionEntity sub : subscriptions) {
            String targetUrl = sub.getTargetUrl();
            String webhookPayload = payload != null ? payload.toString() : "{}";
            NotificationEntity notification = createOrGetNotification(
                    eventId, eventType, aggregateId, targetUrl, NotificationChannel.WEBHOOK,
                    "WEBHOOK_DISPATCH", 1,
                    "Webhook: " + eventType,
                    webhookPayload,
                    correlationId
            );
            if (notification != null) {
                createdNotifications.add(notification);
            }
        }

        meterRegistry.counter("notification.created", "eventType", eventType).increment(createdNotifications.size());
        return createdNotifications;
    }

    /**
     * Atomically creates or retrieves a notification, respecting database uniqueness constraints.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public NotificationEntity createOrGetNotification(UUID eventId, String eventType, String aggregateId,
                                                      String recipient, NotificationChannel channel,
                                                      String templateCode, int templateVersion,
                                                      String renderedSubject, String renderedBody,
                                                      String correlationId) {
        Optional<NotificationEntity> existing = notificationRepository.findByEventIdAndChannelAndRecipient(eventId, channel, recipient);
        if (existing.isPresent()) {
            return existing.get();
        }

        NotificationEntity entity = new NotificationEntity(
                eventId, eventType, aggregateId, recipient, channel,
                templateCode, templateVersion, renderedSubject, renderedBody, correlationId
        );
        try {
            return notificationRepository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException e) {
            log.info("Duplicate notification detected via DB constraint: eventId={}, channel={}, recipient={}", eventId, channel, recipient);
            return notificationRepository.findByEventIdAndChannelAndRecipient(eventId, channel, recipient).orElse(null);
        }
    }

    /**
     * Claims eligible notifications with pessimistic row locking and dynamic leases.
     */
    @Transactional
    public List<NotificationEntity> claimNotifications(String workerId, int limit, Duration leaseDuration) {
        List<NotificationEntity> eligible = notificationRepository.claimEligibleNotificationsNative(Instant.now(), limit);
        List<NotificationEntity> claimed = new ArrayList<>(eligible.size());
        for (NotificationEntity entity : eligible) {
            entity.claim(workerId, leaseDuration);
            claimed.add(notificationRepository.save(entity));
        }
        return claimed;
    }

    /**
     * Dispatches notification delivery to external providers outside of database transactions,
     * then records the delivery result inside a short database transaction.
     */
    public void deliverNotification(UUID notificationId, String workerId) {
        NotificationEntity notification = notificationRepository.findById(notificationId).orElse(null);
        if (notification == null) {
            return;
        }

        log.info("Delivering notification: id={}, channel={}, recipient={}, attempt={}",
                notification.getId(), notification.getChannel(), notification.getRecipient(), notification.getAttemptCount() + 1);

        String recipient = notification.getRecipient();
        String subject = notification.getRenderedSubject();
        String body = notification.getRenderedBody();
        String correlationId = notification.getCorrelationId();
        NotificationChannel channel = notification.getChannel();

        boolean success = false;
        String providerRef = null;
        ProviderErrorClassification classification = ProviderErrorClassification.NONE;
        Integer statusCode = null;
        String errorMessage = null;

        try {
            switch (channel) {
                case EMAIL -> {
                    EmailResult res = emailProvider.sendEmail(recipient, subject, body, correlationId);
                    success = res.success();
                    providerRef = res.providerReference();
                    classification = res.errorClassification();
                    statusCode = res.httpStatusCode();
                    errorMessage = res.errorMessage();
                }
                case SMS -> {
                    SmsResult res = smsProvider.sendSms(recipient, body, correlationId);
                    success = res.success();
                    providerRef = res.providerReference();
                    classification = res.errorClassification();
                    statusCode = res.httpStatusCode();
                    errorMessage = res.errorMessage();
                }
                case WEBHOOK -> {
                    WebhookResult res = webhookProvider.sendWebhook(recipient, notification.getEventType(), notification.getEventId(), body, correlationId);
                    success = res.success();
                    providerRef = res.providerReference();
                    classification = res.errorClassification();
                    statusCode = res.httpStatusCode();
                    errorMessage = res.errorMessage();
                }
            }
        } catch (Exception e) {
            log.error("Unexpected exception during delivery of notification {}: {}", notificationId, e.getMessage(), e);
            success = false;
            classification = ProviderErrorClassification.UNKNOWN;
            errorMessage = e.getMessage();
        }

        // Record delivery result in database
        self.recordDeliveryResult(notificationId, workerId, success, providerRef, classification, statusCode, errorMessage);
    }

    @Transactional
    public void recordDeliveryResult(UUID notificationId, String workerId, boolean success,
                                     String providerRef, ProviderErrorClassification classification,
                                     Integer statusCode, String errorMessage) {
        NotificationEntity notification = notificationRepository.findById(notificationId).orElseThrow();
        int currentAttempt = notification.getAttemptCount() + 1;

        NotificationDeliveryEntity delivery = new NotificationDeliveryEntity(
                notificationId, currentAttempt, workerId, notification.getChannel(),
                success ? "SUCCESS" : "FAILED",
                classification.name(),
                statusCode,
                errorMessage
        );
        deliveryRepository.save(delivery);

        if (success) {
            notification.markSent(providerRef);
            meterRegistry.counter("notification.sent", "channel", notification.getChannel().name()).increment();
            log.info("Notification {} successfully sent via {} [ref={}]", notificationId, notification.getChannel(), providerRef);
        } else {
            if (classification.isRetryable()) {
                Duration backoff = computeBackoff(currentAttempt);
                notification.scheduleRetry(backoff, errorMessage);
                if (notification.getStatus() == NotificationStatus.FAILED) {
                    meterRegistry.counter("notification.failed", "channel", notification.getChannel().name(), "reason", "MAX_RETRIES").increment();
                    log.warn("Notification {} reached maximum attempts and marked FAILED", notificationId);
                } else {
                    meterRegistry.counter("notification.retry", "channel", notification.getChannel().name()).increment();
                    log.info("Notification {} scheduled for retry in {}s (attempt {})", notificationId, backoff.toSeconds(), currentAttempt);
                }
            } else {
                notification.markTerminalFailure(errorMessage);
                meterRegistry.counter("notification.failed", "channel", notification.getChannel().name(), "reason", classification.name()).increment();
                log.warn("Notification {} marked terminal FAILED due to non-retryable error: {}", notificationId, classification);
            }
        }
        notificationRepository.save(notification);
    }

    /**
     * Exponential backoff: 1s, 2s, 4s, 8s, 16s, 32s, max 60s
     */
    public Duration computeBackoff(int attempt) {
        int delaySeconds = (int) Math.min(60, Math.pow(2, Math.max(0, attempt - 1)));
        return Duration.ofSeconds(delaySeconds);
    }

    /**
     * Admin retry execution.
     */
    @Transactional
    public NotificationEntity adminRetry(UUID notificationId, String workerId) {
        NotificationEntity notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new IllegalArgumentException("Notification not found: " + notificationId));
        notification.prepareAdminRetry();
        NotificationEntity saved = notificationRepository.save(notification);
        self.deliverNotification(notificationId, workerId);
        return saved;
    }

    private Map<String, Object> extractContext(JsonNode payload, EventEnvelope<?> envelope) {
        Map<String, Object> context = new HashMap<>();
        context.put("eventId", envelope.eventId().toString());
        context.put("eventType", envelope.eventType());
        context.put("aggregateId", envelope.aggregateId());
        context.put("aggregateType", envelope.aggregateType());
        if (envelope.correlationId() != null) {
            context.put("correlationId", envelope.correlationId().toString());
        }

        if (payload != null && payload.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = payload.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                JsonNode val = entry.getValue();
                if (val.isValueNode()) {
                    context.put(entry.getKey(), val.asText());
                } else {
                    context.put(entry.getKey(), val.toString());
                }
            }
        }
        return context;
    }

    private String resolveRecipient(NotificationChannel channel, Map<String, Object> context) {
        // Direct recipient if provided in context
        if (channel == NotificationChannel.EMAIL && context.containsKey("recipientEmail")) {
            return (String) context.get("recipientEmail");
        }
        if (channel == NotificationChannel.SMS && context.containsKey("recipientPhone")) {
            return (String) context.get("recipientPhone");
        }
        if (channel == NotificationChannel.WEBHOOK && context.containsKey("webhookUrl")) {
            return (String) context.get("webhookUrl");
        }

        // Resolve via account ID
        String accountIdStr = (String) context.get("payerAccountId");
        if (accountIdStr == null) {
            accountIdStr = (String) context.get("payeeAccountId");
        }
        if (accountIdStr == null) {
            accountIdStr = (String) context.get("accountId");
        }

        if (accountIdStr != null) {
            try {
                UUID accountId = UUID.fromString(accountIdStr);
                Optional<AccountEntity> accountOpt = accountRepository.findById(accountId);
                if (accountOpt.isPresent()) {
                    UUID ownerId = accountOpt.get().getOwnerId();
                    Optional<UserEntity> userOpt = userRepository.findById(ownerId);
                    if (userOpt.isPresent()) {
                        UserEntity user = userOpt.get();
                        if (channel == NotificationChannel.EMAIL) {
                            return user.getEmail();
                        } else if (channel == NotificationChannel.SMS) {
                            return "+1555" + Math.abs(user.getEmail().hashCode() % 10000000);
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Could not resolve account owner recipient: {}", e.getMessage());
            }
        }

        // Fallback default
        if (channel == NotificationChannel.EMAIL) {
            return "customer@example.com";
        } else if (channel == NotificationChannel.SMS) {
            return "+15551234567";
        }
        return null;
    }
}
