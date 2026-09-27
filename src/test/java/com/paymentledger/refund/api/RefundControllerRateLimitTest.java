package com.paymentledger.refund.api;

import com.paymentledger.refund.service.RefundService;
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
class RefundControllerRateLimitTest {
    @Mock RefundService refundService;
    @Mock FinancialApiRateLimiter financialApiRateLimiter;

    @Test
    void createRefundEnforcesRefundBucketBeforeFinancialProcessing() {
        UUID userId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        doThrow(new RateLimitExceededException("blocked", 42, 30))
                .when(financialApiRateLimiter).enforce(FinancialRateLimitOperation.REFUND, userId.toString());

        RefundController controller = new RefundController(refundService, financialApiRateLimiter);

        assertThatThrownBy(() -> controller.createRefund(paymentId, "idem", null, userId.toString(), null))
                .isInstanceOf(RateLimitExceededException.class);

        verify(financialApiRateLimiter).enforce(FinancialRateLimitOperation.REFUND, userId.toString());
    }

    @Test
    void createReversalEnforcesReversalBucketBeforeFinancialProcessing() {
        UUID userId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        doThrow(new RateLimitExceededException("blocked", 42, 30))
                .when(financialApiRateLimiter).enforce(FinancialRateLimitOperation.REVERSAL, userId.toString());

        RefundController controller = new RefundController(refundService, financialApiRateLimiter);

        assertThatThrownBy(() -> controller.createReversal(paymentId, "idem", null, userId.toString(), null))
                .isInstanceOf(RateLimitExceededException.class);

        verify(financialApiRateLimiter).enforce(FinancialRateLimitOperation.REVERSAL, userId.toString());
    }
}
