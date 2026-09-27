package com.paymentledger.payment.api;


import com.paymentledger.payment.api.dto.PaymentCreateRequest;
import com.paymentledger.shared.redis.FinancialApiRateLimiter;
import com.paymentledger.shared.redis.FinancialRateLimitOperation;
import com.paymentledger.payment.api.dto.PaymentResponse;
import com.paymentledger.payment.service.PaymentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import com.paymentledger.account.repository.AccountRepository;
import com.paymentledger.account.domain.AccountEntity;
import org.springframework.data.domain.PageRequest;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentService paymentService;
    private final AccountRepository accountRepository;
    private final FinancialApiRateLimiter financialApiRateLimiter;

    public PaymentController(PaymentService paymentService, AccountRepository accountRepository,
                              FinancialApiRateLimiter financialApiRateLimiter) {
        this.paymentService = paymentService;
        this.accountRepository = accountRepository;
        this.financialApiRateLimiter = financialApiRateLimiter;
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> createPayment(
            @AuthenticationPrincipal String userIdStr,
            @RequestHeader(value = "Idempotency-Key", required = true) String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @Valid @RequestBody PaymentCreateRequest request) {
        
        UUID userId = UUID.fromString(userIdStr);
        financialApiRateLimiter.enforce(FinancialRateLimitOperation.PAYMENT, userId.toString());
        UUID payerAccountId = accountRepository.findByOwnerId(userId, PageRequest.of(0, 10))
                .stream()
                .filter(acc -> acc.getAccountType().name().equals("CUSTOMER") || acc.getAccountType().name().equals("MERCHANT"))
                .findFirst()
                .map(AccountEntity::getId)
                .orElseThrow(() -> new com.paymentledger.payment.exception.PaymentDomainException("No operational account found for user"));

        PaymentResponse response = paymentService.createPayment(
                userId,
                payerAccountId,
                idempotencyKey,
                correlationId,
                request
        );

        if ("PENDING_RECONCILIATION".equals(response.getStatus())) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<PaymentResponse> getPayment(
            @AuthenticationPrincipal String userIdStr,
            @PathVariable UUID id) {
        
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String role = auth.getAuthorities().stream().findFirst().map(a -> a.getAuthority().replace("ROLE_", "")).orElse("");

        PaymentResponse response = paymentService.getPayment(
                id, 
                UUID.fromString(userIdStr), 
                role
        );
        
        return ResponseEntity.ok(response);
    }
}
