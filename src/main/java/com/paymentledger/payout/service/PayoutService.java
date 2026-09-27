package com.paymentledger.payout.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentledger.account.domain.AccountEntity;
import com.paymentledger.account.domain.AccountStatus;
import com.paymentledger.account.domain.AccountType;
import com.paymentledger.account.repository.AccountRepository;
import com.paymentledger.ledger.domain.LedgerTransactionEntity;
import com.paymentledger.ledger.repository.LedgerEntryRepository;
import com.paymentledger.ledger.service.LedgerService;
import com.paymentledger.payment.service.PaymentProvider;
import com.paymentledger.payment.service.PaymentProviderResponse;
import com.paymentledger.payout.api.dto.PayoutCreateRequest;
import com.paymentledger.payout.api.dto.PayoutResponse;
import com.paymentledger.payout.domain.PayoutEntity;
import com.paymentledger.payout.exception.PayoutDomainException;
import com.paymentledger.payout.exception.PayoutNotFoundException;
import com.paymentledger.payout.repository.PayoutRepository;
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
public class PayoutService {

    private static final Logger log = LoggerFactory.getLogger(PayoutService.class);

    private final PayoutRepository payoutRepository;
    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final PaymentProvider paymentProvider;
    private final LedgerService ledgerService;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final ObjectMapper objectMapper;
    private PayoutService self;

    @Autowired(required = false)
    private PlatformMetrics platformMetrics;

    public PayoutService(PayoutRepository payoutRepository,
                         AccountRepository accountRepository,
                         LedgerEntryRepository ledgerEntryRepository,
                         PaymentProvider paymentProvider,
                         LedgerService ledgerService,
                         IdempotencyRecordRepository idempotencyRecordRepository,
                         ObjectMapper objectMapper) {
        this.payoutRepository = payoutRepository;
        this.accountRepository = accountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
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
    public void setSelf(@org.springframework.context.annotation.Lazy PayoutService self) {
        this.self = self;
    }

    public PayoutResponse createPayout(UUID actorId,
                                       String idempotencyKey,
                                       String correlationId,
                                       PayoutCreateRequest request) {
        String operation = "PAYOUT_CREATE";
        String requestHash = hashPayoutRequest(request);

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
                    return objectMapper.readValue(existing.getResponseBody(), PayoutResponse.class);
                } catch (JsonProcessingException e) {
                    throw new RuntimeException("Failed to deserialize cached response", e);
                }
            }
            throw new PayoutDomainException(existing.getResponseBody() != null ? existing.getResponseBody() : "Previous payout failed");
        }

        IdempotencyRecordEntity idempotencyRecord = lockIdempotency(actorId, operation, idempotencyKey, requestHash);

        try {
            PayoutResponse response = processPayoutCreation(actorId, correlationId, request);
            int statusCode = "PENDING_RECONCILIATION".equals(response.status()) ? 202 : 201;
            completeIdempotency(idempotencyRecord.getId(), statusCode, response, response.payoutId());
            return response;
        } catch (Exception e) {
            failIdempotency(idempotencyRecord.getId(), 500, e.getMessage());
            throw e;
        }
    }

    private PayoutResponse processPayoutCreation(UUID actorId,
                                                 String correlationId,
                                                 PayoutCreateRequest request) {
        AccountEntity originAccount = accountRepository.findById(request.accountId())
                .orElseThrow(() -> new PayoutDomainException(ErrorCode.RESOURCE_NOT_FOUND, "Account not found: " + request.accountId()));

        if (!actorId.equals(originAccount.getOwnerId())) {
            throw new PayoutDomainException(ErrorCode.UNAUTHORIZED_FINANCIAL_OPERATION, "Unauthorized to initiate payout from this account");
        }

        if (originAccount.getStatus() != AccountStatus.ACTIVE) {
            throw new PayoutDomainException(ErrorCode.ACCOUNT_FROZEN, "Account is frozen");
        }

        if (!originAccount.getCurrency().equals(request.currency())) {
            throw new PayoutDomainException(ErrorCode.INVALID_PAYLOAD, "Account currency mismatch");
        }

        AccountEntity settlementAccount = accountRepository.findFirstByAccountTypeAndCurrency(AccountType.INTERNAL_SETTLEMENT, request.currency())
                .orElseGet(() -> {
                    AccountEntity fallback = new AccountEntity("SETTLEMENT-" + UUID.randomUUID().toString().substring(0, 8), originAccount.getOwnerId(), AccountType.INTERNAL_SETTLEMENT, request.currency(), AccountStatus.ACTIVE);
                    return accountRepository.save(fallback);
                });

        PayoutEntity payout = self.createAndValidatePayoutEntity(request.accountId(), request.amountMinor(), request.currency());

        // External Provider Call (OUTSIDE DB TX)
        PaymentProviderResponse providerResponse = paymentProvider.payout(request.accountId(), request.amountMinor(), request.currency());

        // Phase 16: instrument payout.created (fail-safe)
        try {
            if (platformMetrics != null) {
                platformMetrics.recordPayoutCreated(request.currency());
            }
        } catch (Exception ex) {
            log.warn("Metric recording failed (payout.created): {}", ex.getMessage());
        }

        if (providerResponse.isTimeout()) {
            payout.markPendingReconciliation("GATEWAY_TIMEOUT");
            payoutRepository.save(payout);
            return toPayoutResponse(payout);
        } else if (!providerResponse.isSuccess()) {
            payout.fail(providerResponse.getErrorCode());
            payoutRepository.save(payout);
            // Phase 16: instrument payout.failed (fail-safe)
            try {
                if (platformMetrics != null) {
                    platformMetrics.recordPayoutFailed("PROVIDER_DECLINED");
                }
            } catch (Exception ex) {
                log.warn("Metric recording failed (payout.failed): {}", ex.getMessage());
            }
            throw new PayoutDomainException(ErrorCode.SERVICE_UNAVAILABLE, "Provider declined payout: " + providerResponse.getErrorCode());
        }

        // Provider succeeded. Settle compensating ledger transaction atomically.
        try {
            LedgerTransactionEntity tx = ledgerService.settlePayoutWithLedger(payout, originAccount, settlementAccount, correlationId);
            payout.settle(providerResponse.getProviderReference(), tx.getId());
            payoutRepository.save(payout);
            // Phase 16: instrument payout.settled (fail-safe)
            try {
                if (platformMetrics != null) {
                    platformMetrics.recordPayoutSettled(request.currency());
                }
            } catch (Exception ex) {
                log.warn("Metric recording failed (payout.settled): {}", ex.getMessage());
            }
            return toPayoutResponse(payout);
        } catch (Exception ex) {
            log.error("Failed to commit DB transaction for payout {} after provider success. Marking PENDING_RECONCILIATION.", payout.getId(), ex);
            self.markPayoutPendingReconciliationInNewTx(payout.getId(), "DB_SETTLEMENT_FAILED: " + ex.getMessage());
            // Phase 16: instrument payout.failed (fail-safe)
            try {
                if (platformMetrics != null) {
                    platformMetrics.recordPayoutFailed("DB_SETTLEMENT_FAILED");
                }
            } catch (Exception mex) {
                log.warn("Metric recording failed (payout.failed DB): {}", mex.getMessage());
            }
            PayoutEntity reloaded = payoutRepository.findById(payout.getId()).orElseThrow();
            return toPayoutResponse(reloaded);
        }
    }

    @Transactional
    public PayoutEntity createAndValidatePayoutEntity(UUID accountId, long amountMinor, String currency) {
        AccountEntity lockedAccount = accountRepository.findByIdForUpdate(accountId).orElseThrow();
        if (lockedAccount.getStatus() != AccountStatus.ACTIVE) {
            throw new PayoutDomainException(ErrorCode.ACCOUNT_FROZEN, "Account is frozen");
        }

        long authoritativeBalance = ledgerEntryRepository.calculateLedgerBalanceMinor(accountId);
        if (authoritativeBalance < amountMinor) {
            throw new PayoutDomainException(ErrorCode.PAYOUT_INSUFFICIENT_FUNDS, "Insufficient ledger balance for payout");
        }

        PayoutEntity payout = new PayoutEntity(accountId, amountMinor, currency);
        payout.transitionToProcessing();
        return payoutRepository.saveAndFlush(payout);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markPayoutPendingReconciliationInNewTx(UUID payoutId, String failureReason) {
        PayoutEntity payout = payoutRepository.findById(payoutId).orElseThrow();
        payout.markPendingReconciliation(failureReason);
        payoutRepository.save(payout);
    }

    @Transactional(readOnly = true)
    public PayoutResponse getPayout(UUID payoutId, UUID actorId) {
        PayoutEntity payout = payoutRepository.findById(payoutId)
                .orElseThrow(() -> new PayoutNotFoundException(payoutId));
        AccountEntity originAccount = accountRepository.findById(payout.getAccountId()).orElseThrow();

        if (!actorId.equals(originAccount.getOwnerId())) {
            throw new PayoutDomainException(ErrorCode.UNAUTHORIZED_FINANCIAL_OPERATION, "Unauthorized to view this payout");
        }

        return toPayoutResponse(payout);
    }

    private PayoutResponse toPayoutResponse(PayoutEntity payout) {
        return new PayoutResponse(
                payout.getId(),
                payout.getAccountId(),
                payout.getAmountMinor(),
                payout.getCurrency(),
                payout.getStatus().name(),
                payout.getProviderReference(),
                payout.getCompensatingLedgerTransactionId(),
                payout.getFailureReason(),
                payout.getCreatedAt()
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

    private String hashPayoutRequest(PayoutCreateRequest request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String raw = request.accountId().toString() + "|" + request.amountMinor() + "|" + request.currency();
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
