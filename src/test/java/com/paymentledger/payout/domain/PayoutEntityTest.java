package com.paymentledger.payout.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PayoutEntityTest {

    @Test
    @DisplayName("Constructor enforces strictly positive amount and sets initial state to REQUESTED")
    void constructorEnforcesPositiveAmount() {
        UUID accountId = UUID.randomUUID();
        PayoutEntity payout = new PayoutEntity(accountId, 10000L, "USD");

        assertThat(payout.getAccountId()).isEqualTo(accountId);
        assertThat(payout.getAmountMinor()).isEqualTo(10000L);
        assertThat(payout.getCurrency()).isEqualTo("USD");
        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.REQUESTED);

        assertThatThrownBy(() -> new PayoutEntity(accountId, 0L, "USD"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strictly positive");

        assertThatThrownBy(() -> new PayoutEntity(accountId, -100L, "USD"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strictly positive");
    }

    @Test
    @DisplayName("transitionToProcessing succeeds from REQUESTED state and rejects invalid states")
    void transitionToProcessing() {
        PayoutEntity payout = new PayoutEntity(UUID.randomUUID(), 10000L, "USD");
        payout.transitionToProcessing();
        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.PROCESSING);

        assertThatThrownBy(payout::transitionToProcessing)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be in REQUESTED state");
    }

    @Test
    @DisplayName("settle transitions status to SETTLED and records compensating tx and provider ref")
    void settleSuccess() {
        PayoutEntity payout = new PayoutEntity(UUID.randomUUID(), 10000L, "USD");
        payout.transitionToProcessing();

        UUID compensatingTxId = UUID.randomUUID();
        payout.settle("prov_payout_ref", compensatingTxId);

        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.SETTLED);
        assertThat(payout.getProviderReference()).isEqualTo("prov_payout_ref");
        assertThat(payout.getCompensatingLedgerTransactionId()).isEqualTo(compensatingTxId);

        assertThatThrownBy(() -> payout.settle("another_ref", compensatingTxId))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("fail transitions status to FAILED and records failure reason")
    void failSuccess() {
        PayoutEntity payout = new PayoutEntity(UUID.randomUUID(), 10000L, "USD");
        payout.transitionToProcessing();

        payout.fail("Bank account invalid");
        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.FAILED);
        assertThat(payout.getFailureReason()).isEqualTo("Bank account invalid");

        assertThatThrownBy(() -> payout.fail("Another failure"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("markPendingReconciliation transitions status and allows subsequent settlement")
    void pendingReconciliation() {
        PayoutEntity payout = new PayoutEntity(UUID.randomUUID(), 10000L, "USD");
        payout.transitionToProcessing();

        payout.markPendingReconciliation("Network timeout");
        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.PENDING_RECONCILIATION);

        UUID compensatingTxId = UUID.randomUUID();
        payout.settle("reconciled_payout_ref", compensatingTxId);
        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.SETTLED);
    }
}
