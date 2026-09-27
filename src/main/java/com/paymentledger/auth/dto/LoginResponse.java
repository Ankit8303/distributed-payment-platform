package com.paymentledger.auth.dto;

/**
 * Login response DTO containing JWT access token and refresh token.
 *
 * @see docs/product/07-api-contract.md §3.1
 */
public class LoginResponse {

    private String accessToken;
    private String refreshToken;
    private String tokenType;
    private long expiresInSeconds;

    public LoginResponse() {
    }

    public LoginResponse(String accessToken, String refreshToken, String tokenType, long expiresInSeconds) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.tokenType = tokenType;
        this.expiresInSeconds = expiresInSeconds;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }

    public String getTokenType() {
        return tokenType;
    }

    public void setTokenType(String tokenType) {
        this.tokenType = tokenType;
    }

    public long getExpiresInSeconds() {
        return expiresInSeconds;
    }

    public void setExpiresInSeconds(long expiresInSeconds) {
        this.expiresInSeconds = expiresInSeconds;
    }

    /**
     * Excludes tokens from string representation to prevent leakage.
     */
    @Override
    public String toString() {
        return "LoginResponse{tokenType='" + tokenType + "', expiresInSeconds=" + expiresInSeconds + "}";
    }
}
