package com.paymentledger.admin.api;

import com.paymentledger.admin.api.dto.FinancialAdjustmentCreateRequest;
import com.paymentledger.admin.api.dto.FinancialAdjustmentResponse;
import com.paymentledger.admin.service.FinancialAdjustmentService;
import com.paymentledger.auth.domain.UserEntity;
import com.paymentledger.shared.logging.CorrelationIdFilter;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/adjustments")
public class AdminAdjustmentController {

    private final FinancialAdjustmentService financialAdjustmentService;

    public AdminAdjustmentController(FinancialAdjustmentService financialAdjustmentService) {
        this.financialAdjustmentService = financialAdjustmentService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SYSTEM')")
    public ResponseEntity<FinancialAdjustmentResponse> createAdjustment(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = CorrelationIdFilter.CORRELATION_ID_HEADER, required = false) String correlationId,
            @AuthenticationPrincipal String userIdStr,
            @Valid @RequestBody FinancialAdjustmentCreateRequest request) {

        UUID operatorId = UUID.fromString(userIdStr);
        FinancialAdjustmentResponse response = financialAdjustmentService.postAdjustment(
                operatorId, idempotencyKey, correlationId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{adjustmentId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SYSTEM')")
    public ResponseEntity<FinancialAdjustmentResponse> getAdjustment(
            @PathVariable UUID adjustmentId) {

        FinancialAdjustmentResponse response = financialAdjustmentService.getAdjustment(adjustmentId);
        return ResponseEntity.ok(response);
    }
}
