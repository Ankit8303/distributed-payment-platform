package com.paymentledger.shared.redis;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedisKeyNamespacesTest {

    @Test
    void financialKeyContainsOperationAndActorWithoutSecretMaterial() {
        assertThat(RedisKeyNamespaces.financialApiRateLimitKey(
                FinancialRateLimitOperation.PAYOUT, "User-123"))
                .isEqualTo("rate-limit:financial:v1:payout:user-123");
    }

    @Test
    void financialKeyRejectsMissingOperationOrActor() {
        assertThatThrownBy(() -> RedisKeyNamespaces.financialApiRateLimitKey(null, "user-123"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RedisKeyNamespaces.financialApiRateLimitKey(
                FinancialRateLimitOperation.PAYMENT, " "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
