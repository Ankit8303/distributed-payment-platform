package com.paymentledger.auth.repository;

import com.paymentledger.auth.domain.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link UserEntity}.
 */
@Repository
public interface UserRepository extends JpaRepository<UserEntity, UUID> {

    Optional<UserEntity> findByEmail(String email);

    boolean existsByEmail(String email);

    org.springframework.data.domain.Page<UserEntity> findByRole(com.paymentledger.auth.domain.Role role, org.springframework.data.domain.Pageable pageable);

    org.springframework.data.domain.Page<UserEntity> findByStatus(com.paymentledger.auth.domain.UserStatus status, org.springframework.data.domain.Pageable pageable);

    org.springframework.data.domain.Page<UserEntity> findByEmailContainingIgnoreCase(String email, org.springframework.data.domain.Pageable pageable);

    long countByStatus(com.paymentledger.auth.domain.UserStatus status);
}
