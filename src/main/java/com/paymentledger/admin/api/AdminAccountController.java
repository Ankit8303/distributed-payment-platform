package com.paymentledger.admin.api;

import com.paymentledger.account.domain.AccountEntity;
import com.paymentledger.account.domain.AccountStatus;
import com.paymentledger.account.domain.AccountType;
import com.paymentledger.account.exception.AccountNotFoundException;
import com.paymentledger.account.repository.AccountRepository;
import com.paymentledger.account.service.AccountService;
import com.paymentledger.admin.api.dto.AccountAdminResponse;
import com.paymentledger.admin.api.dto.AccountBalanceSummaryResponse;
import com.paymentledger.admin.api.dto.AccountLifecycleRequest;
import com.paymentledger.admin.api.dto.PageUtils;
import com.paymentledger.ledger.repository.LedgerEntryRepository;
import com.paymentledger.shared.logging.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/accounts")
@PreAuthorize("hasAnyRole('ADMIN', 'SYSTEM')")
public class AdminAccountController {

    private final AccountRepository accountRepository;
    private final AccountService accountService;
    private final LedgerEntryRepository ledgerEntryRepository;

    public AdminAccountController(AccountRepository accountRepository,
                                  AccountService accountService,
                                  LedgerEntryRepository ledgerEntryRepository) {
        this.accountRepository = accountRepository;
        this.accountService = accountService;
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    @GetMapping
    public ResponseEntity<Page<AccountAdminResponse>> listAccounts(
            @RequestParam(required = false) UUID ownerId,
            @RequestParam(required = false) AccountType accountType,
            @RequestParam(required = false) AccountStatus status,
            Pageable pageable) {

        Pageable clamped = PageUtils.clamp(pageable);
        Page<AccountEntity> page;

        if (ownerId != null) {
            page = accountRepository.findByOwnerId(ownerId, clamped);
        } else if (status != null) {
            page = accountRepository.findByStatus(status, clamped);
        } else if (accountType != null) {
            page = accountRepository.findByAccountType(accountType, clamped);
        } else {
            page = accountRepository.findAll(clamped);
        }

        return ResponseEntity.ok(page.map(AccountAdminResponse::fromEntity));
    }

    @GetMapping("/{accountId}")
    public ResponseEntity<AccountAdminResponse> getAccount(@PathVariable UUID accountId) {
        AccountEntity account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + accountId));
        return ResponseEntity.ok(AccountAdminResponse.fromEntity(account));
    }

    @GetMapping("/{accountId}/balance-summary")
    public ResponseEntity<AccountBalanceSummaryResponse> getBalanceSummary(@PathVariable UUID accountId) {
        AccountEntity account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + accountId));

        long authoritativeLedgerBalance = ledgerEntryRepository.calculateLedgerBalanceMinor(accountId);
        long materializedBalance = account.getMaterializedBalanceMinor();
        long diff = materializedBalance - authoritativeLedgerBalance;

        AccountBalanceSummaryResponse summary = new AccountBalanceSummaryResponse(
                account.getId(),
                account.getAccountNumber(),
                account.getCurrency(),
                materializedBalance,
                authoritativeLedgerBalance,
                diff,
                diff == 0
        );

        return ResponseEntity.ok(summary);
    }

    @PostMapping("/{accountId}/freeze")
    public ResponseEntity<AccountAdminResponse> freezeAccount(
            @PathVariable UUID accountId,
            @RequestBody(required = false) AccountLifecycleRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest) {

        UUID actorId = resolveActorId(authentication);
        String actorRole = resolveActorRole(authentication);
        String correlationId = resolveCorrelationId(httpRequest);
        String requestId = httpRequest.getHeader("X-Request-ID");
        String reason = request != null && request.reason() != null ? request.reason() : "Administrative freeze";

        AccountEntity frozen = accountService.adminFreezeAccount(
                accountId, reason, actorId, actorRole, correlationId, requestId);

        return ResponseEntity.ok(AccountAdminResponse.fromEntity(frozen));
    }

    @PostMapping("/{accountId}/unfreeze")
    public ResponseEntity<AccountAdminResponse> unfreezeAccount(
            @PathVariable UUID accountId,
            @RequestBody(required = false) AccountLifecycleRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest) {

        UUID actorId = resolveActorId(authentication);
        String actorRole = resolveActorRole(authentication);
        String correlationId = resolveCorrelationId(httpRequest);
        String requestId = httpRequest.getHeader("X-Request-ID");
        String reason = request != null && request.reason() != null ? request.reason() : "Administrative unfreeze";

        AccountEntity unfrozen = accountService.adminUnfreezeAccount(
                accountId, reason, actorId, actorRole, correlationId, requestId);

        return ResponseEntity.ok(AccountAdminResponse.fromEntity(unfrozen));
    }

    private UUID resolveActorId(Authentication authentication) {
        if (authentication == null || authentication.getPrincipal() == null) {
            return UUID.randomUUID();
        }
        String principal = authentication.getName();
        try {
            return UUID.fromString(principal);
        } catch (IllegalArgumentException e) {
            return UUID.nameUUIDFromBytes(principal.getBytes(StandardCharsets.UTF_8));
        }
    }

    private String resolveActorRole(Authentication authentication) {
        if (authentication == null || authentication.getAuthorities() == null) {
            return "ROLE_ADMIN";
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .findFirst()
                .orElse("ROLE_ADMIN");
    }

    private String resolveCorrelationId(HttpServletRequest request) {
        String mdc = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (mdc != null && !mdc.isBlank()) {
            return mdc;
        }
        String header = request.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER);
        if (header != null && !header.isBlank()) {
            return header;
        }
        return UUID.randomUUID().toString();
    }
}
