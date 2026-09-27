package com.paymentledger.admin.api.dto;

import java.util.UUID;

/**
 * Administrative financial balance comparison for an account.
 * Clearly separates cached/materialized balance from authoritative ledger-derived balance.
 */
public record AccountBalanceSummaryResponse(
        UUID accountId,
        String accountNumber,
        String currency,
        long materializedBalanceMinor,
        long authoritativeLedgerBalanceMinor,
        long differenceMinor,
        boolean isConsistent
) {
}
