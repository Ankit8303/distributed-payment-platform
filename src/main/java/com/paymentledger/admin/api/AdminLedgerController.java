package com.paymentledger.admin.api;

import com.paymentledger.admin.api.dto.LedgerEntryAdminResponse;
import com.paymentledger.admin.api.dto.LedgerTransactionAdminResponse;
import com.paymentledger.admin.api.dto.PageUtils;
import com.paymentledger.ledger.domain.LedgerEntryEntity;
import com.paymentledger.ledger.domain.LedgerTransactionEntity;
import com.paymentledger.ledger.repository.LedgerEntryRepository;
import com.paymentledger.ledger.repository.LedgerTransactionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/ledger")
@PreAuthorize("hasAnyRole('ADMIN', 'SYSTEM')")
public class AdminLedgerController {

    private final LedgerTransactionRepository transactionRepository;
    private final LedgerEntryRepository entryRepository;

    public AdminLedgerController(LedgerTransactionRepository transactionRepository,
                                 LedgerEntryRepository entryRepository) {
        this.transactionRepository = transactionRepository;
        this.entryRepository = entryRepository;
    }

    @GetMapping("/transactions")
    public ResponseEntity<Page<LedgerTransactionAdminResponse>> listTransactions(
            @RequestParam(required = false) String sourceReferenceType,
            Pageable pageable) {

        Pageable clamped = PageUtils.clamp(pageable);
        Page<LedgerTransactionEntity> page;

        if (sourceReferenceType != null && !sourceReferenceType.isBlank()) {
            page = transactionRepository.findBySourceReferenceType(sourceReferenceType, clamped);
        } else {
            page = transactionRepository.findAll(clamped);
        }

        return ResponseEntity.ok(page.map(tx -> {
            List<LedgerEntryEntity> entries = entryRepository.findByLedgerTransaction_Id(tx.getId());
            List<LedgerEntryAdminResponse> entryResponses = entries.stream()
                    .map(LedgerEntryAdminResponse::fromEntity)
                    .toList();
            return LedgerTransactionAdminResponse.fromEntity(tx, entryResponses);
        }));
    }

    @GetMapping("/transactions/{transactionId}")
    public ResponseEntity<LedgerTransactionAdminResponse> getTransaction(@PathVariable UUID transactionId) {
        LedgerTransactionEntity tx = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ledger transaction not found: " + transactionId));

        List<LedgerEntryEntity> entries = entryRepository.findByLedgerTransaction_Id(transactionId);
        List<LedgerEntryAdminResponse> entryResponses = entries.stream()
                .map(LedgerEntryAdminResponse::fromEntity)
                .toList();

        return ResponseEntity.ok(LedgerTransactionAdminResponse.fromEntity(tx, entryResponses));
    }

    @GetMapping("/accounts/{accountId}/entries")
    public ResponseEntity<Page<LedgerEntryAdminResponse>> listAccountEntries(
            @PathVariable UUID accountId,
            Pageable pageable) {

        Pageable clamped = PageUtils.clamp(pageable);
        Page<LedgerEntryEntity> page = entryRepository.findByAccountId(accountId, clamped);
        return ResponseEntity.ok(page.map(LedgerEntryAdminResponse::fromEntity));
    }
}
