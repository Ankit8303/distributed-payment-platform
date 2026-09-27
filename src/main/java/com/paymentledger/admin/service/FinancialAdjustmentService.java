package com.paymentledger.admin.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentledger.account.domain.AccountEntity;
import com.paymentledger.account.repository.AccountRepository;
import com.paymentledger.admin.api.dto.FinancialAdjustmentCreateRequest;
import com.paymentledger.admin.api.dto.FinancialAdjustmentResponse;
import com.paymentledger.admin.domain.FinancialAdjustmentEntity;
import com.paymentledger.admin.repository.FinancialAdjustmentRepository;
import com.paymentledger.ledger.domain.LedgerTransactionEntity;
import com.paymentledger.ledger.service.LedgerService;
import com.paymentledger.shared.error.ErrorCode;
import com.paymentledger.shared.error.IdempotencyConflictException;
import com.paymentledger.shared.idempotency.IdempotencyRecordEntity;
import com.paymentledger.shared.idempotency.IdempotencyRecordRepository;
import com.paymentledger.shared.idempotency.IdempotencyStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
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
public class FinancialAdjustmentService {

    private final FinancialAdjustmentRepository adjustmentRepository;
    private final AccountRepository accountRepository;
    private final LedgerService ledgerService;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final ObjectMapper objectMapper;
    private FinancialAdjustmentService self;

    public FinancialAdjustmentService(FinancialAdjustmentRepository adjustmentRepository,
                                      AccountRepository accountRepository,
                                      LedgerService ledgerService,
                                      IdempotencyRecordRepository idempotencyRecordRepository,
                                      ObjectMapper objectMapper) {
        this.adjustmentRepository = adjustmentRepository;
        this.accountRepository = accountRepository;
        this.ledgerService = ledgerService;
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.objectMapper = objectMapper;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void setSelf(@org.springframework.context.annotation.Lazy FinancialAdjustmentService self) {
        this.self = self;
    }

    public FinancialAdjustmentResponse postAdjustment(UUID operatorId,
                                                      String idempotencyKey,
                                                      String correlationId,
                                                      FinancialAdjustmentCreateRequest request) {
        String operation = "ADMIN_ADJUSTMENT";
        String requestHash = hashAdjustmentRequest(request);

        Optional<IdempotencyRecordEntity> existingRecordOpt = idempotencyRecordRepository
                .findByActorIdAndOperationAndIdempotencyKey(operatorId, operation, idempotencyKey);

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
                    return objectMapper.readValue(existing.getResponseBody(), FinancialAdjustmentResponse.class);
                } catch (JsonProcessingException e) {
                    throw new RuntimeException("Failed to deserialize cached response", e);
                }
            }
            throw new RuntimeException(existing.getResponseBody() != null ? existing.getResponseBody() : "Previous adjustment failed");
        }

        IdempotencyRecordEntity idempotencyRecord = lockIdempotency(operatorId, operation, idempotencyKey, requestHash);

        try {
            FinancialAdjustmentResponse response = self.processAdjustment(operatorId, correlationId, request);
            completeIdempotency(idempotencyRecord.getId(), 201, response, response.adjustmentId());
            return response;
        } catch (Exception e) {
            failIdempotency(idempotencyRecord.getId(), 500, e.getMessage());
            throw e;
        }
    }

    @Transactional
    public FinancialAdjustmentResponse processAdjustment(UUID operatorId,
                                                         String correlationId,
                                                         FinancialAdjustmentCreateRequest request) {
        if (request.sourceAccountId().equals(request.targetAccountId())) {
            throw new IllegalArgumentException("Source and target accounts must be distinct");
        }

        if (request.reason() == null || request.reason().trim().isEmpty()) {
            throw new IllegalArgumentException("Adjustment reason is strictly required");
        }

        UUID adjustmentId = UUID.randomUUID();

        LedgerTransactionEntity tx = ledgerService.postAdjustmentWithLedger(
                adjustmentId,
                request.sourceAccountId(),
                request.targetAccountId(),
                request.amountMinor(),
                request.currency(),
                request.reason(),
                operatorId,
                correlationId
        );

        FinancialAdjustmentEntity adjustment = new FinancialAdjustmentEntity(
                request.sourceAccountId(),
                request.targetAccountId(),
                request.amountMinor(),
                request.currency(),
                request.reason(),
                operatorId,
                tx.getId()
        );

        adjustment = adjustmentRepository.save(adjustment);

        return new FinancialAdjustmentResponse(
                adjustment.getId(),
                adjustment.getSourceAccountId(),
                adjustment.getTargetAccountId(),
                adjustment.getAmountMinor(),
                adjustment.getCurrency(),
                adjustment.getReason(),
                adjustment.getOperatorId(),
                adjustment.getCompensatingLedgerTransactionId(),
                adjustment.getCreatedAt()
        );
    }

    @Transactional(readOnly = true)
    public FinancialAdjustmentResponse getAdjustment(UUID adjustmentId) {
        FinancialAdjustmentEntity adjustment = adjustmentRepository.findById(adjustmentId)
                .orElseThrow(() -> new RuntimeException("Adjustment not found: " + adjustmentId));

        return new FinancialAdjustmentResponse(
                adjustment.getId(),
                adjustment.getSourceAccountId(),
                adjustment.getTargetAccountId(),
                adjustment.getAmountMinor(),
                adjustment.getCurrency(),
                adjustment.getReason(),
                adjustment.getOperatorId(),
                adjustment.getCompensatingLedgerTransactionId(),
                adjustment.getCreatedAt()
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

    private String hashAdjustmentRequest(FinancialAdjustmentCreateRequest request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String raw = request.sourceAccountId() + "|" + request.targetAccountId() + "|" + request.amountMinor() + "|" + request.currency() + "|" + request.reason();
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
