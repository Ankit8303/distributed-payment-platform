package com.paymentledger.auth.domain;

/**
 * Platform user roles as defined by the frozen Phase 0 security model.
 * Maps to Spring Security granted authorities as ROLE_{name}.
 *
 * @see docs/product/09-security-model.md §3
 */
public enum Role {
    CUSTOMER,
    MERCHANT,
    ADMIN,
    SYSTEM
}
