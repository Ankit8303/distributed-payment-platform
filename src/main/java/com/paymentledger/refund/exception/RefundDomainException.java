package com.paymentledger.refund.exception;

import com.paymentledger.shared.error.ErrorCode;

public class RefundDomainException extends RuntimeException {
    private final ErrorCode errorCode;

    public RefundDomainException(String message) {
        super(message);
        this.errorCode = ErrorCode.INVALID_PAYLOAD;
    }

    public RefundDomainException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
