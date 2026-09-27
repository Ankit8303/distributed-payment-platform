package com.paymentledger.reconciliation.audit;

import com.paymentledger.account.domain.AccountEntity;
import com.paymentledger.account.repository.AccountRepository;
import com.paymentledger.ledger.repository.LedgerEntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class BalanceConsistencyAuditor {

    private static final Logger log = LoggerFactory.getLogger(BalanceConsistencyAuditor.class);

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    public record BalanceFinding(UUID accountId, long materializedBalance, long calculatedLedgerBalance, long delta) {}
    public record BalanceReport(int accountsAudited, int findingsCount, List<BalanceFinding> findings) {
        public boolean isClean() { return findingsCount == 0; }
    }

    public BalanceConsistencyAuditor(AccountRepository accountRepository,
                                    LedgerEntryRepository ledgerEntryRepository) {
        this.accountRepository = accountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    @Transactional(readOnly = true)
    public BalanceReport auditBalanceConsistency() {
        List<AccountEntity> accounts = accountRepository.findAll();
        List<BalanceFinding> findings = new ArrayList<>();

        for (AccountEntity account : accounts) {
            long calculated = ledgerEntryRepository.calculateLedgerBalanceMinor(account.getId());
            long materialized = account.getMaterializedBalanceMinor();

            if (calculated != materialized) {
                findings.add(new BalanceFinding(account.getId(), materialized, calculated, calculated - materialized));
            }
        }

        if (!findings.isEmpty()) {
            log.warn("Balance consistency audit found {} discrepancies across {} accounts", findings.size(), accounts.size());
        } else {
            log.info("Balance consistency audit completed clean across {} accounts", accounts.size());
        }

        return new BalanceReport(accounts.size(), findings.size(), findings);
    }
}
