package com.paymentledger.admin.api;

import com.paymentledger.admin.api.dto.PageUtils;
import com.paymentledger.admin.api.dto.PaymentAdminResponse;
import com.paymentledger.payment.domain.PaymentEntity;
import com.paymentledger.payment.domain.PaymentStatus;
import com.paymentledger.payment.exception.PaymentNotFoundException;
import com.paymentledger.payment.repository.PaymentRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/payments")
@PreAuthorize("hasAnyRole('ADMIN', 'SYSTEM')")
public class AdminPaymentController {

    private final PaymentRepository paymentRepository;

    public AdminPaymentController(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    @GetMapping
    public ResponseEntity<Page<PaymentAdminResponse>> listPayments(
            @RequestParam(required = false) PaymentStatus status,
            @RequestParam(required = false) UUID payerAccountId,
            @RequestParam(required = false) UUID payeeAccountId,
            Pageable pageable) {

        Pageable clamped = PageUtils.clamp(pageable);
        Page<PaymentEntity> page;

        if (status != null) {
            page = paymentRepository.findByStatus(status, clamped);
        } else if (payerAccountId != null) {
            page = paymentRepository.findByPayerAccountId(payerAccountId, clamped);
        } else if (payeeAccountId != null) {
            page = paymentRepository.findByPayeeAccountId(payeeAccountId, clamped);
        } else {
            page = paymentRepository.findAll(clamped);
        }

        return ResponseEntity.ok(page.map(PaymentAdminResponse::fromEntity));
    }

    @GetMapping("/{paymentId}")
    public ResponseEntity<PaymentAdminResponse> getPayment(@PathVariable UUID paymentId) {
        PaymentEntity payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException("Payment not found: " + paymentId));
        return ResponseEntity.ok(PaymentAdminResponse.fromEntity(payment));
    }
}
