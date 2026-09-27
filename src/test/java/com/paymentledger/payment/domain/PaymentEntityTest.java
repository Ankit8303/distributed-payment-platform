package com.paymentledger.payment.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class PaymentEntityTest {

    @Test
    void validTransitions_ShouldSucceed() {
        PaymentEntity payment = new PaymentEntity(
                "key",
                "scope",
                UUID.randomUUID(),
                UUID.randomUUID(),
                1000L,
                "USD"
        );
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CREATED);

        payment.authorize();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.AUTHORIZING);

        payment.authorizationSucceeded("auth_123");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(payment.getProviderReference()).isEqualTo("auth_123");

        payment.capture();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CAPTURING);

        payment.captureSucceeded("cap_123");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SETTLED);
        assertThat(payment.getProviderReference()).isEqualTo("cap_123");
    }

    @Test
    void providerDecline_ShouldGoToDeclined() {
        PaymentEntity payment = new PaymentEntity("k", "s", UUID.randomUUID(), UUID.randomUUID(), 1000, "USD");
        payment.authorize();
        payment.authorizationDeclined("INSUFFICIENT_FUNDS");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.DECLINED);
        assertThat(payment.getFailureReason()).isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    void captureTimeout_ShouldGoToPendingReconciliation() {
        PaymentEntity payment = new PaymentEntity("k", "s", UUID.randomUUID(), UUID.randomUUID(), 1000, "USD");
        payment.authorize();
        payment.authorizationSucceeded("auth");
        payment.capture();
        
        payment.markPendingReconciliation();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING_RECONCILIATION);
        
        // PENDING_RECONCILIATION can transition to SETTLED
        payment.captureSucceeded("cap");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SETTLED);
    }

    @Test
    void captureTimeout_ShouldGoToFailed() {
        PaymentEntity payment = new PaymentEntity("k", "s", UUID.randomUUID(), UUID.randomUUID(), 1000, "USD");
        payment.authorize();
        payment.authorizationSucceeded("auth");
        payment.capture();
        
        payment.markPendingReconciliation();
        
        // PENDING_RECONCILIATION can transition to FAILED
        payment.captureFailed("DECLINED_BY_PROVIDER_LATER");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void authorizeTimeout_ShouldGoToPendingReconciliation() {
        PaymentEntity payment = new PaymentEntity("k", "s", UUID.randomUUID(), UUID.randomUUID(), 1000, "USD");
        payment.authorize();
        
        payment.markPendingReconciliation();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING_RECONCILIATION);
    }

    @Test
    void invalidTransitions_ShouldThrowIllegalStateException() {
        PaymentEntity payment = new PaymentEntity("k", "s", UUID.randomUUID(), UUID.randomUUID(), 1000, "USD");
        
        // CREATED -> SETTLED
        assertThrows(IllegalStateException.class, () -> payment.captureSucceeded("cap"));
        
        payment.authorize();
        payment.authorizationDeclined("DECLINE");
        
        // DECLINED -> CAPTURING
        assertThrows(IllegalStateException.class, () -> payment.capture());

        PaymentEntity settled = new PaymentEntity("k", "s", UUID.randomUUID(), UUID.randomUUID(), 1000, "USD");
        settled.authorize();
        settled.authorizationSucceeded("auth");
        settled.capture();
        settled.captureSucceeded("cap");
        
        // SETTLED -> AUTHORIZING
        assertThrows(IllegalStateException.class, () -> settled.authorize());
        
        // SETTLED -> DECLINED
        assertThrows(IllegalStateException.class, () -> settled.authorizationDeclined("x"));

        PaymentEntity failed = new PaymentEntity("k", "s", UUID.randomUUID(), UUID.randomUUID(), 1000, "USD");
        failed.authorize();
        failed.authorizationSucceeded("auth");
        failed.capture();
        failed.captureFailed("fail");
        
        // FAILED -> SETTLED
        assertThrows(IllegalStateException.class, () -> failed.captureSucceeded("cap"));
    }
}
