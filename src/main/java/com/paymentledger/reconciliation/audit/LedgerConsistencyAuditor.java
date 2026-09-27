package com.paymentledger.reconciliation.audit;

import com.paymentledger.account.repository.AccountRepository;
import com.paymentledger.ledger.domain.LedgerEntryDirection;
import com.paymentledger.ledger.domain.LedgerEntryEntity;
import com.paymentledger.ledger.domain.LedgerTransactionEntity;
import com.paymentledger.ledger.repository.LedgerEntryRepository;
import com.paymentledger.ledger.repository.LedgerTransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class LedgerConsistencyAuditor {

    private static final Logger log = LoggerFactory.getLogger(LedgerConsistencyAuditor.class);

    private final LedgerTransactionRepository transactionRepository;
    private final LedgerEntryRepository entryRepository;
    private final AccountRepository accountRepository;

    public record AuditFinding(UUID transactionId, String findingType, String description) {}
    public record AuditReport(int transactionsAudited, int findingsCount, List<AuditFinding> findings) {
        public boolean isClean() { return findingsCount == 0; }
    }

    public LedgerConsistencyAuditor(LedgerTransactionRepository transactionRepository,
                                   LedgerEntryRepository entryRepository,
                                   AccountRepository accountRepository) {
        this.transactionRepository = transactionRepository;
        this.entryRepository = entryRepository;
        this.accountRepository = accountRepository;
    }

    @Transactional(readOnly = true)
    public AuditReport auditLedgerConsistency() {
        List<LedgerTransactionEntity> transactions = transactionRepository.findAll();
        List<AuditFinding> findings = new ArrayList<>();

        for (LedgerTransactionEntity tx : transactions) {
            List<LedgerEntryEntity> entries = entryRepository.findByLedgerTransaction_Id(tx.getId());

            if (entries.isEmpty()) {
                findings.add(new AuditFinding(tx.getId(), "ORPHAN_TRANSACTION", "Transaction has 0 entries"));
                continue;
            }

            long totalDebit = 0L;
            long totalCredit = 0L;

            for (LedgerEntryEntity entry : entries) {
                if (entry.getAmountMinor() <= 0) {
                    findings.add(new AuditFinding(tx.getId(), "NON_POSITIVE_AMOUNT", "Entry " + entry.getId() + " has non-positive amount: " + entry.getAmountMinor()));
                }

                if (!entry.getCurrency().equals(tx.getCurrency())) {
                    findings.add(new AuditFinding(tx.getId(), "CURRENCY_MISMATCH", "Entry currency " + entry.getCurrency() + " does not match tx currency " + tx.getCurrency()));
                }

                if (!accountRepository.existsById(entry.getAccountId())) {
                    findings.add(new AuditFinding(tx.getId(), "INVALID_ACCOUNT_REFERENCE", "Account " + entry.getAccountId() + " does not exist"));
                }

                if (entry.getDirection() == LedgerEntryDirection.DEBIT) {
                    totalDebit += entry.getAmountMinor();
                } else if (entry.getDirection() == LedgerEntryDirection.CREDIT) {
                    totalCredit += entry.getAmountMinor();
                }
            }

            // Exclude initial one-legged funding transactions if transaction_type == 'SYSTEM_ADJUSTMENT' and description == 'Funding'
            boolean isInitialFunding = "Funding".equals(tx.getDescription()) && entries.size() == 1;
            if (!isInitialFunding && totalDebit != totalCredit) {
                findings.add(new AuditFinding(tx.getId(), "UNBALANCED_TRANSACTION",
                        "Debit total (" + totalDebit + ") does not equal Credit total (" + totalCredit + ")"));
            }
        }

        if (!findings.isEmpty()) {
            log.warn("Ledger consistency audit found {} discrepancies across {} transactions", findings.size(), transactions.size());
        } else {
            log.info("Ledger consistency audit completed clean across {} transactions", transactions.size());
        }

        return new AuditReport(transactions.size(), findings.size(), findings);
    }
}
