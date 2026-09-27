package com.paymentledger.payment.api;

import com.paymentledger.account.repository.AccountRepository;
import com.paymentledger.payment.service.PaymentService;
import com.paymentledger.shared.redis.FinancialApiRateLimiter;
import com.paymentledger.shared.redis.FinancialRateLimitOperation;
import com.paymentledger.shared.redis.RateLimitExceededException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentControllerRateLimitTest {
    @Mock PaymentService paymentService;
    @Mock AccountRepository accountRepository;
    @Mock FinancialApiRateLimiter financialApiRateLimiter;

    @Test
    void createPaymentEnforcesPaymentBucketBeforeFinancialProcessing() {
        UUID userId = UUID.randomUUID();
        when(financialApiRateLimiter.enforce(FinancialRateLimitOperation.PAYMENT, userId.toString()))
                .thenThrow(new RateLimitExceededException("blocked", 42, 30));

        PaymentController controller = new PaymentController(paymentService, accountRepository, financialApiRateLimiter);

        assertThatThrownBy(() -> controller.createPayment(userId.toString(), "idem", null, null))
                .isInstanceOf(RateLimitExceededException.class);

        verify(financialApiRateLimiter).enforce(FinancialRateLimitOperation.PAYMENT, userId.toString());
    }
}
