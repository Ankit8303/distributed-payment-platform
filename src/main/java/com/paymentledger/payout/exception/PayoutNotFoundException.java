package com.paymentledger.payout.exception;

import java.util.UUID;

public class PayoutNotFoundException extends RuntimeException {
    public PayoutNotFoundException(UUID payoutId) {
        super("Payout not found: " + payoutId);
    }

    public PayoutNotFoundException(String message) {
        super(message);
    }
}
