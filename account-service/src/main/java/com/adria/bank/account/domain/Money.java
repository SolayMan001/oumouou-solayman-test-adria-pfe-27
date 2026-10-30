package com.adria.bank.account.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Decimal arithmetic boundary for the assessment's double balance field. */
final class Money {

    // Below this limit, a double converted with valueOf retains cent precision.
    private static final BigDecimal MAX_BALANCE = new BigDecimal("999999999999.99");

    private Money() {
    }

    static BigDecimal balance(BigDecimal value) {
        BigDecimal normalized = normalize(value);
        if (normalized.signum() < 0 || normalized.compareTo(MAX_BALANCE) > 0) {
            throw new IllegalArgumentException("Balance must be between 0.00 and " + MAX_BALANCE + ".");
        }
        return normalized;
    }

    static BigDecimal amount(BigDecimal value) {
        BigDecimal normalized = balance(value);
        if (normalized.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be strictly positive.");
        }
        return normalized;
    }

    private static BigDecimal normalize(BigDecimal value) {
        if (value == null) {
            throw new IllegalArgumentException("Monetary value is required.");
        }
        try {
            // Reject sub-cent inputs rather than silently changing the requested amount.
            return value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Monetary values must have at most two decimal places.");
        }
    }
}
