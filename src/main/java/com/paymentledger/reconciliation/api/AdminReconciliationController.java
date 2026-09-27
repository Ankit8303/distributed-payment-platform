package com.paymentledger.reconciliation.api;

import com.paymentledger.reconciliation.audit.BalanceConsistencyAuditor;
import com.paymentledger.reconciliation.audit.LedgerConsistencyAuditor;
import com.paymentledger.reconciliation.domain.ReconciliationAttemptEntity;
import com.paymentledger.reconciliation.domain.ReconciliationCaseEntity;
import com.paymentledger.reconciliation.domain.ReconciliationStatus;
import com.paymentledger.reconciliation.repository.ReconciliationAttemptRepository;
import com.paymentledger.reconciliation.repository.ReconciliationCaseRepository;
import com.paymentledger.reconciliation.service.ReconciliationService;
import com.paymentledger.reconciliation.worker.ReconciliationWorker;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/reconciliation")
public class AdminReconciliationController {

    private final ReconciliationService reconciliationService;
    private final ReconciliationWorker reconciliationWorker;
    private final ReconciliationCaseRepository caseRepository;
    private final ReconciliationAttemptRepository attemptRepository;
    private final LedgerConsistencyAuditor ledgerAuditor;
    private final BalanceConsistencyAuditor balanceAuditor;
    private final com.paymentledger.admin.service.AdminAuditService adminAuditService;

    public record CaseDetailResponse(
            @com.fasterxml.jackson.annotation.JsonUnwrapped ReconciliationCaseEntity caseDetails,
            List<ReconciliationAttemptEntity> attempts) {}

    public AdminReconciliationController(ReconciliationService reconciliationService,
                                       ReconciliationWorker reconciliationWorker,
                                       ReconciliationCaseRepository caseRepository,
                                       ReconciliationAttemptRepository attemptRepository,
                                       LedgerConsistencyAuditor ledgerAuditor,
                                       BalanceConsistencyAuditor balanceAuditor,
                                       @org.springframework.beans.factory.annotation.Autowired(required = false)
                                       com.paymentledger.admin.service.AdminAuditService adminAuditService) {
        this.reconciliationService = reconciliationService;
        this.reconciliationWorker = reconciliationWorker;
        this.caseRepository = caseRepository;
        this.attemptRepository = attemptRepository;
        this.ledgerAuditor = ledgerAuditor;
        this.balanceAuditor = balanceAuditor;
        this.adminAuditService = adminAuditService;
    }

    @GetMapping("/cases")
    @PreAuthorize("hasAnyRole('ADMIN', 'SYSTEM')")
    public ResponseEntity<Page<ReconciliationCaseEntity>> listCases(
            @RequestParam(required = false) ReconciliationStatus status,
            Pageable pageable) {
        Pageable clamped = com.paymentledger.admin.api.dto.PageUtils.clamp(pageable);
        if (status != null) {
            return ResponseEntity.ok(caseRepository.findByReconciliationStatus(status, clamped));
        }
        return ResponseEntity.ok(caseRepository.findAll(clamped));
    }

    @GetMapping("/cases/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SYSTEM')")
    public ResponseEntity<CaseDetailResponse> getCase(@PathVariable UUID id) {
        ReconciliationCaseEntity reconCase = caseRepository.findById(id).orElse(null);
        if (reconCase == null) {
            return ResponseEntity.notFound().build();
        }
        List<ReconciliationAttemptEntity> attempts = attemptRepository.findByReconciliationCaseIdOrderByAttemptNumberAsc(id);
        return ResponseEntity.ok(new CaseDetailResponse(reconCase, attempts));
    }

    @PostMapping("/cases/{id}/trigger")
    @PreAuthorize("hasAnyRole('ADMIN', 'SYSTEM')")
    public ResponseEntity<ReconciliationCaseEntity> triggerCase(@PathVariable UUID id) {
        reconciliationService.reconcileCase(id, "admin-trigger");
        ReconciliationCaseEntity updated = caseRepository.findById(id).orElse(null);
        if (updated == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(updated);
    }

    @PostMapping("/cases/{id}/retry")
    @PreAuthorize("hasAnyRole('ADMIN', 'SYSTEM')")
    public ResponseEntity<CaseDetailResponse> retryCase(
            @PathVariable UUID id,
            org.springframework.security.core.Authentication authentication,
            jakarta.servlet.http.HttpServletRequest httpRequest) {

        ReconciliationCaseEntity reconCase = caseRepository.findById(id)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Reconciliation case not found: " + id));

        String beforeState = reconCase.getReconciliationStatus().name();
        UUID actorId = resolveActorId(authentication);
        String actorRole = resolveActorRole(authentication);
        String correlationId = resolveCorrelationId(httpRequest);
        String requestId = httpRequest.getHeader("X-Request-ID");

        String workerId = "admin-" + actorId;
        reconciliationService.reconcileCase(id, workerId);

        ReconciliationCaseEntity updated = caseRepository.findById(id).orElse(reconCase);

        if (adminAuditService != null) {
            adminAuditService.recordAudit(
                    actorId,
                    actorRole,
                    "RECONCILIATION_RETRY",
                    "RECONCILIATION_CASE",
                    id.toString(),
                    "Administrative reconciliation retry",
                    correlationId,
                    requestId,
                    beforeState,
                    updated.getReconciliationStatus().name(),
                    null
            );
        }

        List<ReconciliationAttemptEntity> attempts = attemptRepository.findByReconciliationCaseIdOrderByAttemptNumberAsc(id);
        return ResponseEntity.ok(new CaseDetailResponse(updated, attempts));
    }

    @PostMapping("/run")
    @PreAuthorize("hasAnyRole('ADMIN', 'SYSTEM')")
    public ResponseEntity<Integer> triggerCycle() {
        int processed = reconciliationWorker.runReconciliationCycle();
        return ResponseEntity.ok(processed);
    }

    @PostMapping("/audit/ledger")
    @PreAuthorize("hasAnyRole('ADMIN', 'SYSTEM')")
    public ResponseEntity<LedgerConsistencyAuditor.AuditReport> auditLedger() {
        return ResponseEntity.ok(ledgerAuditor.auditLedgerConsistency());
    }

    @PostMapping("/audit/balances")
    @PreAuthorize("hasAnyRole('ADMIN', 'SYSTEM')")
    public ResponseEntity<BalanceConsistencyAuditor.BalanceReport> auditBalances() {
        return ResponseEntity.ok(balanceAuditor.auditBalanceConsistency());
    }

    private UUID resolveActorId(org.springframework.security.core.Authentication authentication) {
        if (authentication == null || authentication.getPrincipal() == null) {
            return UUID.randomUUID();
        }
        String principal = authentication.getName();
        try {
            return UUID.fromString(principal);
        } catch (IllegalArgumentException e) {
            return UUID.nameUUIDFromBytes(principal.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private String resolveActorRole(org.springframework.security.core.Authentication authentication) {
        if (authentication == null || authentication.getAuthorities() == null) {
            return "ROLE_ADMIN";
        }
        return authentication.getAuthorities().stream()
                .map(org.springframework.security.core.GrantedAuthority::getAuthority)
                .findFirst()
                .orElse("ROLE_ADMIN");
    }

    private String resolveCorrelationId(jakarta.servlet.http.HttpServletRequest request) {
        String mdc = org.slf4j.MDC.get(com.paymentledger.shared.logging.CorrelationIdFilter.MDC_KEY);
        if (mdc != null && !mdc.isBlank()) {
            return mdc;
        }
        String header = request.getHeader(com.paymentledger.shared.logging.CorrelationIdFilter.CORRELATION_ID_HEADER);
        if (header != null && !header.isBlank()) {
            return header;
        }
        return UUID.randomUUID().toString();
    }
}
