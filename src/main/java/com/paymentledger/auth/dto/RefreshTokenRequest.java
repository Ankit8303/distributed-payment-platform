package com.paymentledger.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Refresh token request DTO.
 */
public class RefreshTokenRequest {

    @NotBlank(message = "Refresh token is required")
    private String refreshToken;

    public RefreshTokenRequest() {
    }

    public RefreshTokenRequest(String refreshToken) {
        this.refreshToken = refreshToken;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }

    /**
     * Excludes refresh token from string representation to prevent leakage.
     */
    @Override
    public String toString() {
        return "RefreshTokenRequest{refreshToken='***MASKED***'}";
    }
}
