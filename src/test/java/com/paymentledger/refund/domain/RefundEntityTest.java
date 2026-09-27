package com.paymentledger.refund.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefundEntityTest {

    @Test
    @DisplayName("Constructor enforces strictly positive amount and sets initial state to REQUESTED")
    void constructorEnforcesPositiveAmount() {
        UUID paymentId = UUID.randomUUID();
        RefundEntity refund = new RefundEntity(paymentId, 5000L, "USD", "Customer requested");

        assertThat(refund.getPaymentId()).isEqualTo(paymentId);
        assertThat(refund.getAmountMinor()).isEqualTo(5000L);
        assertThat(refund.getCurrency()).isEqualTo("USD");
        assertThat(refund.getReason()).isEqualTo("Customer requested");
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.REQUESTED);

        assertThatThrownBy(() -> new RefundEntity(paymentId, 0L, "USD", "Zero amount"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strictly positive");

        assertThatThrownBy(() -> new RefundEntity(paymentId, -500L, "USD", "Negative amount"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strictly positive");
    }

    @Test
    @DisplayName("transitionToProcessing succeeds from REQUESTED state and rejects invalid states")
    void transitionToProcessing() {
        RefundEntity refund = new RefundEntity(UUID.randomUUID(), 5000L, "USD", "Reason");
        refund.transitionToProcessing();
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.PROCESSING);

        assertThatThrownBy(refund::transitionToProcessing)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be in REQUESTED state");
    }

    @Test
    @DisplayName("settle transitions status to SETTLED and records compensating tx and provider ref")
    void settleSuccess() {
        RefundEntity refund = new RefundEntity(UUID.randomUUID(), 5000L, "USD", "Reason");
        refund.transitionToProcessing();

        UUID compensatingTxId = UUID.randomUUID();
        refund.settle("prov_ref_123", compensatingTxId);

        assertThat(refund.getStatus()).isEqualTo(RefundStatus.SETTLED);
        assertThat(refund.getProviderReference()).isEqualTo("prov_ref_123");
        assertThat(refund.getCompensatingLedgerTransactionId()).isEqualTo(compensatingTxId);

        // Terminal state cannot be re-settled or transitioned
        assertThatThrownBy(() -> refund.settle("prov_ref_456", compensatingTxId))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("fail transitions status to FAILED and records failure reason")
    void failSuccess() {
        RefundEntity refund = new RefundEntity(UUID.randomUUID(), 5000L, "USD", "Reason");
        refund.transitionToProcessing();

        refund.fail("Gateway timeout");
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.FAILED);
        assertThat(refund.getFailureReason()).isEqualTo("Gateway timeout");

        // Terminal state cannot fail again
        assertThatThrownBy(() -> refund.fail("Another reason"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("markPendingReconciliation transitions status and allows subsequent settlement")
    void pendingReconciliation() {
        RefundEntity refund = new RefundEntity(UUID.randomUUID(), 5000L, "USD", "Reason");
        refund.transitionToProcessing();

        refund.markPendingReconciliation("Network disconnect");
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.PENDING_RECONCILIATION);

        UUID compensatingTxId = UUID.randomUUID();
        refund.settle("reconciled_ref", compensatingTxId);
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.SETTLED);
    }
}
