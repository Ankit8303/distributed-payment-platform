package com.paymentledger.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Registration request DTO.
 * <p>
 * Role is validated to allow only CUSTOMER or MERCHANT.
 * ADMIN and SYSTEM roles are rejected per the frozen security model.
 *
 * @see docs/product/07-api-contract.md §3.1
 * @see docs/product/09-security-model.md §3 (Threat 3: Privilege Escalation)
 */
public class RegisterRequest {

    @NotBlank(message = "Email is required")
    @Email(message = "Email must be a valid email address")
    private String email;

    @NotBlank(message = "Password is required")
    @Size(min = 12, message = "Password must be at least 12 characters")
    private String password;

    @NotBlank(message = "Role is required")
    private String role;

    public RegisterRequest() {
    }

    public RegisterRequest(String email, String password, String role) {
        this.email = email;
        this.password = password;
        this.role = role;
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

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    /**
     * Excludes password from string representation to prevent leakage.
     */
    @Override
    public String toString() {
        return "RegisterRequest{email='" + email + "', role='" + role + "'}";
    }
}
