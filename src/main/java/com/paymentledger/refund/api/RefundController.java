package com.paymentledger.refund.api;

import com.paymentledger.auth.domain.UserEntity;
import com.paymentledger.refund.api.dto.RefundCreateRequest;
import com.paymentledger.refund.api.dto.RefundResponse;
import com.paymentledger.refund.api.dto.ReversalCreateRequest;
import com.paymentledger.refund.api.dto.ReversalResponse;
import com.paymentledger.refund.service.RefundService;
import com.paymentledger.shared.logging.CorrelationIdFilter;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class RefundController {

    private final RefundService refundService;
    private final FinancialApiRateLimiter financialApiRateLimiter;

    public RefundController(RefundService refundService, FinancialApiRateLimiter financialApiRateLimiter) {
        this.refundService = refundService;
        this.financialApiRateLimiter = financialApiRateLimiter;
    }

    @PostMapping("/payments/{paymentId}/refunds")
    public ResponseEntity<RefundResponse> createRefund(
            @PathVariable UUID paymentId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = CorrelationIdFilter.CORRELATION_ID_HEADER, required = false) String correlationId,
            @AuthenticationPrincipal String userIdStr,
            @Valid @RequestBody RefundCreateRequest request) {

        UUID userId = UUID.fromString(userIdStr);
        financialApiRateLimiter.enforce(FinancialRateLimitOperation.REFUND, userId.toString());
        RefundResponse response = refundService.createRefund(userId, paymentId, idempotencyKey, correlationId, request);
        HttpStatus status = "PENDING_RECONCILIATION".equals(response.status()) ? HttpStatus.ACCEPTED : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(response);
    }

    @GetMapping("/refunds/{refundId}")
    public ResponseEntity<RefundResponse> getRefund(
            @PathVariable UUID refundId,
            @AuthenticationPrincipal String userIdStr) {

        UUID userId = UUID.fromString(userIdStr);
        RefundResponse response = refundService.getRefund(refundId, userId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/payments/{paymentId}/reversal")
    public ResponseEntity<ReversalResponse> createReversal(
            @PathVariable UUID paymentId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = CorrelationIdFilter.CORRELATION_ID_HEADER, required = false) String correlationId,
            @AuthenticationPrincipal String userIdStr,
            @Valid @RequestBody ReversalCreateRequest request) {

        UUID userId = UUID.fromString(userIdStr);
        financialApiRateLimiter.enforce(FinancialRateLimitOperation.REVERSAL, userId.toString());
        ReversalResponse response = refundService.createReversal(userId, paymentId, idempotencyKey, correlationId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/reversals/{reversalId}")
    public ResponseEntity<ReversalResponse> getReversal(
            @PathVariable UUID reversalId,
            @AuthenticationPrincipal String userIdStr) {

        UUID userId = UUID.fromString(userIdStr);
        ReversalResponse response = refundService.getReversal(reversalId, userId);
        return ResponseEntity.ok(response);
    }
}
