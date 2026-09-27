package com.paymentledger.payout.exception;

import com.paymentledger.shared.error.ErrorCode;

public class PayoutDomainException extends RuntimeException {
    private final ErrorCode errorCode;

    public PayoutDomainException(String message) {
        super(message);
        this.errorCode = ErrorCode.INVALID_PAYLOAD;
    }

    public PayoutDomainException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
