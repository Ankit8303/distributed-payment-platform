package com.paymentledger.auth.domain;

/**
 * User account status as defined by the frozen Phase 0 database model.
 *
 * @see docs/product/06-database-model.md §2.1
 */
public enum UserStatus {
    ACTIVE,
    SUSPENDED,
    DEACTIVATED
}
