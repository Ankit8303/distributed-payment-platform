package com.paymentledger.shared.redis;

/**
 * Financial API operations protected by operation-scoped distributed rate limits.
 */
public enum FinancialRateLimitOperation {
    PAYMENT,
    PAYOUT,
    REFUND,
    REVERSAL
}
