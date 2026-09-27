package com.paymentledger.refund.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentledger.account.domain.AccountEntity;
import com.paymentledger.account.repository.AccountRepository;
import com.paymentledger.ledger.domain.LedgerTransactionEntity;
import com.paymentledger.ledger.service.LedgerService;
import com.paymentledger.payment.domain.PaymentEntity;
import com.paymentledger.payment.domain.PaymentStatus;
import com.paymentledger.payment.exception.PaymentNotFoundException;
import com.paymentledger.payment.repository.PaymentRepository;
import com.paymentledger.payment.service.PaymentProvider;
import com.paymentledger.payment.service.PaymentProviderResponse;
import com.paymentledger.refund.api.dto.RefundCreateRequest;
import com.paymentledger.refund.api.dto.RefundResponse;
import com.paymentledger.refund.api.dto.ReversalCreateRequest;
import com.paymentledger.refund.api.dto.ReversalResponse;
import com.paymentledger.refund.domain.RefundEntity;
import com.paymentledger.refund.domain.RefundStatus;
import com.paymentledger.refund.domain.ReversalEntity;
import com.paymentledger.refund.domain.ReversalStatus;
import com.paymentledger.refund.exception.RefundDomainException;
import com.paymentledger.refund.exception.RefundNotFoundException;
import com.paymentledger.refund.exception.ReversalNotFoundException;
import com.paymentledger.refund.repository.RefundRepository;
import com.paymentledger.refund.repository.ReversalRepository;
import com.paymentledger.shared.error.ErrorCode;
import com.paymentledger.shared.error.IdempotencyConflictException;
import com.paymentledger.shared.idempotency.IdempotencyRecordEntity;
import com.paymentledger.shared.idempotency.IdempotencyRecordRepository;
import com.paymentledger.shared.idempotency.IdempotencyStatus;
import com.paymentledger.shared.metrics.PlatformMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
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
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);

    private final RefundRepository refundRepository;
    private final ReversalRepository reversalRepository;
    private final PaymentRepository paymentRepository;
    private final AccountRepository accountRepository;
    private final PaymentProvider paymentProvider;
    private final LedgerService ledgerService;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final ObjectMapper objectMapper;
    private RefundService self;

    @Autowired(required = false)
    private PlatformMetrics platformMetrics;

    public RefundService(RefundRepository refundRepository,
                         ReversalRepository reversalRepository,
                         PaymentRepository paymentRepository,
                         AccountRepository accountRepository,
                         PaymentProvider paymentProvider,
                         LedgerService ledgerService,
                         IdempotencyRecordRepository idempotencyRecordRepository,
                         ObjectMapper objectMapper) {
        this.refundRepository = refundRepository;
        this.reversalRepository = reversalRepository;
        this.paymentRepository = paymentRepository;
        this.accountRepository = accountRepository;
        this.paymentProvider = paymentProvider;
        this.ledgerService = ledgerService;
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.objectMapper = objectMapper;
    }

    /** Package-private setter for testing. */
    void setPlatformMetrics(PlatformMetrics platformMetrics) {
        this.platformMetrics = platformMetrics;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void setSelf(@org.springframework.context.annotation.Lazy RefundService self) {
        this.self = self;
    }

    public RefundResponse createRefund(UUID actorId,
                                       UUID paymentId,
                                       String idempotencyKey,
                                       String correlationId,
                                       RefundCreateRequest request) {
        String operation = "REFUND_CREATE";
        String requestHash = hashRefundRequest(request);

        // Idempotency Gate
        Optional<IdempotencyRecordEntity> existingRecordOpt = idempotencyRecordRepository
                .findByActorIdAndOperationAndIdempotencyKey(actorId, operation, idempotencyKey);

        if (existingRecordOpt.isPresent()) {
            IdempotencyRecordEntity existing = existingRecordOpt.get();
            if (!existing.getRequestHash().equals(requestHash)) {
                throw new IdempotencyConflictException("IDEMPOTENCY_KEY_PAYLOAD_MISMATCH", idempotencyKey);
            }
            if (existing.getStatus() == IdempotencyStatus.IN_PROGRESS) {
                throw new IdempotencyConflictException("IDEMPOTENCY_CONCURRENT_REQUEST", idempotencyKey);
            }
            if (existing.getStatus() == IdempotencyStatus.COMPLETED) {
                try {
                    return objectMapper.readValue(existing.getResponseBody(), RefundResponse.class);
                } catch (JsonProcessingException e) {
                    throw new RuntimeException("Failed to deserialize cached response", e);
                }
            }
            throw new RefundDomainException(existing.getResponseBody() != null ? existing.getResponseBody() : "Previous request failed");
        }

        IdempotencyRecordEntity idempotencyRecord = lockIdempotency(actorId, operation, idempotencyKey, requestHash);

        try {
            RefundResponse response = processRefundCreation(actorId, paymentId, correlationId, request);
            int statusCode = "PENDING_RECONCILIATION".equals(response.status()) ? 202 : 201;
            completeIdempotency(idempotencyRecord.getId(), statusCode, response, response.refundId());
            return response;
        } catch (Exception e) {
            failIdempotency(idempotencyRecord.getId(), 500, e.getMessage());
            throw e;
        }
    }

    private RefundResponse processRefundCreation(UUID actorId,
                                                 UUID paymentId,
                                                 String correlationId,
                                                 RefundCreateRequest request) {
        PaymentEntity payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException("Payment not found: " + paymentId));

        // Authorization check: Actor must own either payee (merchant requesting refund) or payer, or be admin
        AccountEntity payeeAccount = accountRepository.findById(payment.getPayeeAccountId()).orElseThrow();
        AccountEntity payerAccount = accountRepository.findById(payment.getPayerAccountId()).orElseThrow();

        if (!actorId.equals(payeeAccount.getOwnerId()) && !actorId.equals(payerAccount.getOwnerId())) {
            throw new RefundDomainException(ErrorCode.UNAUTHORIZED_FINANCIAL_OPERATION, "Unauthorized to refund this payment");
        }

        if (payment.getStatus() != PaymentStatus.SETTLED) {
            throw new RefundDomainException(ErrorCode.REFUND_NOT_ELIGIBLE, "Payment must be in SETTLED status to be refunded, was: " + payment.getStatus());
        }

        if (reversalRepository.existsByPaymentId(paymentId)) {
            throw new RefundDomainException(ErrorCode.REFUND_NOT_ELIGIBLE, "Payment has already been reversed");
        }

        // Authoritative refundable amount calculation under payment row lock
        RefundEntity refund = self.createAndValidateRefundEntity(paymentId, payment.getCurrency(), request.amountMinor(), request.reason(), payment.getAmountMinor());

        // External Provider Refund Call (OUTSIDE PostgreSQL financial transaction)
        PaymentProviderResponse providerResponse = paymentProvider.refund(payment, request.amountMinor(), request.reason());

        // Phase 16: instrument refund.created (fail-safe)
        try {
            if (platformMetrics != null) {
                platformMetrics.recordRefundCreated(payment.getCurrency());
            }
        } catch (Exception ex) {
            log.warn("Metric recording failed (refund.created): {}", ex.getMessage());
        }

        if (providerResponse.isTimeout()) {
            refund.markPendingReconciliation("GATEWAY_TIMEOUT");
            refundRepository.save(refund);
            return toRefundResponse(refund);
        } else if (!providerResponse.isSuccess()) {
            refund.fail(providerResponse.getErrorCode());
            refundRepository.save(refund);
            // Phase 16: instrument refund.failed (fail-safe)
            try {
                if (platformMetrics != null) {
                    platformMetrics.recordRefundFailed("PROVIDER_DECLINED");
                }
            } catch (Exception ex) {
                log.warn("Metric recording failed (refund.failed): {}", ex.getMessage());
            }
            throw new RefundDomainException(ErrorCode.SERVICE_UNAVAILABLE, "Provider declined refund: " + providerResponse.getErrorCode());
        }

        // Provider succeeded. Now post compensating ledger transaction atomically.
        try {
            LedgerTransactionEntity tx = ledgerService.settleRefundWithLedger(refund, payment, correlationId);
            refund.settle(providerResponse.getProviderReference(), tx.getId());
            refundRepository.save(refund);
            // Phase 16: instrument refund.settled (fail-safe)
            try {
                if (platformMetrics != null) {
                    platformMetrics.recordRefundSettled(payment.getCurrency());
                }
            } catch (Exception ex) {
                log.warn("Metric recording failed (refund.settled): {}", ex.getMessage());
            }
            return toRefundResponse(refund);
        } catch (Exception ex) {
            log.error("Failed to commit DB transaction for refund {} after provider success. Marking PENDING_RECONCILIATION.", refund.getId(), ex);
            self.markRefundPendingReconciliationInNewTx(refund.getId(), "DB_SETTLEMENT_FAILED: " + ex.getMessage());
            // Phase 16: instrument refund.failed (fail-safe)
            try {
                if (platformMetrics != null) {
                    platformMetrics.recordRefundFailed("DB_SETTLEMENT_FAILED");
                }
            } catch (Exception mex) {
                log.warn("Metric recording failed (refund.failed DB): {}", mex.getMessage());
            }
            RefundEntity reloaded = refundRepository.findById(refund.getId()).orElseThrow();
            return toRefundResponse(reloaded);
        }
    }

    @Transactional
    public RefundEntity createAndValidateRefundEntity(UUID paymentId, String currency, long amountMinor, String reason, long originalAmountMinor) {
        // Acquire pessimistic lock on payment to serialize concurrent refund checks
        paymentRepository.findByIdForUpdate(paymentId).orElseThrow();

        long alreadyRefundedOrProcessing = refundRepository.sumSettledAndProcessingRefundsForPayment(paymentId);
        long remainingRefundable = originalAmountMinor - alreadyRefundedOrProcessing;

        if (amountMinor > remainingRefundable) {
            throw new RefundDomainException(ErrorCode.REFUND_AMOUNT_EXCEEDS_PAYMENT,
                    "Refund amount " + amountMinor + " exceeds remaining refundable amount " + remainingRefundable);
        }

        RefundEntity refund = new RefundEntity(paymentId, amountMinor, currency, reason);
        refund.transitionToProcessing();
        return refundRepository.saveAndFlush(refund);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markRefundPendingReconciliationInNewTx(UUID refundId, String failureReason) {
        RefundEntity refund = refundRepository.findById(refundId).orElseThrow();
        refund.markPendingReconciliation(failureReason);
        refundRepository.save(refund);
    }

    public ReversalResponse createReversal(UUID actorId,
                                           UUID paymentId,
                                           String idempotencyKey,
                                           String correlationId,
                                           ReversalCreateRequest request) {
        String operation = "REVERSAL_CREATE";
        String requestHash = hashReversalRequest(request);

        Optional<IdempotencyRecordEntity> existingRecordOpt = idempotencyRecordRepository
                .findByActorIdAndOperationAndIdempotencyKey(actorId, operation, idempotencyKey);

        if (existingRecordOpt.isPresent()) {
            IdempotencyRecordEntity existing = existingRecordOpt.get();
            if (!existing.getRequestHash().equals(requestHash)) {
                throw new IdempotencyConflictException("IDEMPOTENCY_KEY_PAYLOAD_MISMATCH", idempotencyKey);
            }
            if (existing.getStatus() == IdempotencyStatus.IN_PROGRESS) {
                throw new IdempotencyConflictException("IDEMPOTENCY_CONCURRENT_REQUEST", idempotencyKey);
            }
            if (existing.getStatus() == IdempotencyStatus.COMPLETED) {
                try {
                    return objectMapper.readValue(existing.getResponseBody(), ReversalResponse.class);
                } catch (JsonProcessingException e) {
                    throw new RuntimeException("Failed to deserialize cached response", e);
                }
            }
            throw new RefundDomainException(existing.getResponseBody() != null ? existing.getResponseBody() : "Previous reversal failed");
        }

        IdempotencyRecordEntity idempotencyRecord = lockIdempotency(actorId, operation, idempotencyKey, requestHash);

        try {
            ReversalResponse response = self.processReversalCreation(actorId, paymentId, correlationId, request);
            completeIdempotency(idempotencyRecord.getId(), 201, response, response.reversalId());
            return response;
        } catch (Exception e) {
            failIdempotency(idempotencyRecord.getId(), 500, e.getMessage());
            throw e;
        }
    }

    @Transactional
    public ReversalResponse processReversalCreation(UUID actorId,
                                                   UUID paymentId,
                                                   String correlationId,
                                                   ReversalCreateRequest request) {
        PaymentEntity payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException("Payment not found: " + paymentId));

        AccountEntity payeeAccount = accountRepository.findById(payment.getPayeeAccountId()).orElseThrow();
        AccountEntity payerAccount = accountRepository.findById(payment.getPayerAccountId()).orElseThrow();

        if (!actorId.equals(payeeAccount.getOwnerId()) && !actorId.equals(payerAccount.getOwnerId())) {
            throw new RefundDomainException(ErrorCode.UNAUTHORIZED_FINANCIAL_OPERATION, "Unauthorized to reverse this payment");
        }

        if (payment.getStatus() != PaymentStatus.SETTLED) {
            throw new RefundDomainException(ErrorCode.REFUND_NOT_ELIGIBLE, "Payment must be in SETTLED status to be reversed, was: " + payment.getStatus());
        }

        if (reversalRepository.existsByPaymentId(paymentId)) {
            throw new RefundDomainException(ErrorCode.REVERSAL_ALREADY_EXISTS, "Payment has already been reversed");
        }

        long alreadyRefunded = refundRepository.sumSettledAndProcessingRefundsForPayment(paymentId);
        if (alreadyRefunded > 0) {
            throw new RefundDomainException(ErrorCode.REFUND_NOT_ELIGIBLE, "Cannot reverse payment with existing refunds");
        }

        ReversalEntity reversal = new ReversalEntity(paymentId, payment.getAmountMinor(), payment.getCurrency(), request.reason());
        reversal = reversalRepository.saveAndFlush(reversal);

        LedgerTransactionEntity tx = ledgerService.settleReversalWithLedger(reversal, payment, correlationId);
        reversal.complete(tx.getId());
        reversalRepository.save(reversal);

        return toReversalResponse(reversal);
    }

    @Transactional(readOnly = true)
    public RefundResponse getRefund(UUID refundId, UUID actorId) {
        RefundEntity refund = refundRepository.findById(refundId)
                .orElseThrow(() -> new RefundNotFoundException("Refund not found: " + refundId));
        PaymentEntity payment = paymentRepository.findById(refund.getPaymentId()).orElseThrow();
        AccountEntity payeeAccount = accountRepository.findById(payment.getPayeeAccountId()).orElseThrow();
        AccountEntity payerAccount = accountRepository.findById(payment.getPayerAccountId()).orElseThrow();

        if (!actorId.equals(payeeAccount.getOwnerId()) && !actorId.equals(payerAccount.getOwnerId())) {
            throw new RefundDomainException(ErrorCode.UNAUTHORIZED_FINANCIAL_OPERATION, "Unauthorized to view this refund");
        }

        return toRefundResponse(refund);
    }

    @Transactional(readOnly = true)
    public ReversalResponse getReversal(UUID reversalId, UUID actorId) {
        ReversalEntity reversal = reversalRepository.findById(reversalId)
                .orElseThrow(() -> new ReversalNotFoundException("Reversal not found: " + reversalId));
        PaymentEntity payment = paymentRepository.findById(reversal.getPaymentId()).orElseThrow();
        AccountEntity payeeAccount = accountRepository.findById(payment.getPayeeAccountId()).orElseThrow();
        AccountEntity payerAccount = accountRepository.findById(payment.getPayerAccountId()).orElseThrow();

        if (!actorId.equals(payeeAccount.getOwnerId()) && !actorId.equals(payerAccount.getOwnerId())) {
            throw new RefundDomainException(ErrorCode.UNAUTHORIZED_FINANCIAL_OPERATION, "Unauthorized to view this reversal");
        }

        return toReversalResponse(reversal);
    }

    private RefundResponse toRefundResponse(RefundEntity refund) {
        return new RefundResponse(
                refund.getId(),
                refund.getPaymentId(),
                refund.getAmountMinor(),
                refund.getCurrency(),
                refund.getStatus().name(),
                refund.getReason(),
                refund.getProviderReference(),
                refund.getCompensatingLedgerTransactionId(),
                refund.getFailureReason(),
                refund.getCreatedAt()
        );
    }

    private ReversalResponse toReversalResponse(ReversalEntity reversal) {
        return new ReversalResponse(
                reversal.getId(),
                reversal.getPaymentId(),
                reversal.getAmountMinor(),
                reversal.getCurrency(),
                reversal.getStatus().name(),
                reversal.getReason(),
                reversal.getCompensatingLedgerTransactionId(),
                reversal.getFailureReason(),
                reversal.getCreatedAt()
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IdempotencyRecordEntity lockIdempotency(UUID actorId, String operation, String idempotencyKey, String requestHash) {
        try {
            IdempotencyRecordEntity record = new IdempotencyRecordEntity(
                    actorId, operation, idempotencyKey, requestHash, Instant.now().plus(24, ChronoUnit.HOURS));
            return idempotencyRecordRepository.saveAndFlush(record);
        } catch (DataIntegrityViolationException e) {
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

    private String hashRefundRequest(RefundCreateRequest request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String raw = request.amountMinor() + "|" + (request.reason() != null ? request.reason() : "");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private String hashReversalRequest(ReversalCreateRequest request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String raw = request.reason() != null ? request.reason() : "";
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
