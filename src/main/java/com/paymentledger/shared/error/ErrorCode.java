package com.paymentledger.shared.error;

import org.springframework.http.HttpStatus;

/**
 * Standard infrastructure, API, and security error codes.
 * <p>
 * Phase 1: Generic infrastructure errors.
 * Phase 3: Authentication and security errors.
 * Domain and business error codes are introduced in their respective feature phases.
 */
public enum ErrorCode {

    // Phase 1 — Generic infrastructure errors
    INVALID_PAYLOAD(HttpStatus.BAD_REQUEST, "Request validation failure or malformed payload"),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "Requested resource does not exist"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "HTTP request method is not supported for this endpoint"),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Service is temporarily unavailable"),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected internal server error occurred"),

    // Phase 3 — Authentication and security errors
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Missing, expired, or invalid authentication"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "Insufficient authorization for the requested resource"),
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "An account with this email already exists"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Invalid email or password"),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "Refresh token is invalid, expired, or revoked"),

    // Phase 4 — Account Management
    ACCOUNT_FROZEN(HttpStatus.UNPROCESSABLE_ENTITY, "Account is administratively frozen; debits are barred"),

    // Phase 5 — Payment Processing
    IDEMPOTENCY_KEY_PAYLOAD_MISMATCH(HttpStatus.CONFLICT, "Idempotency key previously used with different payload"),
    IDEMPOTENCY_CONCURRENT_REQUEST(HttpStatus.CONFLICT, "Identical idempotency key request currently executing"),
    INSUFFICIENT_FUNDS(HttpStatus.UNPROCESSABLE_ENTITY, "Debtor account has insufficient funds for transaction"),
    PROVIDER_UNAVAILABLE(HttpStatus.BAD_GATEWAY, "External gateway unreachable or returned 5xx"),
    PROVIDER_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "External gateway socket timed out; transaction in reconciliation"),
    PAYMENT_PENDING_RECONCILIATION(HttpStatus.ACCEPTED, "Transaction state indeterminate. Reconciliation active."),

    // Phase 10 — Redis Auxiliary Infrastructure
    RATE_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "Request rate limit exceeded. Please retry later."),

    // Phase 11 — Refunds, Reversals, Payouts & Adjustments
    REFUND_AMOUNT_EXCEEDS_PAYMENT(HttpStatus.UNPROCESSABLE_ENTITY, "Refund amount exceeds remaining refundable amount"),
    REFUND_NOT_ELIGIBLE(HttpStatus.BAD_REQUEST, "Payment is not in settled state or ineligible for refund"),
    REVERSAL_ALREADY_EXISTS(HttpStatus.CONFLICT, "Payment has already been reversed"),
    PAYOUT_INSUFFICIENT_FUNDS(HttpStatus.UNPROCESSABLE_ENTITY, "Account has insufficient funds for payout"),
    UNAUTHORIZED_FINANCIAL_OPERATION(HttpStatus.FORBIDDEN, "Unauthorized to perform financial operation on target account");

    private final HttpStatus httpStatus;
    private final String defaultTitle;

    ErrorCode(HttpStatus httpStatus, String defaultTitle) {
        this.httpStatus = httpStatus;
        this.defaultTitle = defaultTitle;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public String getDefaultTitle() {
        return defaultTitle;
    }
}
