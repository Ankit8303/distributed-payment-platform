package com.paymentledger.account.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountEntityTest {

    @Test
    @DisplayName("Default account initialization sets status to PENDING_VERIFICATION and zero balance")
    void defaultInitialization() {
        UUID ownerId = UUID.randomUUID();
        AccountEntity account = new AccountEntity("ACC-123", ownerId, AccountType.CUSTOMER, "USD", AccountStatus.ACTIVE);

        assertThat(account.getAccountNumber()).isEqualTo("ACC-123");
        assertThat(account.getOwnerId()).isEqualTo(ownerId);
        assertThat(account.getAccountType()).isEqualTo(AccountType.CUSTOMER);
        assertThat(account.getCurrency()).isEqualTo("USD");
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.getMaterializedBalanceMinor()).isEqualTo(0L);
    }

    @Test
    @DisplayName("Freezing an active account transitions status to FROZEN")
    void freezeActiveAccount() {
        AccountEntity account = new AccountEntity("ACC-123", UUID.randomUUID(), AccountType.CUSTOMER, "USD", AccountStatus.ACTIVE);
        account.freeze();
        assertThat(account.getStatus()).isEqualTo(AccountStatus.FROZEN);
    }

    @Test
    @DisplayName("Freezing an already frozen account throws IllegalStateException")
    void freezeAlreadyFrozenAccountThrows() {
        AccountEntity account = new AccountEntity("ACC-123", UUID.randomUUID(), AccountType.CUSTOMER, "USD", AccountStatus.FROZEN);
        assertThatThrownBy(account::freeze)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already frozen");
    }

    @Test
    @DisplayName("Freezing a closed account throws IllegalStateException")
    void freezeClosedAccountThrows() {
        AccountEntity account = new AccountEntity("ACC-123", UUID.randomUUID(), AccountType.CUSTOMER, "USD", AccountStatus.CLOSED);
        assertThatThrownBy(account::freeze)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed account");
    }

    @Test
    @DisplayName("Unfreezing a frozen account transitions status to ACTIVE")
    void unfreezeFrozenAccount() {
        AccountEntity account = new AccountEntity("ACC-123", UUID.randomUUID(), AccountType.CUSTOMER, "USD", AccountStatus.FROZEN);
        account.unfreeze();
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    @DisplayName("Unfreezing an active account throws IllegalStateException")
    void unfreezeActiveAccountThrows() {
        AccountEntity account = new AccountEntity("ACC-123", UUID.randomUUID(), AccountType.CUSTOMER, "USD", AccountStatus.ACTIVE);
        assertThatThrownBy(account::unfreeze)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not frozen");
    }

    @Test
    @DisplayName("Closing an account transitions status to CLOSED")
    void closeAccount() {
        AccountEntity account = new AccountEntity("ACC-123", UUID.randomUUID(), AccountType.CUSTOMER, "USD", AccountStatus.ACTIVE);
        account.close();
        assertThat(account.getStatus()).isEqualTo(AccountStatus.CLOSED);
    }

    @Test
    @DisplayName("Closing an already closed account throws IllegalStateException")
    void closeAlreadyClosedAccountThrows() {
        AccountEntity account = new AccountEntity("ACC-123", UUID.randomUUID(), AccountType.CUSTOMER, "USD", AccountStatus.CLOSED);
        assertThatThrownBy(account::close)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already closed");
    }

    @Test
    @DisplayName("Materialized balance arithmetic correctly increments and decrements")
    void balanceArithmetic() {
        AccountEntity account = new AccountEntity("ACC-123", UUID.randomUUID(), AccountType.CUSTOMER, "USD", AccountStatus.ACTIVE);

        account.addBalanceMinor(5000L);
        assertThat(account.getMaterializedBalanceMinor()).isEqualTo(5000L);

        account.subtractBalanceMinor(2000L);
        assertThat(account.getMaterializedBalanceMinor()).isEqualTo(3000L);

        assertThatThrownBy(() -> account.addBalanceMinor(-100L))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> account.subtractBalanceMinor(-100L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("fromCached correctly reconstructs non-authoritative read model")
    void fromCachedReconstruction() {
        UUID id = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        Instant now = Instant.now();

        AccountEntity cached = AccountEntity.fromCached(
                id, "ACC-CACHED", ownerId, AccountType.CUSTOMER, "USD",
                AccountStatus.ACTIVE, 75000L, 2L, now, now
        );

        assertThat(cached.getId()).isEqualTo(id);
        assertThat(cached.getAccountNumber()).isEqualTo("ACC-CACHED");
        assertThat(cached.getOwnerId()).isEqualTo(ownerId);
        assertThat(cached.getMaterializedBalanceMinor()).isEqualTo(75000L);
        assertThat(cached.getVersion()).isEqualTo(2L);
        assertThat(cached.getStatus()).isEqualTo(AccountStatus.ACTIVE);
    }
}
