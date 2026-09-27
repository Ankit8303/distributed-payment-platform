package com.paymentledger.admin.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PaymentInvestigationTraceResponse(
        PaymentAdminResponse payment,
        AccountAdminResponse payerAccount,
        AccountAdminResponse payeeAccount,
        LedgerTransactionAdminResponse ledgerTransaction,
        List<OutboxEventSummary> outboxEvents,
        List<KafkaAuditSummary> kafkaAudits,
        List<ReconciliationCaseAdminResponse> reconciliationCases,
        List<NotificationSummary> notifications
) {
    public record OutboxEventSummary(
            UUID eventId,
            String eventType,
            String aggregateType,
            String aggregateId,
            String status,
            String topic,
            Instant createdAt,
            Instant publishedAt
    ) {}

    public record KafkaAuditSummary(
            UUID id,
            UUID eventId,
            String eventType,
            String aggregateId,
            String correlationId,
            Instant createdAt
    ) {}

    public record NotificationSummary(
            UUID id,
            UUID eventId,
            String channel,
            String status,
            int attemptCount,
            Instant nextAttemptAt,
            String recipientRedacted,
            Instant createdAt
    ) {}
}
