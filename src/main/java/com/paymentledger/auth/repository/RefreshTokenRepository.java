package com.paymentledger.auth.repository;

import com.paymentledger.auth.domain.RefreshTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link RefreshTokenEntity}.
 * Supports atomic token rotation via concurrency-safe revocation queries.
 */
@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshTokenEntity, UUID> {

    Optional<RefreshTokenEntity> findByTokenHash(String tokenHash);

    /**
     * Atomically revoke a refresh token only if it is not already revoked.
     * Returns the number of rows updated (0 or 1).
     * This ensures exactly-once consumption under concurrent requests.
     */
    @Modifying
    @Query("UPDATE RefreshTokenEntity r SET r.revoked = true WHERE r.tokenHash = :tokenHash AND r.revoked = false")
    int revokeByTokenHashIfNotRevoked(@Param("tokenHash") String tokenHash);
}
