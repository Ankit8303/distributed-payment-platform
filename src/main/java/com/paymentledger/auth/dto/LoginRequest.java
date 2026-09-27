package com.paymentledger.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Login request DTO.
 *
 * @see docs/product/07-api-contract.md §3.1
 */
public class LoginRequest {

    @NotBlank(message = "Email is required")
    private String email;

    @NotBlank(message = "Password is required")
    private String password;

    public LoginRequest() {
    }

    public LoginRequest(String email, String password) {
        this.email = email;
        this.password = password;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    /**
     * Excludes password from string representation to prevent leakage.
     */
    @Override
    public String toString() {
        return "LoginRequest{email='" + email + "'}";
    }
}
