package com.paymentledger.admin.api;

import com.paymentledger.admin.api.dto.PageUtils;
import com.paymentledger.admin.api.dto.RefundAdminResponse;
import com.paymentledger.refund.domain.RefundEntity;
import com.paymentledger.refund.domain.RefundStatus;
import com.paymentledger.refund.repository.RefundRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/refunds")
@PreAuthorize("hasAnyRole('ADMIN', 'SYSTEM')")
public class AdminRefundController {

    private final RefundRepository refundRepository;

    public AdminRefundController(RefundRepository refundRepository) {
        this.refundRepository = refundRepository;
    }

    @GetMapping
    public ResponseEntity<Page<RefundAdminResponse>> listRefunds(
            @RequestParam(required = false) UUID paymentId,
            @RequestParam(required = false) RefundStatus status,
            Pageable pageable) {

        Pageable clamped = PageUtils.clamp(pageable);
        Page<RefundEntity> page;

        if (paymentId != null) {
            page = refundRepository.findByPaymentId(paymentId, clamped);
        } else if (status != null) {
            page = refundRepository.findByStatus(status, clamped);
        } else {
            page = refundRepository.findAll(clamped);
        }

        return ResponseEntity.ok(page.map(RefundAdminResponse::fromEntity));
    }

    @GetMapping("/{refundId}")
    public ResponseEntity<RefundAdminResponse> getRefund(@PathVariable UUID refundId) {
        RefundEntity refund = refundRepository.findById(refundId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Refund not found: " + refundId));
        return ResponseEntity.ok(RefundAdminResponse.fromEntity(refund));
    }
}
