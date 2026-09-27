package com.paymentledger.admin.api.dto;

public record DashboardSummaryResponse(
        long totalUsers,
        long totalAccounts,
        long activeAccounts,
        long frozenAccounts,
        long totalPayments,
        long settledPayments,
        long failedPayments,
        long pendingReconciliationPayments,
        long openReconciliationCases,
        long totalNotifications
) {
}
