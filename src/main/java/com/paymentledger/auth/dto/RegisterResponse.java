package com.paymentledger.auth.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;
import java.util.UUID;

/**
 * Registration response DTO.
 * <p>
 * Does NOT include defaultAccountId — account creation belongs to Phase 4.
 *
 * @see docs/product/07-api-contract.md §3.1
 */
public class RegisterResponse {

    private UUID userId;
    private String email;
    private String role;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant createdAt;

    public RegisterResponse() {
    }

    public RegisterResponse(UUID userId, String email, String role, Instant createdAt) {
        this.userId = userId;
        this.email = email;
        this.role = role;
        this.createdAt = createdAt;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
