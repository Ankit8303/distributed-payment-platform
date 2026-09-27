package com.paymentledger.ledger.domain;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class LedgerTransactionEntityTest {

    @Test
    void testValidBalancedTransaction() {
        LedgerTransactionEntity tx = new LedgerTransactionEntity(LedgerTransactionType.PAYMENT, UUID.randomUUID(), "PAYMENT", "USD", "Test");
        
        tx.addEntry(new LedgerEntryEntity(UUID.randomUUID(), LedgerEntryDirection.DEBIT, 1000, "USD", 1));
        tx.addEntry(new LedgerEntryEntity(UUID.randomUUID(), LedgerEntryDirection.CREDIT, 1000, "USD", 1));

        tx.post();
        assertThat(tx.getStatus()).isEqualTo(LedgerTransactionStatus.POSTED);
        assertThat(tx.getPostedAt()).isNotNull();
    }

    @Test
    void testUnbalancedTransaction_ThrowsException() {
        LedgerTransactionEntity tx = new LedgerTransactionEntity(LedgerTransactionType.PAYMENT, UUID.randomUUID(), "PAYMENT", "USD", "Test");
        
        tx.addEntry(new LedgerEntryEntity(UUID.randomUUID(), LedgerEntryDirection.DEBIT, 1000, "USD", 1));
        tx.addEntry(new LedgerEntryEntity(UUID.randomUUID(), LedgerEntryDirection.CREDIT, 500, "USD", 1));

        assertThrows(IllegalStateException.class, tx::post);
    }

    @Test
    void testCurrencyMismatch_ThrowsException() {
        LedgerTransactionEntity tx = new LedgerTransactionEntity(LedgerTransactionType.PAYMENT, UUID.randomUUID(), "PAYMENT", "USD", "Test");
        
        tx.addEntry(new LedgerEntryEntity(UUID.randomUUID(), LedgerEntryDirection.DEBIT, 1000, "EUR", 1));
        tx.addEntry(new LedgerEntryEntity(UUID.randomUUID(), LedgerEntryDirection.CREDIT, 1000, "USD", 1));

        assertThrows(IllegalStateException.class, tx::post);
    }

    @Test
    void testNegativeAmount_ThrowsException() {
        LedgerTransactionEntity tx = new LedgerTransactionEntity(LedgerTransactionType.PAYMENT, UUID.randomUUID(), "PAYMENT", "USD", "Test");
        
        tx.addEntry(new LedgerEntryEntity(UUID.randomUUID(), LedgerEntryDirection.DEBIT, -1000, "USD", 1));
        tx.addEntry(new LedgerEntryEntity(UUID.randomUUID(), LedgerEntryDirection.CREDIT, -1000, "USD", 1));

        assertThrows(IllegalStateException.class, tx::post);
    }
}
