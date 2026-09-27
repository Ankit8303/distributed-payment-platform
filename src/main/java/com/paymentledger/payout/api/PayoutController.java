package com.paymentledger.payout.api;

import com.paymentledger.auth.domain.UserEntity;
import com.paymentledger.payout.api.dto.PayoutCreateRequest;
import com.paymentledger.payout.api.dto.PayoutResponse;
import com.paymentledger.payout.service.PayoutService;
import com.paymentledger.shared.logging.CorrelationIdFilter;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payouts")
public class PayoutController {

    private final PayoutService payoutService;

    public PayoutController(PayoutService payoutService) {
        this.payoutService = payoutService;
    }

    @PostMapping
    public ResponseEntity<PayoutResponse> createPayout(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = CorrelationIdFilter.CORRELATION_ID_HEADER, required = false) String correlationId,
            @AuthenticationPrincipal String userIdStr,
            @Valid @RequestBody PayoutCreateRequest request) {

        UUID userId = UUID.fromString(userIdStr);
        PayoutResponse response = payoutService.createPayout(userId, idempotencyKey, correlationId, request);
        HttpStatus status = "PENDING_RECONCILIATION".equals(response.status()) ? HttpStatus.ACCEPTED : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(response);
    }

    @GetMapping("/{payoutId}")
    public ResponseEntity<PayoutResponse> getPayout(
            @PathVariable UUID payoutId,
            @AuthenticationPrincipal String userIdStr) {

        UUID userId = UUID.fromString(userIdStr);
        PayoutResponse response = payoutService.getPayout(payoutId, userId);
        return ResponseEntity.ok(response);
    }
}
