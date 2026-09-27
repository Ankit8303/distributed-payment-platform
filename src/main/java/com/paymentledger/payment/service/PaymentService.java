package com.paymentledger.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentledger.account.domain.AccountEntity;
import com.paymentledger.account.domain.AccountStatus;
import com.paymentledger.account.repository.AccountRepository;
import com.paymentledger.messaging.config.TopicNames;
import com.paymentledger.messaging.event.*;
import com.paymentledger.messaging.producer.EventPublisher;
import com.paymentledger.outbox.service.OutboxService;
import com.paymentledger.payment.api.dto.PaymentCreateRequest;
import com.paymentledger.payment.api.dto.PaymentResponse;
import com.paymentledger.payment.domain.PaymentEntity;
import com.paymentledger.payment.domain.PaymentStatus;
import com.paymentledger.payment.exception.PaymentDomainException;
import com.paymentledger.payment.exception.PaymentNotFoundException;
import com.paymentledger.payment.repository.PaymentRepository;
import com.paymentledger.shared.error.IdempotencyConflictException;
import com.paymentledger.shared.idempotency.IdempotencyRecordEntity;
import com.paymentledger.shared.idempotency.IdempotencyRecordRepository;
import com.paymentledger.shared.idempotency.IdempotencyStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository paymentRepository;
    private final AccountRepository accountRepository;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final PaymentProvider paymentProvider;
    private final ObjectMapper objectMapper;
    private final com.paymentledger.ledger.service.LedgerService ledgerService;
    private final EventPublisher eventPublisher;
    private final OutboxService outboxService;

    @Autowired(required = false)
    private com.paymentledger.shared.metrics.PlatformMetrics platformMetrics;

    public void setPlatformMetrics(com.paymentledger.shared.metrics.PlatformMetrics platformMetrics) {
        this.platformMetrics = platformMetrics;
    }

    public PaymentService(PaymentRepository paymentRepository,
                          AccountRepository accountRepository,
                          IdempotencyRecordRepository idempotencyRecordRepository,
                          PaymentProvider paymentProvider,
                          ObjectMapper objectMapper,
                          com.paymentledger.ledger.service.LedgerService ledgerService) {
        this(paymentRepository, accountRepository, idempotencyRecordRepository, paymentProvider, objectMapper, ledgerService, null, null);
    }

    public PaymentService(PaymentRepository paymentRepository,
                          AccountRepository accountRepository,
                          IdempotencyRecordRepository idempotencyRecordRepository,
                          PaymentProvider paymentProvider,
                          ObjectMapper objectMapper,
                          com.paymentledger.ledger.service.LedgerService ledgerService,
                          EventPublisher eventPublisher) {
        this(paymentRepository, accountRepository, idempotencyRecordRepository, paymentProvider, objectMapper, ledgerService, eventPublisher, null);
    }

    @Autowired
    public PaymentService(PaymentRepository paymentRepository,
                          AccountRepository accountRepository,
                          IdempotencyRecordRepository idempotencyRecordRepository,
                          PaymentProvider paymentProvider,
                          ObjectMapper objectMapper,
                          com.paymentledger.ledger.service.LedgerService ledgerService,
                          @Autowired(required = false) EventPublisher eventPublisher,
                          @Autowired(required = false) OutboxService outboxService) {
        this.paymentRepository = paymentRepository;
        this.accountRepository = accountRepository;
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.paymentProvider = paymentProvider;
        this.objectMapper = objectMapper;
        this.ledgerService = ledgerService;
        this.eventPublisher = eventPublisher;
        this.outboxService = outboxService;
    }

    public PaymentResponse createPayment(UUID payerId, UUID defaultAccountId, String idempotencyKey, String correlationId, PaymentCreateRequest request) {
        String operation = "PAYMENT_CREATE";
        String requestHash = hashRequest(request);

        // Phase 1: Idempotency Gate
        Optional<IdempotencyRecordEntity> existingRecordOpt = getIdempotencyRecord(payerId, operation, idempotencyKey);
        
        if (existingRecordOpt.isPresent()) {
            IdempotencyRecordEntity existing = existingRecordOpt.get();
            if (!existing.getRequestHash().equals(requestHash)) {
                throw new IdempotencyConflictException("IDEMPOTENCY_KEY_PAYLOAD_MISMATCH", idempotencyKey);
            }
            if (existing.getStatus() == IdempotencyStatus.IN_PROGRESS) {
                // Check if payment was already processed or in progress despite idempotency record being IN_PROGRESS (e.g. crash recovery)
                String scope = payerId.toString() + ":" + operation;
                Optional<PaymentEntity> existingPayment = paymentRepository.findByIdempotencyScopeAndIdempotencyKey(scope, idempotencyKey);
                if (existingPayment.isPresent()) {
                    PaymentEntity payment = existingPayment.get();
                    if (payment.getStatus() == PaymentStatus.SETTLED || payment.getStatus() == PaymentStatus.PENDING_RECONCILIATION) {
                        PaymentResponse response = buildResponse(payment, correlationId,
                                payment.getStatus() == PaymentStatus.PENDING_RECONCILIATION ? "Transaction state indeterminate due to gateway timeout or database failure. Reconciliation active." : null);
                        int statusCode = payment.getStatus() == PaymentStatus.PENDING_RECONCILIATION ? 202 : 201;
                        completeIdempotency(existing.getId(), statusCode, response, payment.getId());
                        return response;
                    }
                    if (payment.getStatus() == PaymentStatus.DECLINED) {
                        failIdempotency(existing.getId(), 400, "PROVIDER_DECLINED: " + payment.getFailureReason());
                        throw new PaymentDomainException("PROVIDER_DECLINED: " + payment.getFailureReason());
                    }
                    if (payment.getStatus() == PaymentStatus.FAILED) {
                        failIdempotency(existing.getId(), 400, "CAPTURE_FAILED: " + payment.getFailureReason());
                        throw new PaymentDomainException("CAPTURE_FAILED: " + payment.getFailureReason());
                    }
                    // For ambiguous in-flight states (CREATED, AUTHORIZING, AUTHORIZED, CAPTURING):
                    // If the request was created recently (within in-flight processing window, e.g. 10s),
                    // treat it as an active concurrent request and reject with 409.
                    // If the record is older, the executing process has crashed/orphaned the transaction.
                    // Per Phase 7 spec: do not re-invoke external provider; transition to PENDING_RECONCILIATION.
                    boolean isOrphaned = existing.getCreatedAt() != null && 
                            existing.getCreatedAt().isBefore(Instant.now().minusSeconds(10));
                    if (isOrphaned) {
                        payment.markPendingReconciliation();
                        paymentRepository.save(payment);
                        publishPaymentEvent("PaymentPendingReconciliation", payment, correlationId,
                                new PaymentPendingReconciliationEventPayload(payment.getId(), payment.getPayerAccountId(), payment.getAmountMinor(), payment.getCurrency(), "ORPHANED_TRANSACTION_CRASH_RECOVERY", payment.getUpdatedAt()));
                        PaymentResponse response = buildResponse(payment, correlationId,
                                "Transaction state indeterminate due to application crash during processing. Reconciliation active.");
                        completeIdempotency(existing.getId(), 202, response, payment.getId());
                        return response;
                    }
                }
                throw new IdempotencyConflictException("IDEMPOTENCY_CONCURRENT_REQUEST", idempotencyKey);
            }
            if (existing.getStatus() == IdempotencyStatus.COMPLETED) {
                try {
                    return objectMapper.readValue(existing.getResponseBody(), PaymentResponse.class);
                } catch (JsonProcessingException e) {
                    throw new RuntimeException("Failed to deserialize cached response", e);
                }
            }
            // IdempotencyStatus.FAILED
            String cachedError = existing.getResponseBody();
            throw new PaymentDomainException(cachedError != null && !cachedError.isBlank()
                    ? cachedError
                    : "Previous request failed. Please use a new idempotency key.");
        }

        // Lock idempotency
        IdempotencyRecordEntity idempotencyRecord = lockIdempotency(payerId, operation, idempotencyKey, requestHash);

        PaymentResponse response = null;
        try {
            // Phase 2: Execute Business Logic
            response = processPaymentCreation(defaultAccountId, payerId, idempotencyKey, correlationId, request);
            
            // Phase 3: Complete Idempotency
            int statusCode = "PENDING_RECONCILIATION".equals(response.getStatus()) ? 202 : 201;
            completeIdempotency(idempotencyRecord.getId(), statusCode, response, response.getPaymentId());
            return response;
        } catch (Exception e) {
            failIdempotency(idempotencyRecord.getId(), 500, e.getMessage());
            throw e;
        }
    }
    
    private PaymentResponse processPaymentCreation(UUID payerAccountId, UUID payerId, String idempotencyKey, String correlationId, PaymentCreateRequest request) {
        UUID payeeAccountId = request.getPayeeAccountId();

        if (payerAccountId.equals(payeeAccountId)) {
            throw new PaymentDomainException("Payer and payee accounts cannot be the same");
        }

        AccountEntity payerAccount;
        AccountEntity payeeAccount;

        // Fetch accounts (no lock needed here, LedgerService acquires the authoritative lock later)
        if (payerAccountId.compareTo(payeeAccountId) < 0) {
            payerAccount = accountRepository.findById(payerAccountId)
                    .orElseThrow(() -> new PaymentDomainException("Payer account not found"));
            payeeAccount = accountRepository.findById(payeeAccountId)
                    .orElseThrow(() -> new PaymentDomainException("Payee account not found"));
        } else {
            payeeAccount = accountRepository.findById(payeeAccountId)
                    .orElseThrow(() -> new PaymentDomainException("Payee account not found"));
            payerAccount = accountRepository.findById(payerAccountId)
                    .orElseThrow(() -> new PaymentDomainException("Payer account not found"));
        }

        if (!payerAccount.getOwnerId().equals(payerId)) {
            throw new PaymentDomainException("Payer account does not belong to the authenticated user");
        }

        if (payerAccount.getStatus() != AccountStatus.ACTIVE) {
            throw new PaymentDomainException("ACCOUNT_FROZEN");
        }

        if (!payerAccount.getCurrency().equals(request.getCurrency()) || !payeeAccount.getCurrency().equals(request.getCurrency())) {
            throw new PaymentDomainException("Currency mismatch between accounts and payment");
        }
        
        if (payerAccount.getAccountType().name().equals("CUSTOMER")) {
            // Check balance (In a real system, materialized_balance_minor might be checked here, but 
            // the requirements say "No balance mutation APIs exist" in Phase 5, and Ledger is authoritative.
            // However, API contract says "INSUFFICIENT_FUNDS". For Phase 5, if materialized balance is used:
            if (payerAccount.getMaterializedBalanceMinor() < request.getAmountMinor()) {
                throw new PaymentDomainException("INSUFFICIENT_FUNDS");
            }
        }

        // Create Payment Entity
        String scope = payerId.toString() + ":PAYMENT_CREATE";
        PaymentEntity payment = new PaymentEntity(idempotencyKey, scope, payerAccountId, payeeAccount.getId(), request.getAmountMinor(), request.getCurrency());
        payment.authorize(); // Transition to AUTHORIZING
        payment = paymentRepository.saveAndFlush(payment);

        if (platformMetrics != null) {
            platformMetrics.recordPaymentCreated(payment.getCurrency());
        }

        publishPaymentEvent("PaymentCreated", payment, correlationId,
                new PaymentCreatedEventPayload(payment.getId(), payment.getPayerAccountId(), payment.getPayeeAccountId(), payment.getAmountMinor(), payment.getCurrency(), payment.getCreatedAt()));

        // External Provider Authorization
        PaymentProviderResponse authResponse = paymentProvider.authorize(payment, request.getPaymentMethodToken());

        if (authResponse.isTimeout()) {
            payment.markPendingReconciliation();
            paymentRepository.save(payment);
            if (platformMetrics != null) {
                platformMetrics.recordPaymentPendingReconciliation("GATEWAY_TIMEOUT");
            }
            publishPaymentEvent("PaymentPendingReconciliation", payment, correlationId,
                    new PaymentPendingReconciliationEventPayload(payment.getId(), payment.getPayerAccountId(), payment.getAmountMinor(), payment.getCurrency(), "GATEWAY_TIMEOUT", payment.getUpdatedAt()));
            return buildResponse(payment, correlationId, "Transaction state indeterminate due to gateway timeout. Reconciliation active.");
        } else if (!authResponse.isSuccess()) {
            payment.authorizationDeclined(authResponse.getErrorCode());
            paymentRepository.save(payment);
            if (platformMetrics != null) {
                platformMetrics.recordPaymentFailed("PROVIDER_DECLINED");
            }
            publishPaymentEvent("PaymentDeclined", payment, correlationId,
                    new PaymentDeclinedEventPayload(payment.getId(), payment.getPayerAccountId(), payment.getAmountMinor(), payment.getCurrency(), authResponse.getErrorCode(), payment.getUpdatedAt()));
            throw new PaymentDomainException("PROVIDER_DECLINED: " + authResponse.getErrorCode());
        }

        payment.authorizationSucceeded(authResponse.getProviderReference());
        
        payment.capture();
        payment = paymentRepository.saveAndFlush(payment);

        PaymentProviderResponse captureResponse = paymentProvider.capture(payment);

        if (captureResponse.isTimeout()) {
            payment.markPendingReconciliation();
            paymentRepository.save(payment);
            if (platformMetrics != null) {
                platformMetrics.recordPaymentPendingReconciliation("GATEWAY_TIMEOUT");
            }
            publishPaymentEvent("PaymentPendingReconciliation", payment, correlationId,
                    new PaymentPendingReconciliationEventPayload(payment.getId(), payment.getPayerAccountId(), payment.getAmountMinor(), payment.getCurrency(), "GATEWAY_TIMEOUT", payment.getUpdatedAt()));
            return buildResponse(payment, correlationId, "Transaction state indeterminate due to gateway timeout. Reconciliation active.");
        } else if (!captureResponse.isSuccess()) {
            payment.captureFailed(captureResponse.getErrorCode());
            paymentRepository.save(payment);
            if (platformMetrics != null) {
                platformMetrics.recordPaymentFailed("CAPTURE_FAILED");
            }
            publishPaymentEvent("PaymentFailed", payment, correlationId,
                    new PaymentFailedEventPayload(payment.getId(), payment.getPayerAccountId(), payment.getAmountMinor(), payment.getCurrency(), captureResponse.getErrorCode(), payment.getUpdatedAt()));
            throw new PaymentDomainException("CAPTURE_FAILED: " + captureResponse.getErrorCode());
        }

        // ============================================================
        // FINANCIAL SETTLEMENT — ONE ATOMIC DATABASE TRANSACTION
        // ============================================================
        try {
            ledgerService.settlePaymentWithLedger(payment.getId(), captureResponse.getProviderReference(), correlationId);
            // Reload the managed entity state for response building
            payment = paymentRepository.findById(payment.getId()).orElseThrow();
            if (platformMetrics != null) {
                long durationMs = java.time.Duration.between(payment.getCreatedAt(), java.time.Instant.now()).toMillis();
                platformMetrics.recordPaymentSettled(payment.getCurrency(), durationMs);
            }
            if (outboxService == null) {
                publishPaymentEvent("PaymentSettled", payment, correlationId,
                        new PaymentSettledEventPayload(payment.getId(), payment.getPayerAccountId(), payment.getPayeeAccountId(), payment.getAmountMinor(), payment.getFeeAmountMinor(), payment.getCurrency(), null, payment.getProviderReference(), payment.getUpdatedAt()));
            }
        } catch (Exception ex) {
            // DB failure after external provider capture succeeded.
            payment = paymentRepository.findById(payment.getId()).orElseThrow();
            payment.markPendingReconciliation();
            paymentRepository.save(payment);
            if (platformMetrics != null) {
                platformMetrics.recordPaymentPendingReconciliation("DB_SETTLEMENT_FAILED");
            }
            publishPaymentEvent("PaymentPendingReconciliation", payment, correlationId,
                    new PaymentPendingReconciliationEventPayload(payment.getId(), payment.getPayerAccountId(), payment.getAmountMinor(), payment.getCurrency(), "DB_SETTLEMENT_FAILED", payment.getUpdatedAt()));
            return buildResponse(payment, correlationId, "Provider captured but database transaction failed. Reconciliation active.");
        }

        return buildResponse(payment, correlationId, null);
    }

    private void publishPaymentEvent(String eventType, PaymentEntity payment, String correlationId, Object payload) {
        UUID corrId;
        try {
            corrId = (correlationId != null && !correlationId.isBlank()) 
                    ? UUID.fromString(correlationId) 
                    : UUID.randomUUID();
        } catch (IllegalArgumentException e) {
            corrId = UUID.nameUUIDFromBytes(correlationId.getBytes(StandardCharsets.UTF_8));
        }

        if (outboxService != null) {
            try {
                outboxService.saveEvent(
                        "PAYMENT",
                        payment.getId().toString(),
                        eventType,
                        TopicNames.PAYMENT_EVENTS,
                        payment.getPayerAccountId().toString(),
                        corrId,
                        payment.getId().toString(),
                        payload
                );
                return;
            } catch (Exception ex) {
                log.error("Failed to persist outbox event for {} (paymentId={}): {}", eventType, payment.getId(), ex.getMessage(), ex);
                throw ex;
            }
        }

        if (eventPublisher == null) {
            return;
        }
        try {
            UUID eventId = UUID.randomUUID();
            EventEnvelope<Object> envelope = new EventEnvelope<>(
                    eventId,
                    eventType,
                    Instant.now(),
                    "PAYMENT",
                    payment.getId().toString(),
                    EventEnvelope.CURRENT_SCHEMA_VERSION,
                    corrId,
                    payment.getId().toString(),
                    payload
            );
            eventPublisher.publish(TopicNames.PAYMENT_EVENTS, payment.getPayerAccountId().toString(), envelope);
        } catch (Exception ex) {
            log.warn("Direct Kafka publication failed for {} event (paymentId={}): {}. PostgreSQL financial transaction remains authoritative.",
                    eventType, payment.getId(), ex.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public PaymentResponse getPayment(UUID paymentId, UUID callerId, String role) {
        PaymentEntity payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException("Payment not found"));

        if (!role.equals("ADMIN") && !role.equals("SYSTEM")) {
            AccountEntity payer = accountRepository.findById(payment.getPayerAccountId()).orElse(null);
            AccountEntity payee = accountRepository.findById(payment.getPayeeAccountId()).orElse(null);
            
            boolean isOwner = (payer != null && payer.getOwnerId().equals(callerId)) ||
                              (payee != null && payee.getOwnerId().equals(callerId));
            
            if (!isOwner) {
                throw new PaymentNotFoundException("Payment not found or access denied");
            }
        }

        return buildResponse(payment, null, null);
    }

    // Isolated transaction for idempotency
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IdempotencyRecordEntity lockIdempotency(UUID actorId, String operation, String idempotencyKey, String requestHash) {
        try {
            IdempotencyRecordEntity record = new IdempotencyRecordEntity(actorId, operation, idempotencyKey, requestHash, Instant.now().plus(24, ChronoUnit.HOURS));
            return idempotencyRecordRepository.saveAndFlush(record);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new IdempotencyConflictException("IDEMPOTENCY_CONCURRENT_REQUEST", idempotencyKey);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeIdempotency(UUID recordId, int statusCode, Object responseBody, UUID resourceId) {
        IdempotencyRecordEntity record = idempotencyRecordRepository.findById(recordId).orElseThrow();
        try {
            record.complete(statusCode, objectMapper.writeValueAsString(responseBody), resourceId);
        } catch (JsonProcessingException e) {
            record.complete(statusCode, "{}", resourceId);
        }
        idempotencyRecordRepository.save(record);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failIdempotency(UUID recordId, int statusCode, String responseBody) {
        IdempotencyRecordEntity record = idempotencyRecordRepository.findById(recordId).orElseThrow();
        record.fail(statusCode, responseBody);
        idempotencyRecordRepository.save(record);
    }

    public Optional<IdempotencyRecordEntity> getIdempotencyRecord(UUID actorId, String operation, String idempotencyKey) {
        return idempotencyRecordRepository.findByActorIdAndOperationAndIdempotencyKey(actorId, operation, idempotencyKey);
    }

    private String hashRequest(PaymentCreateRequest request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String raw = request.getPayeeAccountId().toString() + "|" + request.getAmountMinor() + "|" + request.getCurrency() + "|" + request.getPaymentMethodToken();
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private PaymentResponse buildResponse(PaymentEntity payment, String correlationId, String message) {
        PaymentResponse response = new PaymentResponse();
        response.setPaymentId(payment.getId());
        response.setIdempotencyKey(payment.getIdempotencyKey());
        response.setPayerAccountId(payment.getPayerAccountId());
        response.setPayeeAccountId(payment.getPayeeAccountId());
        response.setAmountMinor(payment.getAmountMinor());
        response.setFeeAmountMinor(payment.getFeeAmountMinor());
        response.setCurrency(payment.getCurrency());
        response.setStatus(payment.getStatus().name());
        response.setProviderReference(payment.getProviderReference());
        response.setCorrelationId(correlationId);
        response.setCreatedAt(payment.getCreatedAt());
        
        if (payment.getStatus() == PaymentStatus.PENDING_RECONCILIATION) {
            response.setMessage(message);
            response.setPollUrl("/api/v1/payments/" + payment.getId());
        }
        
        return response;
    }
}
