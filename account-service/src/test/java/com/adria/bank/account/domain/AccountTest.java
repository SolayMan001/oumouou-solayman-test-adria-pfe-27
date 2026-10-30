package com.adria.bank.account.domain;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountTest {

    @Test
    void decimalArithmeticDoesNotAccumulateBinaryRoundingErrors() {
        Account account = new Account("customer", "MAD", new BigDecimal("0.10"));
        account.credit(new BigDecimal("0.20"));
        assertThat(account.getBalance()).isEqualTo(0.30);
        account.debit(new BigDecimal("0.30"));
        assertThat(account.getBalance()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.01", "0.001", "1000000000000"})
    @NullSource
    void invalidBalancesAreRejectedEvenOutsideRest(String balance) {
        BigDecimal value = balance == null ? null : new BigDecimal(balance);
        assertThatThrownBy(() -> new Account("customer", "MAD", value))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "0.001", "1000000000000"})
    @NullSource
    void invalidAmountsAreRejectedEvenOutsideRest(String amount) {
        Account account = new Account("customer", "MAD", new BigDecimal("100.00"));
        BigDecimal value = amount == null ? null : new BigDecimal(amount);
        assertThatThrownBy(() -> account.debit(value)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> account.credit(value)).isInstanceOf(IllegalArgumentException.class);
        assertThat(account.getBalance()).isEqualTo(100.0);
    }
}
