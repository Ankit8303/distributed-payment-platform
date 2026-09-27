package com.paymentledger.shared.error;

import com.paymentledger.auth.exception.EmailAlreadyExistsException;
import com.paymentledger.auth.exception.InvalidCredentialsException;
import com.paymentledger.auth.exception.InvalidRefreshTokenException;
import com.paymentledger.shared.logging.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import com.paymentledger.account.exception.AccountNotFoundException;
import com.paymentledger.account.exception.AccountDomainException;
import com.paymentledger.account.exception.AccountFrozenException;
import com.paymentledger.payment.exception.PaymentDomainException;
import com.paymentledger.payment.exception.PaymentNotFoundException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Global exception handler providing RFC 7807 problem details error responses.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String ERROR_TYPE_BASE_URL = "https://api.paymentledger.com/errors/";

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        String path = extractPath(request);
        String correlationId = resolveCorrelationId();

        ApiErrorResponse errorResponse = new ApiErrorResponse(
                ERROR_TYPE_BASE_URL + ErrorCode.INVALID_PAYLOAD.name(),
                ErrorCode.INVALID_PAYLOAD.getDefaultTitle(),
                HttpStatus.BAD_REQUEST.value(),
                "Validation failed for one or more fields",
                path,
                ErrorCode.INVALID_PAYLOAD.name(),
                correlationId
        );

        List<ApiErrorResponse.InvalidParameter> invalidParams = new ArrayList<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            invalidParams.add(new ApiErrorResponse.InvalidParameter(
                    fieldError.getField(),
                    fieldError.getDefaultMessage()
            ));
        }
        errorResponse.setInvalidParameters(invalidParams);

        log.warn("Validation error on path {}: {}", path, invalidParams);
        return new ResponseEntity<>(errorResponse, HttpStatus.BAD_REQUEST);
    }

    // --- Phase 3: Security exception handlers ---

    @ExceptionHandler(EmailAlreadyExistsException.class)
    public ResponseEntity<ApiErrorResponse> handleEmailAlreadyExists(
            EmailAlreadyExistsException ex,
            HttpServletRequest request) {

        String correlationId = resolveCorrelationId();
        log.warn("Registration failed - duplicate email on path {}", request.getRequestURI());

        ApiErrorResponse errorResponse = new ApiErrorResponse(
                ERROR_TYPE_BASE_URL + ErrorCode.EMAIL_ALREADY_EXISTS.name(),
                ErrorCode.EMAIL_ALREADY_EXISTS.getDefaultTitle(),
                HttpStatus.CONFLICT.value(),
                ex.getMessage(),
                request.getRequestURI(),
                ErrorCode.EMAIL_ALREADY_EXISTS.name(),
                correlationId
        );

        return new ResponseEntity<>(errorResponse, HttpStatus.CONFLICT);
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidCredentials(
            InvalidCredentialsException ex,
            HttpServletRequest request) {

        String correlationId = resolveCorrelationId();
        log.warn("Authentication failed on path {}", request.getRequestURI());

        ApiErrorResponse errorResponse = new ApiErrorResponse(
                ERROR_TYPE_BASE_URL + ErrorCode.INVALID_CREDENTIALS.name(),
                ErrorCode.INVALID_CREDENTIALS.getDefaultTitle(),
                HttpStatus.UNAUTHORIZED.value(),
                ex.getMessage(),
                request.getRequestURI(),
                ErrorCode.INVALID_CREDENTIALS.name(),
                correlationId
        );

        return new ResponseEntity<>(errorResponse, HttpStatus.UNAUTHORIZED);
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidRefreshToken(
            InvalidRefreshTokenException ex,
            HttpServletRequest request) {

        String correlationId = resolveCorrelationId();
        log.warn("Refresh token rejected on path {}", request.getRequestURI());

        ApiErrorResponse errorResponse = new ApiErrorResponse(
                ERROR_TYPE_BASE_URL + ErrorCode.INVALID_REFRESH_TOKEN.name(),
                ErrorCode.INVALID_REFRESH_TOKEN.getDefaultTitle(),
                HttpStatus.UNAUTHORIZED.value(),
                ex.getMessage(),
                request.getRequestURI(),
                ErrorCode.INVALID_REFRESH_TOKEN.name(),
                correlationId
        );

        return new ResponseEntity<>(errorResponse, HttpStatus.UNAUTHORIZED);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(
            AccessDeniedException ex,
            HttpServletRequest request) {

        String correlationId = resolveCorrelationId();
        log.warn("Authorization denied on path {}", request.getRequestURI());

        ApiErrorResponse errorResponse = new ApiErrorResponse(
                ERROR_TYPE_BASE_URL + ErrorCode.FORBIDDEN.name(),
                ErrorCode.FORBIDDEN.getDefaultTitle(),
                HttpStatus.FORBIDDEN.value(),
                "You do not have permission to access this resource",
                request.getRequestURI(),
                ErrorCode.FORBIDDEN.name(),
                correlationId
        );

        return new ResponseEntity<>(errorResponse, HttpStatus.FORBIDDEN);
    }

    // --- Phase 4: Account Management exception handlers ---

    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleAccountNotFound(
            AccountNotFoundException ex,
            HttpServletRequest request) {

        String correlationId = resolveCorrelationId();
        log.warn("Account not found on path {}", request.getRequestURI());

        ApiErrorResponse errorResponse = new ApiErrorResponse(
                ERROR_TYPE_BASE_URL + ErrorCode.RESOURCE_NOT_FOUND.name(),
                ErrorCode.RESOURCE_NOT_FOUND.getDefaultTitle(),
                HttpStatus.NOT_FOUND.value(),
                ex.getMessage(),
                request.getRequestURI(),
                ErrorCode.RESOURCE_NOT_FOUND.name(),
                correlationId
        );

        return new ResponseEntity<>(errorResponse, HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(AccountDomainException.class)
    public ResponseEntity<ApiErrorResponse> handleAccountDomainException(
            AccountDomainException ex,
            HttpServletRequest request) {

        String correlationId = resolveCorrelationId();
        log.warn("Account domain rule violation on path {}: {}", request.getRequestURI(), ex.getMessage());

        ApiErrorResponse errorResponse = new ApiErrorResponse(
                ERROR_TYPE_BASE_URL + ErrorCode.INVALID_PAYLOAD.name(),
                ErrorCode.INVALID_PAYLOAD.getDefaultTitle(),
                HttpStatus.BAD_REQUEST.value(),
                ex.getMessage(),
                request.getRequestURI(),
                ErrorCode.INVALID_PAYLOAD.name(),
                correlationId
        );

        return new ResponseEntity<>(errorResponse, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(AccountFrozenException.class)
    public ResponseEntity<ApiErrorResponse> handleAccountFrozenException(
            AccountFrozenException ex,
            HttpServletRequest request) {

        String correlationId = resolveCorrelationId();
        log.warn("Account frozen exception on path {}: {}", request.getRequestURI(), ex.getMessage());

        ApiErrorResponse errorResponse = new ApiErrorResponse(
                ERROR_TYPE_BASE_URL + ErrorCode.ACCOUNT_FROZEN.name(),
                ErrorCode.ACCOUNT_FROZEN.getDefaultTitle(),
                HttpStatus.UNPROCESSABLE_ENTITY.value(),
                ex.getMessage(),
                request.getRequestURI(),
                ErrorCode.ACCOUNT_FROZEN.name(),
                correlationId
        );

        return new ResponseEntity<>(errorResponse, HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiErrorResponse> handleOptimisticLockingFailure(
            ObjectOptimisticLockingFailureException ex,
            HttpServletRequest request) {

        String correlationId = resolveCorrelationId();
        log.warn("Optimistic locking failure on path {}", request.getRequestURI());

        ApiErrorResponse errorResponse = new ApiErrorResponse(
                ERROR_TYPE_BASE_URL + "CONFLICT",
                "Concurrent modification detected",
                HttpStatus.CONFLICT.value(),
                "The resource was modified by another request. Please retry.",
                request.getRequestURI(),
                "CONFLICT",
                correlationId
        );

        return new ResponseEntity<>(errorResponse, HttpStatus.CONFLICT);
    }

    // --- Phase 5: Payment Processing exception handlers ---

    @ExceptionHandler(PaymentNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handlePaymentNotFound(
            PaymentNotFoundException ex,
            HttpServletRequest request) {

        String correlationId = resolveCorrelationId();
        log.warn("Payment not found on path {}", request.getRequestURI());

        ApiErrorResponse errorResponse = new ApiErrorResponse(
                ERROR_TYPE_BASE_URL + ErrorCode.RESOURCE_NOT_FOUND.name(),
                ErrorCode.RESOURCE_NOT_FOUND.getDefaultTitle(),
                HttpStatus.NOT_FOUND.value(),
                ex.getMessage(),
                request.getRequestURI(),
                ErrorCode.RESOURCE_NOT_FOUND.name(),
                correlationId
        );

        return new ResponseEntity<>(errorResponse, HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(PaymentDomainException.class)
    public ResponseEntity<ApiErrorResponse> handlePaymentDomainException(
            PaymentDomainException ex,
            HttpServletRequest request) {

        String correlationId = resolveCorrelationId();
        log.warn("Payment domain exception on path {}: {}", request.getRequestURI(), ex.getMessage());

        String errorCodeName = ErrorCode.INVALID_PAYLOAD.name();
        String title = ErrorCode.INVALID_PAYLOAD.getDefaultTitle();
        int httpStatus = HttpStatus.BAD_REQUEST.value();

        if (ex.getMessage().contains("INSUFFICIENT_FUNDS")) {
            errorCodeName = ErrorCode.INSUFFICIENT_FUNDS.name();
            title = ErrorCode.INSUFFICIENT_FUNDS.getDefaultTitle();
            httpStatus = HttpStatus.UNPROCESSABLE_ENTITY.value();
        } else if (ex.getMessage().contains("ACCOUNT_FROZEN")) {
            errorCodeName = ErrorCode.ACCOUNT_FROZEN.name();
            title = ErrorCode.ACCOUNT_FROZEN.getDefaultTitle();
            httpStatus = HttpStatus.UNPROCESSABLE_ENTITY.value();
        } else if (ex.getMessage().contains("TIMEOUT") || ex.getMessage().contains("PENDING_RECONCILIATION")) {
            errorCodeName = ErrorCode.PAYMENT_PENDING_RECONCILIATION.name();
            title = ErrorCode.PAYMENT_PENDING_RECONCILIATION.getDefaultTitle();
            httpStatus = HttpStatus.ACCEPTED.value();
        }

        ApiErrorResponse errorResponse = new ApiErrorResponse(
                ERROR_TYPE_BASE_URL + errorCodeName,
                title,
                httpStatus,
                ex.getMessage(),
                request.getRequestURI(),
                errorCodeName,
                correlationId
        );

        return new ResponseEntity<>(errorResponse, HttpStatus.valueOf(httpStatus));
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleIdempotencyConflictException(
            IdempotencyConflictException ex,
            HttpServletRequest request) {

        String correlationId = resolveCorrelationId();
        log.warn("Idempotency conflict on path {}: {}", request.getRequestURI(), ex.getMessage());

        String errorCodeName = ex.getMessage().contains("MISMATCH") 
                ? ErrorCode.IDEMPOTENCY_KEY_PAYLOAD_MISMATCH.name() 
                : ErrorCode.IDEMPOTENCY_CONCURRENT_REQUEST.name();

        String title = ex.getMessage().contains("MISMATCH") 
                ? ErrorCode.IDEMPOTENCY_KEY_PAYLOAD_MISMATCH.getDefaultTitle() 
                : ErrorCode.IDEMPOTENCY_CONCURRENT_REQUEST.getDefaultTitle();

        ApiErrorResponse errorResponse = new ApiErrorResponse(
                ERROR_TYPE_BASE_URL + errorCodeName,
                title,
                HttpStatus.CONFLICT.value(),
                ex.getMessage(),
                request.getRequestURI(),
                errorCodeName,
                correlationId
        );

        return new ResponseEntity<>(errorResponse, HttpStatus.CONFLICT);
    }

    // --- Phase 10: Redis Auxiliary Infrastructure exception handlers ---

    @ExceptionHandler(com.paymentledger.shared.redis.RateLimitExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleRateLimitExceededException(
            com.paymentledger.shared.redis.RateLimitExceededException ex,
            HttpServletRequest request) {

        String correlationId = resolveCorrelationId();
        log.warn("Rate limit exceeded on path {}: {}", request.getRequestURI(), ex.getMessage());

        String errorCodeName = ErrorCode.RATE_LIMIT_EXCEEDED.name();
        String title = ErrorCode.RATE_LIMIT_EXCEEDED.getDefaultTitle();

        ApiErrorResponse errorResponse = new ApiErrorResponse(
                ERROR_TYPE_BASE_URL + errorCodeName,
                title,
                HttpStatus.TOO_MANY_REQUESTS.value(),
                ex.getMessage(),
                request.getRequestURI(),
                errorCodeName,
                correlationId
        );

        HttpHeaders headers = new HttpHeaders();
        headers.add("Retry-After", String.valueOf(ex.getRetryAfterSeconds()));
        headers.add("X-RateLimit-Limit", String.valueOf(ex.getLimit()));
        headers.add("X-RateLimit-Remaining", "0");
        headers.add("X-RateLimit-Reset", String.valueOf(ex.getRetryAfterSeconds()));

        return new ResponseEntity<>(errorResponse, headers, HttpStatus.TOO_MANY_REQUESTS);
    }

    // --- Phase 11: Financial Operations (Refunds, Reversals, Payouts, Adjustments) exception handlers ---

    @ExceptionHandler(com.paymentledger.refund.exception.RefundNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleRefundNotFound(
            com.paymentledger.refund.exception.RefundNotFoundException ex,
            HttpServletRequest request) {

        String correlationId = resolveCorrelationId();
        log.warn("Refund not found on path {}", request.getRequestURI());

        ApiErrorResponse errorResponse = new ApiErrorResponse(
                ERROR_TYPE_BASE_URL + ErrorCode.RESOURCE_NOT_FOUND.name(),
                ErrorCode.RESOURCE_NOT_FOUND.getDefaultTitle(),
                HttpStatus.NOT_FOUND.value(),
                ex.getMessage(),
                request.getRequestURI(),
                ErrorCode.RESOURCE_NOT_FOUND.name(),
                correlationId
        );

        return new ResponseEntity<>(errorResponse, HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(com.paymentledger.refund.exception.ReversalNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleReversalNotFound(
            com.paymentledger.refund.exception.ReversalNotFoundException ex,
            HttpServletRequest request) {

        String correlationId = resolveCorrelationId();
        log.warn("Reversal not found on path {}", request.getRequestURI());

        ApiErrorResponse errorResponse = new ApiErrorResponse(
                ERROR_TYPE_BASE_URL + ErrorCode.RESOURCE_NOT_FOUND.name(),
                ErrorCode.RESOURCE_NOT_FOUND.getDefaultTitle(),
                HttpStatus.NOT_FOUND.value(),
                ex.getMessage(),
                request.getRequestURI(),
                ErrorCode.RESOURCE_NOT_FOUND.name(),
                correlationId
        );

        return new ResponseEntity<>(errorResponse, HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(com.paymentledger.payout.exception.PayoutNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handlePayoutNotFound(
            com.paymentledger.payout.exception.PayoutNotFoundException ex,
            HttpServletRequest request) {

        String correlationId = resolveCorrelationId();
        log.warn("Payout not found on path {}", request.getRequestURI());

        ApiErrorResponse errorResponse = new ApiErrorResponse(
                ERROR_TYPE_BASE_URL + ErrorCode.RESOURCE_NOT_FOUND.name(),
                ErrorCode.RESOURCE_NOT_FOUND.getDefaultTitle(),
                HttpStatus.NOT_FOUND.value(),
                ex.getMessage(),
                request.getRequestURI(),
                ErrorCode.RESOURCE_NOT_FOUND.name(),
                correlationId
        );

        return new ResponseEntity<>(errorResponse, HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(com.paymentledger.refund.exception.RefundDomainException.class)
    public ResponseEntity<ApiErrorResponse> handleRefundDomainException(
            com.paymentledger.refund.exception.RefundDomainException ex,
            HttpServletRequest request) {

        String correlationId = resolveCorrelationId();
        log.warn("Refund domain exception on path {}: {}", request.getRequestURI(), ex.getMessage());

        ErrorCode code = ex.getErrorCode() != null ? ex.getErrorCode() : ErrorCode.INVALID_PAYLOAD;
        HttpStatus status = code.getHttpStatus() != null ? code.getHttpStatus() : HttpStatus.UNPROCESSABLE_ENTITY;

        ApiErrorResponse errorResponse = new ApiErrorResponse(
                ERROR_TYPE_BASE_URL + code.name(),
                code.getDefaultTitle(),
                status.value(),
                ex.getMessage(),
                request.getRequestURI(),
                code.name(),
                correlationId
        );

        return new ResponseEntity<>(errorResponse, status);
    }

    @ExceptionHandler(com.paymentledger.payout.exception.PayoutDomainException.class)
    public ResponseEntity<ApiErrorResponse> handlePayoutDomainException(
            com.paymentledger.payout.exception.PayoutDomainException ex,
            HttpServletRequest request) {

        String correlationId = resolveCorrelationId();
        log.warn("Payout domain exception on path {}: {}", request.getRequestURI(), ex.getMessage());

        ErrorCode code = ex.getErrorCode() != null ? ex.getErrorCode() : ErrorCode.INVALID_PAYLOAD;
        HttpStatus status = code.getHttpStatus() != null ? code.getHttpStatus() : HttpStatus.UNPROCESSABLE_ENTITY;

        ApiErrorResponse errorResponse = new ApiErrorResponse(
                ERROR_TYPE_BASE_URL + code.name(),
                code.getDefaultTitle(),
                status.value(),
                ex.getMessage(),
                request.getRequestURI(),
                code.name(),
                correlationId
        );

        return new ResponseEntity<>(errorResponse, status);
    }

    // --- Phase 1: Generic exception handlers ---

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiErrorResponse> handleResponseStatusException(
            ResponseStatusException ex,
            HttpServletRequest request) {

        String correlationId = resolveCorrelationId();
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }

        ApiErrorResponse errorResponse = new ApiErrorResponse(
                ERROR_TYPE_BASE_URL + status.name(),
                ex.getReason() != null ? ex.getReason() : status.getReasonPhrase(),
                status.value(),
                ex.getReason() != null ? ex.getReason() : status.getReasonPhrase(),
                request.getRequestURI(),
                status.name(),
                correlationId
        );

        log.warn("HTTP {} on path {}: {}", status.value(), request.getRequestURI(), ex.getMessage());
        return new ResponseEntity<>(errorResponse, status);
    }

    @ExceptionHandler(com.paymentledger.notification.security.SsrfBlockedException.class)
    public ResponseEntity<ApiErrorResponse> handleSsrfBlocked(
            com.paymentledger.notification.security.SsrfBlockedException ex,
            HttpServletRequest request) {

        String correlationId = resolveCorrelationId();
        log.warn("Blocked SSRF attempt on path {}: {}", request.getRequestURI(), ex.getMessage());

        ApiErrorResponse errorResponse = new ApiErrorResponse(
                ERROR_TYPE_BASE_URL + "SSRF_BLOCKED",
                "SSRF Destination Blocked",
                HttpStatus.BAD_REQUEST.value(),
                ex.getMessage(),
                request.getRequestURI(),
                "SSRF_BLOCKED",
                correlationId
        );

        return new ResponseEntity<>(errorResponse, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleGenericException(
            Exception ex,
            HttpServletRequest request) {

        String correlationId = resolveCorrelationId();
        log.error("Unhandled exception processing request [path={}]", request.getRequestURI(), ex);

        ApiErrorResponse errorResponse = new ApiErrorResponse(
                ERROR_TYPE_BASE_URL + ErrorCode.INTERNAL_SERVER_ERROR.name(),
                ErrorCode.INTERNAL_SERVER_ERROR.getDefaultTitle(),
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "An unexpected internal server error occurred",
                request.getRequestURI(),
                ErrorCode.INTERNAL_SERVER_ERROR.name(),
                correlationId
        );

        return new ResponseEntity<>(errorResponse, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private String resolveCorrelationId() {
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        return correlationId;
    }

    private String extractPath(WebRequest request) {
        if (request instanceof ServletWebRequest) {
            ServletWebRequest servletWebRequest = (ServletWebRequest) request;
            return servletWebRequest.getRequest().getRequestURI();
        }
        return "";
    }
}

