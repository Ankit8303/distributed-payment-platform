package com.paymentledger.account.service;

import com.paymentledger.account.domain.AccountEntity;
import com.paymentledger.account.domain.AccountStatus;
import com.paymentledger.account.domain.AccountType;
import com.paymentledger.account.exception.AccountDomainException;
import com.paymentledger.account.exception.AccountNotFoundException;
import com.paymentledger.account.repository.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    private AccountRepository accountRepository;

    private AccountService accountService;

    @BeforeEach
    void setUp() {
        accountService = new AccountService(accountRepository);
    }

    @Test
    @DisplayName("createAccount should create CUSTOMER account for CUSTOMER user")
    void createAccount_customer_success() {
        UUID ownerId = UUID.randomUUID();
        when(accountRepository.save(any(AccountEntity.class))).thenAnswer(i -> {
            AccountEntity a = i.getArgument(0);
            ReflectionTestUtils.setField(a, "id", UUID.randomUUID());
            return a;
        });

        AccountEntity account = accountService.createAccount(ownerId, AccountType.CUSTOMER, "USD", "CUSTOMER");

        assertThat(account.getOwnerId()).isEqualTo(ownerId);
        assertThat(account.getAccountType()).isEqualTo(AccountType.CUSTOMER);
        assertThat(account.getCurrency()).isEqualTo("USD");
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.getMaterializedBalanceMinor()).isEqualTo(0L);
    }

    @Test
    @DisplayName("createAccount should reject CUSTOMER creating MERCHANT account")
    void createAccount_customerCreatingMerchant_fails() {
        assertThatThrownBy(() -> accountService.createAccount(UUID.randomUUID(), AccountType.MERCHANT, "USD", "CUSTOMER"))
                .isInstanceOf(AccountDomainException.class)
                .hasMessageContaining("Customers can only create CUSTOMER accounts");
    }

    @Test
    @DisplayName("createAccount should reject ordinary user creating FEES account")
    void createAccount_customerCreatingFees_fails() {
        assertThatThrownBy(() -> accountService.createAccount(UUID.randomUUID(), AccountType.FEES, "USD", "CUSTOMER"))
                .isInstanceOf(AccountDomainException.class)
                .hasMessageContaining("Customers can only create CUSTOMER accounts");
    }

    @Test
    @DisplayName("getAccount should return account for owner")
    void getAccount_owner_success() {
        UUID ownerId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        AccountEntity account = new AccountEntity("ACC-1", ownerId, AccountType.CUSTOMER, "USD", AccountStatus.ACTIVE);
        when(accountRepository.findByIdAndOwnerId(accountId, ownerId)).thenReturn(Optional.of(account));

        AccountEntity result = accountService.getAccount(accountId, ownerId, "CUSTOMER");
        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("getAccount should reject if owner doesn't match")
    void getAccount_wrongOwner_fails() {
        UUID ownerId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        when(accountRepository.findByIdAndOwnerId(accountId, ownerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.getAccount(accountId, ownerId, "CUSTOMER"))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    @DisplayName("freezeAccount should transition status to FROZEN")
    void freezeAccount_success() {
        UUID accountId = UUID.randomUUID();
        AccountEntity account = new AccountEntity("ACC-1", UUID.randomUUID(), AccountType.CUSTOMER, "USD", AccountStatus.ACTIVE);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        accountService.freezeAccount(accountId, "Security reason");

        assertThat(account.getStatus()).isEqualTo(AccountStatus.FROZEN);
        verify(accountRepository).save(account);
    }

    @Test
    @DisplayName("unfreezeAccount should transition status to ACTIVE")
    void unfreezeAccount_success() {
        UUID accountId = UUID.randomUUID();
        AccountEntity account = new AccountEntity("ACC-1", UUID.randomUUID(), AccountType.CUSTOMER, "USD", AccountStatus.FROZEN);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        accountService.unfreezeAccount(accountId, "Security cleared");

        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        verify(accountRepository).save(account);
    }

    @Test
    @DisplayName("freezeAccount should fail if already CLOSED")
    void freezeAccount_closedAccount_fails() {
        UUID accountId = UUID.randomUUID();
        AccountEntity account = new AccountEntity("ACC-1", UUID.randomUUID(), AccountType.CUSTOMER, "USD", AccountStatus.CLOSED);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> accountService.freezeAccount(accountId, "Reason"))
                .isInstanceOf(IllegalStateException.class);
    }
}
