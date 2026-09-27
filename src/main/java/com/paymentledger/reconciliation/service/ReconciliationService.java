package com.paymentledger.reconciliation.service;

import com.paymentledger.account.domain.AccountEntity;
import com.paymentledger.account.domain.AccountType;
import com.paymentledger.account.domain.AccountStatus;
import com.paymentledger.account.repository.AccountRepository;
import com.paymentledger.ledger.domain.LedgerTransactionEntity;
import com.paymentledger.ledger.service.LedgerService;
import com.paymentledger.messaging.event.PaymentFailedEventPayload;
import com.paymentledger.messaging.config.TopicNames;
import com.paymentledger.outbox.service.OutboxService;
import com.paymentledger.payment.domain.PaymentEntity;
import com.paymentledger.payment.domain.PaymentStatus;
import com.paymentledger.payment.repository.PaymentRepository;
import com.paymentledger.payment.service.PaymentProvider;
import com.paymentledger.payment.service.ProviderOperationStatus;
import com.paymentledger.payout.domain.PayoutEntity;
import com.paymentledger.payout.domain.PayoutStatus;
import com.paymentledger.payout.repository.PayoutRepository;
import com.paymentledger.reconciliation.domain.*;
import com.paymentledger.reconciliation.repository.ReconciliationAttemptRepository;
import com.paymentledger.reconciliation.repository.ReconciliationCaseRepository;
import com.paymentledger.refund.domain.RefundEntity;
import com.paymentledger.refund.domain.RefundStatus;
import com.paymentledger.refund.domain.ReversalEntity;
import com.paymentledger.refund.domain.ReversalStatus;
import com.paymentledger.refund.repository.RefundRepository;
import com.paymentledger.refund.repository.ReversalRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final ReconciliationCaseRepository caseRepository;
    private final ReconciliationAttemptRepository attemptRepository;
    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final PayoutRepository payoutRepository;
    private final ReversalRepository reversalRepository;
    private final AccountRepository accountRepository;
    private final LedgerService ledgerService;
    private final PaymentProvider paymentProvider;
    private final OutboxService outboxService;
    private final MeterRegistry meterRegistry;

    private ReconciliationService self;

    public ReconciliationService(ReconciliationCaseRepository caseRepository,
                                 ReconciliationAttemptRepository attemptRepository,
                                 PaymentRepository paymentRepository,
                                 RefundRepository refundRepository,
                                 PayoutRepository payoutRepository,
                                 ReversalRepository reversalRepository,
                                 AccountRepository accountRepository,
                                 LedgerService ledgerService,
                                 PaymentProvider paymentProvider,
                                 @Autowired(required = false) OutboxService outboxService,
                                 @Autowired(required = false) MeterRegistry meterRegistry) {
        this.caseRepository = caseRepository;
        this.attemptRepository = attemptRepository;
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.payoutRepository = payoutRepository;
        this.reversalRepository = reversalRepository;
        this.accountRepository = accountRepository;
        this.ledgerService = ledgerService;
        this.paymentProvider = paymentProvider;
        this.outboxService = outboxService;
        this.meterRegistry = meterRegistry;
    }

    @Autowired
    public void setSelf(@Lazy ReconciliationService self) {
        this.self = self;
    }

    @Transactional
    public ReconciliationCaseEntity createOrGetCase(ReconciliationOperationType type,
                                                    UUID operationId,
                                                    String providerReference,
                                                    String localStatus,
                                                    String correlationId) {
        Optional<ReconciliationCaseEntity> existing = caseRepository.findByOperationTypeAndOperationId(type, operationId);
        if (existing.isPresent()) {
            return existing.get();
        }

        ReconciliationCaseEntity newCase = new ReconciliationCaseEntity(type, operationId, providerReference, localStatus, correlationId);
        newCase = caseRepository.save(newCase);
        incrementMetric("reconciliation.candidates");
        log.info("Created reconciliation case {} for {} {}", newCase.getId(), type, operationId);
        return newCase;
    }

    @Transactional
    public int scanAndEnrolCandidates() {
        int enrolled = 0;

        List<PaymentEntity> pendingPayments = paymentRepository.findByStatus(PaymentStatus.PENDING_RECONCILIATION);
        for (PaymentEntity p : pendingPayments) {
            if (caseRepository.findByOperationTypeAndOperationId(ReconciliationOperationType.PAYMENT, p.getId()).isEmpty()) {
                createOrGetCase(ReconciliationOperationType.PAYMENT, p.getId(), p.getProviderReference(), p.getStatus().name(), null);
                enrolled++;
            }
        }

        List<RefundEntity> pendingRefunds = refundRepository.findByStatus(RefundStatus.PENDING_RECONCILIATION);
        for (RefundEntity r : pendingRefunds) {
            if (caseRepository.findByOperationTypeAndOperationId(ReconciliationOperationType.REFUND, r.getId()).isEmpty()) {
                createOrGetCase(ReconciliationOperationType.REFUND, r.getId(), r.getProviderReference(), r.getStatus().name(), null);
                enrolled++;
            }
        }

        List<PayoutEntity> pendingPayouts = payoutRepository.findByStatus(PayoutStatus.PENDING_RECONCILIATION);
        for (PayoutEntity po : pendingPayouts) {
            if (caseRepository.findByOperationTypeAndOperationId(ReconciliationOperationType.PAYOUT, po.getId()).isEmpty()) {
                createOrGetCase(ReconciliationOperationType.PAYOUT, po.getId(), po.getProviderReference(), po.getStatus().name(), null);
                enrolled++;
            }
        }

        if (enrolled > 0) {
            log.info("Scanned and enrolled {} new reconciliation candidates", enrolled);
        }
        return enrolled;
    }

    @Transactional
    public List<ReconciliationCaseEntity> claimCases(String workerId, int limit, Duration leaseDuration) {
        List<ReconciliationCaseEntity> claimable = caseRepository.claimEligibleCasesNative(Instant.now(), limit);
        for (ReconciliationCaseEntity reconCase : claimable) {
            reconCase.claim(workerId, leaseDuration);
            caseRepository.save(reconCase);
        }
        return claimable;
    }

    public void reconcileCase(UUID caseId, String workerId) {
        ReconciliationCaseEntity reconCase = caseRepository.findById(caseId).orElse(null);
        if (reconCase == null) {
            return;
        }

        if (reconCase.getReconciliationStatus() == ReconciliationStatus.RESOLVED ||
                reconCase.getReconciliationStatus() == ReconciliationStatus.MANUAL_REVIEW) {
            return;
        }

        incrementMetric("reconciliation.attempts");

        // External provider query (EXECUTED OUTSIDE PostgreSQL TRANSACTION)
        ProviderOperationStatus providerStatus;
        try {
            providerStatus = paymentProvider.queryOperationStatus(
                    reconCase.getOperationType().name(),
                    reconCase.getOperationId(),
                    reconCase.getProviderReference()
            );
        } catch (Exception ex) {
            log.warn("External provider query failed for case {}: {}", caseId, ex.getMessage());
            providerStatus = ProviderOperationStatus.unknown("Provider query exception: " + ex.getMessage());
        }

        // Execute local state resolution under transaction
        try {
            self.resolveOperation(caseId, providerStatus, workerId);
        } catch (Exception ex) {
            log.error("Error executing resolution for case {}", caseId, ex);
            self.recordCaseException(caseId, workerId, ex.getMessage(), providerStatus);
        }
    }

    @Transactional
    public void resolveOperation(UUID caseId, ProviderOperationStatus providerStatus, String workerId) {
        ReconciliationCaseEntity reconCase = caseRepository.findByIdForUpdate(caseId).orElseThrow();

        if (reconCase.getReconciliationStatus() == ReconciliationStatus.RESOLVED) {
            return;
        }

        switch (reconCase.getOperationType()) {
            case PAYMENT -> resolvePayment(reconCase, providerStatus, workerId);
            case REFUND -> resolveRefund(reconCase, providerStatus, workerId);
            case PAYOUT -> resolvePayout(reconCase, providerStatus, workerId);
            case REVERSAL -> resolveReversal(reconCase, providerStatus, workerId);
        }
    }

    private void resolvePayment(ReconciliationCaseEntity reconCase, ProviderOperationStatus providerStatus, String workerId) {
        PaymentEntity payment = paymentRepository.findByIdForUpdate(reconCase.getOperationId()).orElse(null);
        if (payment == null) {
            reconCase.escalateToManualReview(providerStatus.getOutcome().name(), "Payment entity missing", DiscrepancyType.LOCAL_FINANCIAL_STATE_MISSING);
            recordAttempt(reconCase, workerId, providerStatus, DiscrepancyType.LOCAL_FINANCIAL_STATE_MISSING, "INSPECT_PAYMENT", "MANUAL_REVIEW", "Payment missing");
            incrementMetric("reconciliation.manual_review");
            return;
        }

        // Idempotency: re-check if already in terminal state
        if (payment.getStatus() == PaymentStatus.SETTLED) {
            reconCase.recordSuccess(providerStatus.getOutcome().name(), "Payment was already SETTLED", DiscrepancyType.ALREADY_RESOLVED);
            recordAttempt(reconCase, workerId, providerStatus, DiscrepancyType.ALREADY_RESOLVED, "RECONCILE_PAYMENT", "RESOLVED", null);
            incrementMetric("reconciliation.success");
            return;
        }
        if (payment.getStatus() == PaymentStatus.FAILED || payment.getStatus() == PaymentStatus.DECLINED) {
            reconCase.recordSuccess(providerStatus.getOutcome().name(), "Payment was already FAILED/DECLINED", DiscrepancyType.ALREADY_RESOLVED);
            recordAttempt(reconCase, workerId, providerStatus, DiscrepancyType.ALREADY_RESOLVED, "RECONCILE_PAYMENT", "RESOLVED", null);
            incrementMetric("reconciliation.success");
            return;
        }

        if (providerStatus.getOutcome() == ProviderOperationStatus.Outcome.SUCCESS) {
            String ref = providerStatus.getProviderReference() != null ? providerStatus.getProviderReference() : payment.getProviderReference();
            if (ref == null) {
                ref = "prov_rec_" + UUID.randomUUID().toString().replace("-", "");
            }

            // Settle payment with double-entry ledger & outbox atomically
            ledgerService.settlePaymentWithLedger(payment.getId(), ref, reconCase.getCorrelationId());

            reconCase.recordSuccess("SUCCESS", "Payment settled via provider confirmation", DiscrepancyType.PROVIDER_SUCCESS_LOCAL_PENDING);
            recordAttempt(reconCase, workerId, providerStatus, DiscrepancyType.PROVIDER_SUCCESS_LOCAL_PENDING, "SETTLE_PAYMENT", "RESOLVED", null);
            incrementMetric("reconciliation.success");

        } else if (providerStatus.getOutcome() == ProviderOperationStatus.Outcome.FAILED) {
            payment.captureFailed(providerStatus.getErrorCode() != null ? providerStatus.getErrorCode() : "PROVIDER_FAILED");
            paymentRepository.save(payment);

            if (outboxService != null) {
                UUID corrId = parseCorrelationId(reconCase.getCorrelationId());
                PaymentFailedEventPayload payload = new PaymentFailedEventPayload(
                        payment.getId(),
                        payment.getPayerAccountId(),
                        payment.getAmountMinor(),
                        payment.getCurrency(),
                        payment.getFailureReason(),
                        payment.getUpdatedAt()
                );
                outboxService.saveEvent(
                        "PAYMENT",
                        payment.getId().toString(),
                        "PaymentFailed",
                        TopicNames.PAYMENT_EVENTS,
                        payment.getPayerAccountId().toString(),
                        corrId,
                        payment.getId().toString(),
                        payload
                );
            }

            reconCase.recordSuccess("FAILED", "Payment marked failed via provider confirmation", DiscrepancyType.PROVIDER_FAILURE_LOCAL_PENDING);
            recordAttempt(reconCase, workerId, providerStatus, DiscrepancyType.PROVIDER_FAILURE_LOCAL_PENDING, "FAIL_PAYMENT", "RESOLVED", null);
            incrementMetric("reconciliation.failed");

        } else if (providerStatus.getOutcome() == ProviderOperationStatus.Outcome.UNKNOWN) {
            incrementMetric("reconciliation.provider_unknown");
            Duration backoff = computeBackoff(reconCase.getAttemptCount());
            reconCase.recordRetry("UNKNOWN", providerStatus.getMessage(), backoff, DiscrepancyType.PROVIDER_UNKNOWN);
            recordAttempt(reconCase, workerId, providerStatus, DiscrepancyType.PROVIDER_UNKNOWN, "RETRY_SCHEDULED", reconCase.getReconciliationStatus().name(), providerStatus.getMessage());
            incrementMetric(reconCase.getReconciliationStatus() == ReconciliationStatus.MANUAL_REVIEW ? "reconciliation.manual_review" : "reconciliation.retry");

        } else { // PENDING
            Duration backoff = computeBackoff(reconCase.getAttemptCount());
            reconCase.recordRetry("PENDING", "Operation still pending at provider", backoff, DiscrepancyType.PROVIDER_UNKNOWN);
            recordAttempt(reconCase, workerId, providerStatus, DiscrepancyType.PROVIDER_UNKNOWN, "RETRY_SCHEDULED", reconCase.getReconciliationStatus().name(), null);
            incrementMetric("reconciliation.retry");
        }
    }

    private void resolveRefund(ReconciliationCaseEntity reconCase, ProviderOperationStatus providerStatus, String workerId) {
        RefundEntity refund = refundRepository.findByIdForUpdate(reconCase.getOperationId()).orElse(null);
        if (refund == null) {
            reconCase.escalateToManualReview(providerStatus.getOutcome().name(), "Refund entity missing", DiscrepancyType.LOCAL_FINANCIAL_STATE_MISSING);
            recordAttempt(reconCase, workerId, providerStatus, DiscrepancyType.LOCAL_FINANCIAL_STATE_MISSING, "INSPECT_REFUND", "MANUAL_REVIEW", "Refund missing");
            incrementMetric("reconciliation.manual_review");
            return;
        }

        PaymentEntity payment = paymentRepository.findByIdForUpdate(refund.getPaymentId()).orElseThrow();

        if (refund.getStatus() == RefundStatus.SETTLED) {
            reconCase.recordSuccess(providerStatus.getOutcome().name(), "Refund was already SETTLED", DiscrepancyType.ALREADY_RESOLVED);
            recordAttempt(reconCase, workerId, providerStatus, DiscrepancyType.ALREADY_RESOLVED, "RECONCILE_REFUND", "RESOLVED", null);
            incrementMetric("reconciliation.success");
            return;
        }
        if (refund.getStatus() == RefundStatus.FAILED) {
            reconCase.recordSuccess(providerStatus.getOutcome().name(), "Refund was already FAILED", DiscrepancyType.ALREADY_RESOLVED);
            recordAttempt(reconCase, workerId, providerStatus, DiscrepancyType.ALREADY_RESOLVED, "RECONCILE_REFUND", "RESOLVED", null);
            incrementMetric("reconciliation.success");
            return;
        }

        if (providerStatus.getOutcome() == ProviderOperationStatus.Outcome.SUCCESS) {
            String ref = providerStatus.getProviderReference() != null ? providerStatus.getProviderReference() : refund.getProviderReference();
            if (ref == null) {
                ref = "ref_rec_" + UUID.randomUUID().toString().replace("-", "");
            }

            LedgerTransactionEntity tx = ledgerService.settleRefundWithLedger(refund, payment, reconCase.getCorrelationId());
            refund.settle(ref, tx.getId());
            refundRepository.save(refund);

            reconCase.recordSuccess("SUCCESS", "Refund settled via provider confirmation", DiscrepancyType.PROVIDER_SUCCESS_LOCAL_PENDING);
            recordAttempt(reconCase, workerId, providerStatus, DiscrepancyType.PROVIDER_SUCCESS_LOCAL_PENDING, "SETTLE_REFUND", "RESOLVED", null);
            incrementMetric("reconciliation.success");

        } else if (providerStatus.getOutcome() == ProviderOperationStatus.Outcome.FAILED) {
            refund.fail(providerStatus.getErrorCode() != null ? providerStatus.getErrorCode() : "PROVIDER_DECLINED");
            refundRepository.save(refund);

            reconCase.recordSuccess("FAILED", "Refund marked failed via provider confirmation", DiscrepancyType.PROVIDER_FAILURE_LOCAL_PENDING);
            recordAttempt(reconCase, workerId, providerStatus, DiscrepancyType.PROVIDER_FAILURE_LOCAL_PENDING, "FAIL_REFUND", "RESOLVED", null);
            incrementMetric("reconciliation.failed");

        } else if (providerStatus.getOutcome() == ProviderOperationStatus.Outcome.UNKNOWN) {
            incrementMetric("reconciliation.provider_unknown");
            Duration backoff = computeBackoff(reconCase.getAttemptCount());
            reconCase.recordRetry("UNKNOWN", providerStatus.getMessage(), backoff, DiscrepancyType.PROVIDER_UNKNOWN);
            recordAttempt(reconCase, workerId, providerStatus, DiscrepancyType.PROVIDER_UNKNOWN, "RETRY_SCHEDULED", reconCase.getReconciliationStatus().name(), providerStatus.getMessage());
            incrementMetric(reconCase.getReconciliationStatus() == ReconciliationStatus.MANUAL_REVIEW ? "reconciliation.manual_review" : "reconciliation.retry");

        } else {
            Duration backoff = computeBackoff(reconCase.getAttemptCount());
            reconCase.recordRetry("PENDING", "Refund still pending at provider", backoff, DiscrepancyType.PROVIDER_UNKNOWN);
            recordAttempt(reconCase, workerId, providerStatus, DiscrepancyType.PROVIDER_UNKNOWN, "RETRY_SCHEDULED", reconCase.getReconciliationStatus().name(), null);
            incrementMetric("reconciliation.retry");
        }
    }

    private void resolvePayout(ReconciliationCaseEntity reconCase, ProviderOperationStatus providerStatus, String workerId) {
        PayoutEntity payout = payoutRepository.findByIdForUpdate(reconCase.getOperationId()).orElse(null);
        if (payout == null) {
            reconCase.escalateToManualReview(providerStatus.getOutcome().name(), "Payout entity missing", DiscrepancyType.LOCAL_FINANCIAL_STATE_MISSING);
            recordAttempt(reconCase, workerId, providerStatus, DiscrepancyType.LOCAL_FINANCIAL_STATE_MISSING, "INSPECT_PAYOUT", "MANUAL_REVIEW", "Payout missing");
            incrementMetric("reconciliation.manual_review");
            return;
        }

        AccountEntity originAccount = accountRepository.findById(payout.getAccountId()).orElseThrow();
        AccountEntity settlementAccount = accountRepository.findFirstByAccountTypeAndCurrency(AccountType.INTERNAL_SETTLEMENT, payout.getCurrency())
                .orElseGet(() -> {
                    AccountEntity fallback = new AccountEntity("SETTLEMENT-" + UUID.randomUUID().toString().substring(0, 8), originAccount.getOwnerId(), AccountType.INTERNAL_SETTLEMENT, payout.getCurrency(), AccountStatus.ACTIVE);
                    return accountRepository.save(fallback);
                });

        if (payout.getStatus() == PayoutStatus.SETTLED) {
            reconCase.recordSuccess(providerStatus.getOutcome().name(), "Payout was already SETTLED", DiscrepancyType.ALREADY_RESOLVED);
            recordAttempt(reconCase, workerId, providerStatus, DiscrepancyType.ALREADY_RESOLVED, "RECONCILE_PAYOUT", "RESOLVED", null);
            incrementMetric("reconciliation.success");
            return;
        }
        if (payout.getStatus() == PayoutStatus.FAILED) {
            reconCase.recordSuccess(providerStatus.getOutcome().name(), "Payout was already FAILED", DiscrepancyType.ALREADY_RESOLVED);
            recordAttempt(reconCase, workerId, providerStatus, DiscrepancyType.ALREADY_RESOLVED, "RECONCILE_PAYOUT", "RESOLVED", null);
            incrementMetric("reconciliation.success");
            return;
        }

        if (providerStatus.getOutcome() == ProviderOperationStatus.Outcome.SUCCESS) {
            String ref = providerStatus.getProviderReference() != null ? providerStatus.getProviderReference() : payout.getProviderReference();
            if (ref == null) {
                ref = "payout_rec_" + UUID.randomUUID().toString().replace("-", "");
            }

            LedgerTransactionEntity tx = ledgerService.settlePayoutWithLedger(payout, originAccount, settlementAccount, reconCase.getCorrelationId());
            payout.settle(ref, tx.getId());
            payoutRepository.save(payout);

            reconCase.recordSuccess("SUCCESS", "Payout settled via provider confirmation", DiscrepancyType.PROVIDER_SUCCESS_LOCAL_PENDING);
            recordAttempt(reconCase, workerId, providerStatus, DiscrepancyType.PROVIDER_SUCCESS_LOCAL_PENDING, "SETTLE_PAYOUT", "RESOLVED", null);
            incrementMetric("reconciliation.success");

        } else if (providerStatus.getOutcome() == ProviderOperationStatus.Outcome.FAILED) {
            payout.fail(providerStatus.getErrorCode() != null ? providerStatus.getErrorCode() : "PROVIDER_DECLINED");
            payoutRepository.save(payout);

            reconCase.recordSuccess("FAILED", "Payout marked failed via provider confirmation", DiscrepancyType.PROVIDER_FAILURE_LOCAL_PENDING);
            recordAttempt(reconCase, workerId, providerStatus, DiscrepancyType.PROVIDER_FAILURE_LOCAL_PENDING, "FAIL_PAYOUT", "RESOLVED", null);
            incrementMetric("reconciliation.failed");

        } else if (providerStatus.getOutcome() == ProviderOperationStatus.Outcome.UNKNOWN) {
            incrementMetric("reconciliation.provider_unknown");
            Duration backoff = computeBackoff(reconCase.getAttemptCount());
            reconCase.recordRetry("UNKNOWN", providerStatus.getMessage(), backoff, DiscrepancyType.PROVIDER_UNKNOWN);
            recordAttempt(reconCase, workerId, providerStatus, DiscrepancyType.PROVIDER_UNKNOWN, "RETRY_SCHEDULED", reconCase.getReconciliationStatus().name(), providerStatus.getMessage());
            incrementMetric(reconCase.getReconciliationStatus() == ReconciliationStatus.MANUAL_REVIEW ? "reconciliation.manual_review" : "reconciliation.retry");

        } else {
            Duration backoff = computeBackoff(reconCase.getAttemptCount());
            reconCase.recordRetry("PENDING", "Payout still pending at provider", backoff, DiscrepancyType.PROVIDER_UNKNOWN);
            recordAttempt(reconCase, workerId, providerStatus, DiscrepancyType.PROVIDER_UNKNOWN, "RETRY_SCHEDULED", reconCase.getReconciliationStatus().name(), null);
            incrementMetric("reconciliation.retry");
        }
    }

    private void resolveReversal(ReconciliationCaseEntity reconCase, ProviderOperationStatus providerStatus, String workerId) {
        ReversalEntity reversal = reversalRepository.findById(reconCase.getOperationId()).orElse(null);
        if (reversal == null) {
            reconCase.escalateToManualReview(providerStatus.getOutcome().name(), "Reversal entity missing", DiscrepancyType.LOCAL_FINANCIAL_STATE_MISSING);
            recordAttempt(reconCase, workerId, providerStatus, DiscrepancyType.LOCAL_FINANCIAL_STATE_MISSING, "INSPECT_REVERSAL", "MANUAL_REVIEW", "Reversal missing");
            return;
        }

        reconCase.recordSuccess(providerStatus.getOutcome().name(), "Reversal already completed locally", DiscrepancyType.ALREADY_RESOLVED);
        recordAttempt(reconCase, workerId, providerStatus, DiscrepancyType.ALREADY_RESOLVED, "RECONCILE_REVERSAL", "RESOLVED", null);
        incrementMetric("reconciliation.success");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordCaseException(UUID caseId, String workerId, String error, ProviderOperationStatus providerStatus) {
        ReconciliationCaseEntity reconCase = caseRepository.findById(caseId).orElse(null);
        if (reconCase != null) {
            Duration backoff = computeBackoff(reconCase.getAttemptCount());
            reconCase.recordRetry(providerStatus != null ? providerStatus.getOutcome().name() : "ERROR", error, backoff, DiscrepancyType.PROVIDER_UNKNOWN);
            caseRepository.save(reconCase);
            recordAttempt(reconCase, workerId, providerStatus, DiscrepancyType.PROVIDER_UNKNOWN, "TRANSACTION_EXCEPTION", reconCase.getReconciliationStatus().name(), error);
            incrementMetric("reconciliation.retry");
        }
    }

    private void recordAttempt(ReconciliationCaseEntity reconCase,
                               String workerId,
                               ProviderOperationStatus providerStatus,
                               DiscrepancyType discrepancy,
                               String action,
                               String status,
                               String error) {
        ReconciliationAttemptEntity attempt = new ReconciliationAttemptEntity(
                reconCase.getId(),
                reconCase.getAttemptCount() + ("RESOLVED".equals(status) ? 1 : 0),
                workerId,
                providerStatus != null ? providerStatus.getOutcome().name() : null,
                discrepancy,
                action,
                status,
                error
        );
        attemptRepository.save(attempt);
    }

    private Duration computeBackoff(int attemptCount) {
        long seconds = (long) Math.min(60, Math.pow(2, attemptCount));
        return Duration.ofSeconds(Math.max(1, seconds));
    }

    private UUID parseCorrelationId(String correlationId) {
        if (correlationId != null && !correlationId.isBlank()) {
            try {
                return UUID.fromString(correlationId);
            } catch (IllegalArgumentException e) {
                return UUID.nameUUIDFromBytes(correlationId.getBytes(StandardCharsets.UTF_8));
            }
        }
        return UUID.randomUUID();
    }

    private void incrementMetric(String metricName) {
        if (meterRegistry != null) {
            Counter.builder(metricName).register(meterRegistry).increment();
        }
    }
}
