package com.paymentledger.payout.api;

import com.paymentledger.payout.service.PayoutService;
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
import static org.mockito.Mockito.doThrow;

@ExtendWith(MockitoExtension.class)
class PayoutControllerRateLimitTest {
    @Mock PayoutService payoutService;
    @Mock FinancialApiRateLimiter financialApiRateLimiter;

    @Test
    void createPayoutEnforcesPayoutBucketBeforeFinancialProcessing() {
        UUID userId = UUID.randomUUID();
        doThrow(new RateLimitExceededException("blocked", 42, 30))
                .when(financialApiRateLimiter).enforce(FinancialRateLimitOperation.PAYOUT, userId.toString());

        PayoutController controller = new PayoutController(payoutService, financialApiRateLimiter);

        assertThatThrownBy(() -> controller.createPayout("idem", null, userId.toString(), null))
                .isInstanceOf(RateLimitExceededException.class);

        verify(financialApiRateLimiter).enforce(FinancialRateLimitOperation.PAYOUT, userId.toString());
    }
}
