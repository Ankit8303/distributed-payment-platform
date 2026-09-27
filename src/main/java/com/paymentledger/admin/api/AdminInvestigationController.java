package com.paymentledger.admin.api;

import com.paymentledger.account.domain.AccountEntity;
import com.paymentledger.account.repository.AccountRepository;
import com.paymentledger.admin.api.dto.*;
import com.paymentledger.ledger.domain.LedgerEntryEntity;
import com.paymentledger.ledger.domain.LedgerTransactionEntity;
import com.paymentledger.ledger.repository.LedgerEntryRepository;
import com.paymentledger.ledger.repository.LedgerTransactionRepository;
import com.paymentledger.notification.domain.NotificationEntity;
import com.paymentledger.notification.repository.NotificationRepository;
import com.paymentledger.outbox.domain.OutboxEventEntity;
import com.paymentledger.outbox.repository.OutboxEventRepository;
import com.paymentledger.payment.domain.PaymentEntity;
import com.paymentledger.payment.exception.PaymentNotFoundException;
import com.paymentledger.payment.repository.PaymentRepository;
import com.paymentledger.reconciliation.domain.ReconciliationCaseEntity;
import com.paymentledger.reconciliation.domain.ReconciliationOperationType;
import com.paymentledger.reconciliation.repository.ReconciliationCaseRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/investigations")
@PreAuthorize("hasAnyRole('ADMIN', 'SYSTEM')")
public class AdminInvestigationController {

    private final PaymentRepository paymentRepository;
    private final AccountRepository accountRepository;
    private final LedgerTransactionRepository transactionRepository;
    private final LedgerEntryRepository entryRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ReconciliationCaseRepository reconciliationCaseRepository;
    private final NotificationRepository notificationRepository;
    private final JdbcTemplate jdbcTemplate;

    public AdminInvestigationController(PaymentRepository paymentRepository,
                                        AccountRepository accountRepository,
                                        LedgerTransactionRepository transactionRepository,
                                        LedgerEntryRepository entryRepository,
                                        OutboxEventRepository outboxEventRepository,
                                        ReconciliationCaseRepository reconciliationCaseRepository,
                                        NotificationRepository notificationRepository,
                                        JdbcTemplate jdbcTemplate) {
        this.paymentRepository = paymentRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.entryRepository = entryRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.reconciliationCaseRepository = reconciliationCaseRepository;
        this.notificationRepository = notificationRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/payments/{paymentId}")
    public ResponseEntity<PaymentInvestigationTraceResponse> tracePayment(@PathVariable UUID paymentId) {
        PaymentEntity payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException("Payment not found: " + paymentId));

        PaymentAdminResponse paymentResponse = PaymentAdminResponse.fromEntity(payment);

        AccountAdminResponse payerAccount = accountRepository.findById(payment.getPayerAccountId())
                .map(AccountAdminResponse::fromEntity)
                .orElse(null);

        AccountAdminResponse payeeAccount = accountRepository.findById(payment.getPayeeAccountId())
                .map(AccountAdminResponse::fromEntity)
                .orElse(null);

        // Ledger transaction and entries
        LedgerTransactionAdminResponse ledgerTxResponse = null;
        Optional<LedgerTransactionEntity> optTx = transactionRepository.findBySourceReferenceId(paymentId);
        if (optTx.isPresent()) {
            LedgerTransactionEntity tx = optTx.get();
            List<LedgerEntryEntity> entries = entryRepository.findByLedgerTransaction_Id(tx.getId());
            List<LedgerEntryAdminResponse> entryResponses = entries.stream()
                    .map(LedgerEntryAdminResponse::fromEntity)
                    .toList();
            ledgerTxResponse = LedgerTransactionAdminResponse.fromEntity(tx, entryResponses);
        }

        // Outbox events
        List<OutboxEventEntity> outboxEntities = outboxEventRepository.findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc(
                "PAYMENT", paymentId.toString());
        List<PaymentInvestigationTraceResponse.OutboxEventSummary> outboxSummaries = outboxEntities.stream()
                .map(o -> new PaymentInvestigationTraceResponse.OutboxEventSummary(
                        o.getId(),
                        o.getEventType(),
                        o.getAggregateType(),
                        o.getAggregateId(),
                        o.getStatus().name(),
                        o.getTopic(),
                        o.getCreatedAt(),
                        o.getPublishedAt()
                ))
                .toList();

        // Kafka consumer audit records
        List<PaymentInvestigationTraceResponse.KafkaAuditSummary> kafkaAudits = queryKafkaAudits(paymentId.toString());

        // Reconciliation cases
        List<ReconciliationCaseAdminResponse> reconCases = new ArrayList<>();
        Optional<ReconciliationCaseEntity> optCase = reconciliationCaseRepository.findByOperationTypeAndOperationId(
                ReconciliationOperationType.PAYMENT, paymentId);
        optCase.ifPresent(c -> reconCases.add(ReconciliationCaseAdminResponse.fromEntity(c)));

        // Notifications
        List<NotificationEntity> notifs = notificationRepository.findByAggregateId(paymentId.toString());
        List<PaymentInvestigationTraceResponse.NotificationSummary> notifSummaries = notifs.stream()
                .map(n -> new PaymentInvestigationTraceResponse.NotificationSummary(
                        n.getId(),
                        n.getEventId(),
                        n.getChannel().name(),
                        n.getStatus().name(),
                        n.getAttemptCount(),
                        n.getNextAttemptAt(),
                        redactRecipient(n.getRecipient()),
                        n.getCreatedAt()
                ))
                .toList();

        PaymentInvestigationTraceResponse response = new PaymentInvestigationTraceResponse(
                paymentResponse,
                payerAccount,
                payeeAccount,
                ledgerTxResponse,
                outboxSummaries,
                kafkaAudits,
                reconCases,
                notifSummaries
        );

        return ResponseEntity.ok(response);
    }

    private List<PaymentInvestigationTraceResponse.KafkaAuditSummary> queryKafkaAudits(String aggregateId) {
        String sql = "SELECT id, event_id, event_type, aggregate_id, correlation_id, created_at " +
                "FROM payment_event_audits WHERE aggregate_id = ? ORDER BY created_at ASC";
        try {
            return jdbcTemplate.query(sql, (rs, rowNum) -> {
                UUID id = (UUID) rs.getObject("id");
                UUID eventId = (UUID) rs.getObject("event_id");
                String eventType = rs.getString("event_type");
                String aggId = rs.getString("aggregate_id");
                String correlationId = rs.getString("correlation_id");
                Timestamp ts = rs.getTimestamp("created_at");
                Instant createdAt = ts != null ? ts.toInstant() : null;
                return new PaymentInvestigationTraceResponse.KafkaAuditSummary(
                        id, eventId, eventType, aggId, correlationId, createdAt);
            }, aggregateId);
        } catch (Exception ex) {
            return List.of();
        }
    }

    private String redactRecipient(String recipient) {
        if (recipient == null || recipient.isBlank()) {
            return "";
        }
        if (recipient.contains("@")) {
            int atIdx = recipient.indexOf('@');
            if (atIdx > 2) {
                return recipient.substring(0, 2) + "***" + recipient.substring(atIdx);
            }
            return "***" + recipient.substring(atIdx);
        }
        if (recipient.length() > 6) {
            return recipient.substring(0, 3) + "***" + recipient.substring(recipient.length() - 2);
        }
        return "***";
    }
}
