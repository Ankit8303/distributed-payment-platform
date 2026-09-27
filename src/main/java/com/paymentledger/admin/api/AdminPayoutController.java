package com.paymentledger.admin.api;

import com.paymentledger.admin.api.dto.PageUtils;
import com.paymentledger.admin.api.dto.PayoutAdminResponse;
import com.paymentledger.payout.domain.PayoutEntity;
import com.paymentledger.payout.domain.PayoutStatus;
import com.paymentledger.payout.repository.PayoutRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/payouts")
@PreAuthorize("hasAnyRole('ADMIN', 'SYSTEM')")
public class AdminPayoutController {

    private final PayoutRepository payoutRepository;

    public AdminPayoutController(PayoutRepository payoutRepository) {
        this.payoutRepository = payoutRepository;
    }

    @GetMapping
    public ResponseEntity<Page<PayoutAdminResponse>> listPayouts(
            @RequestParam(required = false) UUID accountId,
            @RequestParam(required = false) PayoutStatus status,
            Pageable pageable) {

        Pageable clamped = PageUtils.clamp(pageable);
        Page<PayoutEntity> page;

        if (accountId != null) {
            page = payoutRepository.findByAccountId(accountId, clamped);
        } else if (status != null) {
            page = payoutRepository.findByStatus(status, clamped);
        } else {
            page = payoutRepository.findAll(clamped);
        }

        return ResponseEntity.ok(page.map(PayoutAdminResponse::fromEntity));
    }

    @GetMapping("/{payoutId}")
    public ResponseEntity<PayoutAdminResponse> getPayout(@PathVariable UUID payoutId) {
        PayoutEntity payout = payoutRepository.findById(payoutId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payout not found: " + payoutId));
        return ResponseEntity.ok(PayoutAdminResponse.fromEntity(payout));
    }
}
