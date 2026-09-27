package com.paymentledger.admin.api.dto;

import com.paymentledger.auth.domain.Role;
import com.paymentledger.auth.domain.UserEntity;
import com.paymentledger.auth.domain.UserStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Administrative view of a user identity.
 * Strictly excludes sensitive credentials such as password hashes.
 */
public record UserAdminResponse(
        UUID id,
        String email,
        Role role,
        UserStatus status,
        Instant createdAt,
        Instant updatedAt
) {
    public static UserAdminResponse fromEntity(UserEntity user) {
        return new UserAdminResponse(
                user.getId(),
                user.getEmail(),
                user.getRole(),
                user.getStatus(),
                user.getCreatedAt(),
                user.getUpdatedAt()
        );
    }
}
