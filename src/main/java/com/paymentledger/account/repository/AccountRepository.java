package com.paymentledger.account.repository;

import com.paymentledger.account.domain.AccountEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

@Repository
public interface AccountRepository extends JpaRepository<AccountEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM AccountEntity a WHERE a.id = :id")
    Optional<AccountEntity> findByIdForUpdate(@Param("id") UUID id);

    Optional<AccountEntity> findByIdAndOwnerId(UUID id, UUID ownerId);

    Page<AccountEntity> findByOwnerId(UUID ownerId, Pageable pageable);
    
    boolean existsByAccountNumber(String accountNumber);

    Optional<AccountEntity> findFirstByAccountTypeAndCurrency(com.paymentledger.account.domain.AccountType accountType, String currency);

    Page<AccountEntity> findByStatus(com.paymentledger.account.domain.AccountStatus status, Pageable pageable);

    Page<AccountEntity> findByAccountType(com.paymentledger.account.domain.AccountType accountType, Pageable pageable);

    long countByStatus(com.paymentledger.account.domain.AccountStatus status);
}
