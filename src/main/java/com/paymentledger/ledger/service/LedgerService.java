package com.paymentledger.ledger.service;

import com.paymentledger.account.domain.AccountEntity;
import com.paymentledger.account.domain.AccountStatus;
import com.paymentledger.account.repository.AccountRepository;
import com.paymentledger.ledger.domain.LedgerEntryDirection;
import com.paymentledger.ledger.domain.LedgerEntryEntity;
import com.paymentledger.ledger.domain.LedgerTransactionEntity;
import com.paymentledger.ledger.domain.LedgerTransactionType;
import com.paymentledger.ledger.repository.LedgerEntryRepository;
import com.paymentledger.ledger.repository.LedgerTransactionRepository;
import com.paymentledger.payment.domain.PaymentEntity;
import com.paymentledger.payment.domain.PaymentStatus;
import com.paymentledger.payment.exception.PaymentDomainException;
import com.paymentledger.payment.repository.PaymentRepository;
import com.paymentledger.messaging.config.TopicNames;
import com.paymentledger.messaging.event.PaymentSettledEventPayload;
import com.paymentledger.outbox.service.OutboxService;
import com.paymentledger.shared.metrics.PlatformMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    private final LedgerTransactionRepository ledgerTransactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final AccountRepository accountRepository;
    private final PaymentRepository paymentRepository;
    private final OutboxService outboxService;

    @Autowired(required = false)
    private PlatformMetrics platformMetrics;

    public LedgerService(LedgerTransactionRepository ledgerTransactionRepository,
                         LedgerEntryRepository ledgerEntryRepository,
                         AccountRepository accountRepository,
                         PaymentRepository paymentRepository) {
        this(ledgerTransactionRepository, ledgerEntryRepository, accountRepository, paymentRepository, null);
    }

    @Autowired
    public LedgerService(LedgerTransactionRepository ledgerTransactionRepository,
                         LedgerEntryRepository ledgerEntryRepository,
                         AccountRepository accountRepository,
                         PaymentRepository paymentRepository,
                         @Autowired(required = false) OutboxService outboxService) {
        this.ledgerTransactionRepository = ledgerTransactionRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.accountRepository = accountRepository;
        this.paymentRepository = paymentRepository;
        this.outboxService = outboxService;
    }

    /** Package-private setter for testing. */
    void setPlatformMetrics(PlatformMetrics platformMetrics) {
        this.platformMetrics = platformMetrics;
    }

    /**
     * Settles a payment and posts the corresponding double-entry ledger transaction
     * in ONE atomic database transaction.
     *
     * <p>The caller MUST invoke this AFTER the external provider capture has succeeded
     * and OUTSIDE any enclosing database transaction, so that:
     * <ul>
     *   <li>Provider capture (external side-effect) is not inside a DB transaction</li>
     *   <li>Payment SETTLED + Ledger POSTED are committed together atomically</li>
     * </ul>
     *
     * <p>If this transaction fails (constraint violation, insufficient funds, etc.),
     * the entire DB transaction rolls back: no ledger entries, no materialized balance
     * changes, and the payment remains in CAPTURING state. The caller is responsible
     * for transitioning the payment to PENDING_RECONCILIATION via a separate
     * recovery transaction.
     *
     * @param paymentId        the ID of the payment to settle (must be in CAPTURING state)
     * @param providerReference the capture reference from the external provider
     * @return the posted LedgerTransactionEntity
     */
    @Transactional
    public LedgerTransactionEntity settlePaymentWithLedger(UUID paymentId, String providerReference) {
        return settlePaymentWithLedger(paymentId, providerReference, null);
    }

    @Transactional
    public LedgerTransactionEntity settlePaymentWithLedger(UUID paymentId, String providerReference, String correlationId) {
        // Re-load the payment inside THIS transaction so it becomes a managed entity
        PaymentEntity payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalStateException("Payment not found: " + paymentId));

        if (payment.getStatus() != PaymentStatus.CAPTURING && payment.getStatus() != PaymentStatus.PENDING_RECONCILIATION) {
            throw new IllegalStateException("Payment must be in CAPTURING or PENDING_RECONCILIATION state to settle, was: " + payment.getStatus());
        }

        // Duplicate ledger posting guard (application-level optimization;
        // the DB unique constraint on (source_reference_type, source_reference_id) is authoritative)
        if (ledgerTransactionRepository.existsBySourceReferenceIdAndSourceReferenceType(payment.getId(), "PAYMENT")) {
            throw new IllegalStateException("Duplicate ledger posting detected for payment " + payment.getId());
        }

        UUID payerId = payment.getPayerAccountId();
        UUID payeeId = payment.getPayeeAccountId();

        AccountEntity payerAccount;
        AccountEntity payeeAccount;

        // Deterministic locking to avoid deadlocks
        if (payerId.compareTo(payeeId) < 0) {
            payerAccount = accountRepository.findByIdForUpdate(payerId).orElseThrow();
            payeeAccount = accountRepository.findByIdForUpdate(payeeId).orElseThrow();
        } else {
            payeeAccount = accountRepository.findByIdForUpdate(payeeId).orElseThrow();
            payerAccount = accountRepository.findByIdForUpdate(payerId).orElseThrow();
        }

        if (payerAccount.getStatus() != AccountStatus.ACTIVE) {
            throw new PaymentDomainException("ACCOUNT_FROZEN");
        }

        if (!payerAccount.getCurrency().equals(payment.getCurrency()) || !payeeAccount.getCurrency().equals(payment.getCurrency())) {
            throw new PaymentDomainException("Currency mismatch");
        }

        // Authoritative balance check: ledger_entries SUM is the source of truth
        if (payerAccount.getAccountType().name().equals("CUSTOMER")) {
            long authoritativeBalance = ledgerEntryRepository.calculateLedgerBalanceMinor(payerId);
            if (authoritativeBalance < payment.getAmountMinor()) {
                try {
                    if (platformMetrics != null) {
                        platformMetrics.recordLedgerBalanceCheck(payerAccount.getAccountType().name(), false);
                    }
                } catch (Exception ex) {
                    log.warn("Metric recording failed (ledger.balance.check insufficient): {}", ex.getMessage());
                }
                throw new PaymentDomainException("INSUFFICIENT_FUNDS");
            }
            try {
                if (platformMetrics != null) {
                    platformMetrics.recordLedgerBalanceCheck(payerAccount.getAccountType().name(), true);
                }
            } catch (Exception ex) {
                log.warn("Metric recording failed (ledger.balance.check sufficient): {}", ex.getMessage());
            }
        }

        long payerSeq = ledgerEntryRepository.getMaxSequenceNumberForAccount(payerId) + 1;
        long payeeSeq = ledgerEntryRepository.getMaxSequenceNumberForAccount(payeeId) + 1;

        LedgerTransactionEntity tx = new LedgerTransactionEntity(
                LedgerTransactionType.PAYMENT,
                payment.getId(),
                "PAYMENT",
                payment.getCurrency(),
                "Payment Settlement"
        );

        LedgerEntryEntity debit = new LedgerEntryEntity(payerId, LedgerEntryDirection.DEBIT, payment.getAmountMinor(), payment.getCurrency(), payerSeq);
        LedgerEntryEntity credit = new LedgerEntryEntity(payeeId, LedgerEntryDirection.CREDIT, payment.getAmountMinor(), payment.getCurrency(), payeeSeq);

        tx.addEntry(debit);
        tx.addEntry(credit);

        tx.post();

        ledgerTransactionRepository.save(tx);

        // Phase 16: Record ledger transaction posted metric (fail-safe)
        try {
            if (platformMetrics != null) {
                platformMetrics.recordLedgerTransactionPosted("PAYMENT", payment.getCurrency());
            }
        } catch (Exception ex) {
            log.warn("Metric recording failed (ledger.transaction.posted PAYMENT): {}", ex.getMessage());
        }

        // Update materialized balances (derived state, not authoritative)
        payerAccount.subtractBalanceMinor(payment.getAmountMinor());
        payeeAccount.addBalanceMinor(payment.getAmountMinor());

        accountRepository.save(payerAccount);
        accountRepository.save(payeeAccount);

        // Transition payment to SETTLED in the SAME transaction as ledger posting
        payment.captureSucceeded(providerReference);
        paymentRepository.save(payment);

        // Phase 9: Persist PaymentSettled outbox event in the SAME transaction
        if (outboxService != null) {
            UUID corrId;
            if (correlationId != null && !correlationId.isBlank()) {
                try {
                    corrId = UUID.fromString(correlationId);
                } catch (IllegalArgumentException e) {
                    corrId = UUID.nameUUIDFromBytes(correlationId.getBytes(StandardCharsets.UTF_8));
                }
            } else {
                corrId = UUID.randomUUID();
            }

            PaymentSettledEventPayload payload = new PaymentSettledEventPayload(
                    payment.getId(),
                    payment.getPayerAccountId(),
                    payment.getPayeeAccountId(),
                    payment.getAmountMinor(),
                    payment.getFeeAmountMinor(),
                    payment.getCurrency(),
                    tx.getId(),
                    payment.getProviderReference(),
                    payment.getUpdatedAt()
            );

            outboxService.saveEvent(
                    "PAYMENT",
                    payment.getId().toString(),
                    "PaymentSettled",
                    TopicNames.PAYMENT_EVENTS,
                    payment.getPayerAccountId().toString(),
                    corrId,
                    payment.getId().toString(),
                    payload
            );
        }

        return tx;
    }

    /**
     * Settle a refund and post compensating ledger entries atomically.
     * Merchant account (payee of original payment) is DEBITED.
     * Payer account (payer of original payment) is CREDITED.
     */
    @Transactional
    public LedgerTransactionEntity settleRefundWithLedger(com.paymentledger.refund.domain.RefundEntity refund,
                                                          PaymentEntity payment,
                                                          String correlationId) {
        if (ledgerTransactionRepository.existsBySourceReferenceIdAndSourceReferenceType(refund.getId(), "REFUND")) {
            throw new IllegalStateException("Duplicate ledger posting detected for refund " + refund.getId());
        }

        UUID payerAccountId = payment.getPayerAccountId();
        UUID payeeAccountId = payment.getPayeeAccountId();

        AccountEntity payerAccount;
        AccountEntity payeeAccount;

        // Deterministic lock acquisition order
        if (payerAccountId.compareTo(payeeAccountId) < 0) {
            payerAccount = accountRepository.findByIdForUpdate(payerAccountId).orElseThrow();
            payeeAccount = accountRepository.findByIdForUpdate(payeeAccountId).orElseThrow();
        } else {
            payeeAccount = accountRepository.findByIdForUpdate(payeeAccountId).orElseThrow();
            payerAccount = accountRepository.findByIdForUpdate(payerAccountId).orElseThrow();
        }

        if (payeeAccount.getStatus() != AccountStatus.ACTIVE) {
            throw new com.paymentledger.refund.exception.RefundDomainException(
                    com.paymentledger.shared.error.ErrorCode.ACCOUNT_FROZEN, "Merchant account is frozen");
        }

        // Authoritative ledger balance check for merchant
        long authoritativeMerchantBalance = ledgerEntryRepository.calculateLedgerBalanceMinor(payeeAccountId);
        if (authoritativeMerchantBalance < refund.getAmountMinor()) {
            throw new com.paymentledger.refund.exception.RefundDomainException(
                    com.paymentledger.shared.error.ErrorCode.INSUFFICIENT_FUNDS, "Merchant has insufficient ledger funds for refund");
        }

        long payeeSeq = ledgerEntryRepository.getMaxSequenceNumberForAccount(payeeAccountId) + 1;
        long payerSeq = ledgerEntryRepository.getMaxSequenceNumberForAccount(payerAccountId) + 1;

        LedgerTransactionEntity tx = new LedgerTransactionEntity(
                LedgerTransactionType.REFUND,
                refund.getId(),
                "REFUND",
                refund.getCurrency(),
                "Refund for payment " + payment.getId()
        );

        // Compensating entries: Merchant DEBIT, Payer CREDIT
        LedgerEntryEntity debitMerchant = new LedgerEntryEntity(payeeAccountId, LedgerEntryDirection.DEBIT, refund.getAmountMinor(), refund.getCurrency(), payeeSeq);
        LedgerEntryEntity creditPayer = new LedgerEntryEntity(payerAccountId, LedgerEntryDirection.CREDIT, refund.getAmountMinor(), refund.getCurrency(), payerSeq);

        tx.addEntry(debitMerchant);
        tx.addEntry(creditPayer);
        tx.post();

        ledgerTransactionRepository.save(tx);

        // Phase 16: Record ledger transaction posted metric (fail-safe)
        try {
            if (platformMetrics != null) {
                platformMetrics.recordLedgerTransactionPosted("REFUND", refund.getCurrency());
            }
        } catch (Exception ex) {
            log.warn("Metric recording failed (ledger.transaction.posted REFUND): {}", ex.getMessage());
        }

        payeeAccount.subtractBalanceMinor(refund.getAmountMinor());
        payerAccount.addBalanceMinor(refund.getAmountMinor());

        accountRepository.save(payeeAccount);
        accountRepository.save(payerAccount);

        if (outboxService != null) {
            UUID corrId = parseCorrelationId(correlationId);
            com.paymentledger.messaging.event.RefundSettledEventPayload payload = new com.paymentledger.messaging.event.RefundSettledEventPayload(
                    refund.getId(),
                    payment.getId(),
                    payerAccountId,
                    payeeAccountId,
                    refund.getAmountMinor(),
                    refund.getCurrency(),
                    tx.getId(),
                    refund.getProviderReference(),
                    refund.getUpdatedAt() != null ? refund.getUpdatedAt() : java.time.Instant.now()
            );

            outboxService.saveEvent(
                    "REFUND",
                    refund.getId().toString(),
                    "RefundSettled",
                    TopicNames.REFUND_EVENTS,
                    payeeAccountId.toString(),
                    corrId,
                    refund.getId().toString(),
                    payload
            );
        }

        return tx;
    }

    /**
     * Settle a reversal and post compensating ledger entries atomically.
     * Payee account is DEBITED for full payment amount.
     * Payer account is CREDITED for full payment amount.
     */
    @Transactional
    public LedgerTransactionEntity settleReversalWithLedger(com.paymentledger.refund.domain.ReversalEntity reversal,
                                                            PaymentEntity payment,
                                                            String correlationId) {
        if (ledgerTransactionRepository.existsBySourceReferenceIdAndSourceReferenceType(reversal.getId(), "REVERSAL")) {
            throw new IllegalStateException("Duplicate ledger posting detected for reversal " + reversal.getId());
        }

        UUID payerAccountId = payment.getPayerAccountId();
        UUID payeeAccountId = payment.getPayeeAccountId();

        AccountEntity payerAccount;
        AccountEntity payeeAccount;

        if (payerAccountId.compareTo(payeeAccountId) < 0) {
            payerAccount = accountRepository.findByIdForUpdate(payerAccountId).orElseThrow();
            payeeAccount = accountRepository.findByIdForUpdate(payeeAccountId).orElseThrow();
        } else {
            payeeAccount = accountRepository.findByIdForUpdate(payeeAccountId).orElseThrow();
            payerAccount = accountRepository.findByIdForUpdate(payerAccountId).orElseThrow();
        }

        long payeeSeq = ledgerEntryRepository.getMaxSequenceNumberForAccount(payeeAccountId) + 1;
        long payerSeq = ledgerEntryRepository.getMaxSequenceNumberForAccount(payerAccountId) + 1;

        LedgerTransactionEntity tx = new LedgerTransactionEntity(
                LedgerTransactionType.SYSTEM_ADJUSTMENT,
                reversal.getId(),
                "REVERSAL",
                reversal.getCurrency(),
                "Reversal for payment " + payment.getId() + ": " + reversal.getReason()
        );

        LedgerEntryEntity debitPayee = new LedgerEntryEntity(payeeAccountId, LedgerEntryDirection.DEBIT, reversal.getAmountMinor(), reversal.getCurrency(), payeeSeq);
        LedgerEntryEntity creditPayer = new LedgerEntryEntity(payerAccountId, LedgerEntryDirection.CREDIT, reversal.getAmountMinor(), reversal.getCurrency(), payerSeq);

        tx.addEntry(debitPayee);
        tx.addEntry(creditPayer);
        tx.post();

        ledgerTransactionRepository.save(tx);

        // Phase 16: Record ledger transaction posted metric (fail-safe)
        try {
            if (platformMetrics != null) {
                platformMetrics.recordLedgerTransactionPosted("REVERSAL", reversal.getCurrency());
            }
        } catch (Exception ex) {
            log.warn("Metric recording failed (ledger.transaction.posted REVERSAL): {}", ex.getMessage());
        }

        payeeAccount.subtractBalanceMinor(reversal.getAmountMinor());
        payerAccount.addBalanceMinor(reversal.getAmountMinor());

        accountRepository.save(payeeAccount);
        accountRepository.save(payerAccount);

        if (outboxService != null) {
            UUID corrId = parseCorrelationId(correlationId);
            com.paymentledger.messaging.event.ReversalSettledEventPayload payload = new com.paymentledger.messaging.event.ReversalSettledEventPayload(
                    reversal.getId(),
                    payment.getId(),
                    payerAccountId,
                    payeeAccountId,
                    reversal.getAmountMinor(),
                    reversal.getCurrency(),
                    tx.getId(),
                    reversal.getReason(),
                    reversal.getUpdatedAt() != null ? reversal.getUpdatedAt() : java.time.Instant.now()
            );

            outboxService.saveEvent(
                    "REVERSAL",
                    reversal.getId().toString(),
                    "ReversalSettled",
                    TopicNames.PAYMENT_EVENTS,
                    payment.getPayerAccountId().toString(),
                    corrId,
                    reversal.getId().toString(),
                    payload
            );
        }

        return tx;
    }

    /**
     * Settle a payout and post double-entry ledger entries atomically.
     * Origin account (Merchant/Customer) is DEBITED.
     * Settlement account (Internal Clearing) is CREDITED.
     */
    @Transactional
    public LedgerTransactionEntity settlePayoutWithLedger(com.paymentledger.payout.domain.PayoutEntity payout,
                                                          AccountEntity originAccount,
                                                          AccountEntity settlementAccount,
                                                          String correlationId) {
        if (ledgerTransactionRepository.existsBySourceReferenceIdAndSourceReferenceType(payout.getId(), "PAYOUT")) {
            throw new IllegalStateException("Duplicate ledger posting detected for payout " + payout.getId());
        }

        UUID originId = originAccount.getId();
        UUID settlementId = settlementAccount.getId();

        AccountEntity lockedOrigin;
        AccountEntity lockedSettlement;

        if (originId.compareTo(settlementId) < 0) {
            lockedOrigin = accountRepository.findByIdForUpdate(originId).orElseThrow();
            lockedSettlement = accountRepository.findByIdForUpdate(settlementId).orElseThrow();
        } else {
            lockedSettlement = accountRepository.findByIdForUpdate(settlementId).orElseThrow();
            lockedOrigin = accountRepository.findByIdForUpdate(originId).orElseThrow();
        }

        if (lockedOrigin.getStatus() != AccountStatus.ACTIVE) {
            throw new com.paymentledger.payout.exception.PayoutDomainException(
                    com.paymentledger.shared.error.ErrorCode.ACCOUNT_FROZEN, "Account is frozen");
        }

        long authoritativeBalance = ledgerEntryRepository.calculateLedgerBalanceMinor(originId);
        if (authoritativeBalance < payout.getAmountMinor()) {
            throw new com.paymentledger.payout.exception.PayoutDomainException(
                    com.paymentledger.shared.error.ErrorCode.PAYOUT_INSUFFICIENT_FUNDS, "Insufficient ledger balance for payout");
        }

        long originSeq = ledgerEntryRepository.getMaxSequenceNumberForAccount(originId) + 1;
        long settlementSeq = ledgerEntryRepository.getMaxSequenceNumberForAccount(settlementId) + 1;

        LedgerTransactionEntity tx = new LedgerTransactionEntity(
                LedgerTransactionType.PAYOUT,
                payout.getId(),
                "PAYOUT",
                payout.getCurrency(),
                "Payout to external bank for account " + originId
        );

        LedgerEntryEntity debitOrigin = new LedgerEntryEntity(originId, LedgerEntryDirection.DEBIT, payout.getAmountMinor(), payout.getCurrency(), originSeq);
        LedgerEntryEntity creditSettlement = new LedgerEntryEntity(settlementId, LedgerEntryDirection.CREDIT, payout.getAmountMinor(), payout.getCurrency(), settlementSeq);

        tx.addEntry(debitOrigin);
        tx.addEntry(creditSettlement);
        tx.post();

        ledgerTransactionRepository.save(tx);

        // Phase 16: Record ledger transaction posted metric (fail-safe)
        try {
            if (platformMetrics != null) {
                platformMetrics.recordLedgerTransactionPosted("PAYOUT", payout.getCurrency());
            }
        } catch (Exception ex) {
            log.warn("Metric recording failed (ledger.transaction.posted PAYOUT): {}", ex.getMessage());
        }

        lockedOrigin.subtractBalanceMinor(payout.getAmountMinor());
        lockedSettlement.addBalanceMinor(payout.getAmountMinor());

        accountRepository.save(lockedOrigin);
        accountRepository.save(lockedSettlement);

        if (outboxService != null) {
            UUID corrId = parseCorrelationId(correlationId);
            com.paymentledger.messaging.event.PayoutSettledEventPayload payload = new com.paymentledger.messaging.event.PayoutSettledEventPayload(
                    payout.getId(),
                    originId,
                    payout.getAmountMinor(),
                    payout.getCurrency(),
                    tx.getId(),
                    payout.getProviderReference(),
                    payout.getUpdatedAt() != null ? payout.getUpdatedAt() : java.time.Instant.now()
            );

            outboxService.saveEvent(
                    "PAYOUT",
                    payout.getId().toString(),
                    "PayoutSettled",
                    TopicNames.PAYMENT_EVENTS,
                    originId.toString(),
                    corrId,
                    payout.getId().toString(),
                    payload
            );
        }

        return tx;
    }

    /**
     * Post a controlled administrative financial adjustment atomically.
     * Source account is DEBITED.
     * Target account is CREDITED.
     */
    @Transactional
    public LedgerTransactionEntity postAdjustmentWithLedger(UUID adjustmentId,
                                                            UUID sourceAccountId,
                                                            UUID targetAccountId,
                                                            long amountMinor,
                                                            String currency,
                                                            String reason,
                                                            UUID operatorId,
                                                            String correlationId) {
        AccountEntity sourceAccount;
        AccountEntity targetAccount;

        if (sourceAccountId.compareTo(targetAccountId) < 0) {
            sourceAccount = accountRepository.findByIdForUpdate(sourceAccountId)
                    .orElseThrow(() -> new IllegalArgumentException("Source account not found: " + sourceAccountId));
            targetAccount = accountRepository.findByIdForUpdate(targetAccountId)
                    .orElseThrow(() -> new IllegalArgumentException("Target account not found: " + targetAccountId));
        } else {
            targetAccount = accountRepository.findByIdForUpdate(targetAccountId)
                    .orElseThrow(() -> new IllegalArgumentException("Target account not found: " + targetAccountId));
            sourceAccount = accountRepository.findByIdForUpdate(sourceAccountId)
                    .orElseThrow(() -> new IllegalArgumentException("Source account not found: " + sourceAccountId));
        }

        if (!sourceAccount.getCurrency().equals(currency) || !targetAccount.getCurrency().equals(currency)) {
            throw new IllegalArgumentException("Account currency does not match adjustment currency");
        }

        long sourceSeq = ledgerEntryRepository.getMaxSequenceNumberForAccount(sourceAccountId) + 1;
        long targetSeq = ledgerEntryRepository.getMaxSequenceNumberForAccount(targetAccountId) + 1;

        LedgerTransactionEntity tx = new LedgerTransactionEntity(
                LedgerTransactionType.SYSTEM_ADJUSTMENT,
                adjustmentId,
                "ADMIN_ADJUSTMENT",
                currency,
                "Admin adjustment: " + reason
        );

        LedgerEntryEntity debitSource = new LedgerEntryEntity(sourceAccountId, LedgerEntryDirection.DEBIT, amountMinor, currency, sourceSeq);
        LedgerEntryEntity creditTarget = new LedgerEntryEntity(targetAccountId, LedgerEntryDirection.CREDIT, amountMinor, currency, targetSeq);

        tx.addEntry(debitSource);
        tx.addEntry(creditTarget);
        tx.post();

        ledgerTransactionRepository.save(tx);

        // Phase 16: Record ledger transaction posted metric (fail-safe)
        try {
            if (platformMetrics != null) {
                platformMetrics.recordLedgerTransactionPosted("ADMIN_ADJUSTMENT", currency);
            }
        } catch (Exception ex) {
            log.warn("Metric recording failed (ledger.transaction.posted ADMIN_ADJUSTMENT): {}", ex.getMessage());
        }

        sourceAccount.subtractBalanceMinor(amountMinor);
        targetAccount.addBalanceMinor(amountMinor);

        accountRepository.save(sourceAccount);
        accountRepository.save(targetAccount);

        if (outboxService != null) {
            UUID corrId = parseCorrelationId(correlationId);
            com.paymentledger.messaging.event.FinancialAdjustmentPostedEventPayload payload = new com.paymentledger.messaging.event.FinancialAdjustmentPostedEventPayload(
                    adjustmentId,
                    sourceAccountId,
                    targetAccountId,
                    amountMinor,
                    currency,
                    operatorId,
                    tx.getId(),
                    reason,
                    java.time.Instant.now()
            );

            outboxService.saveEvent(
                    "FINANCIAL_ADJUSTMENT",
                    adjustmentId.toString(),
                    "FinancialAdjustmentPosted",
                    TopicNames.PAYMENT_EVENTS,
                    sourceAccountId.toString(),
                    corrId,
                    adjustmentId.toString(),
                    payload
            );
        }

        return tx;
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
}
