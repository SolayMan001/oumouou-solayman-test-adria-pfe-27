package com.adria.bank.transaction.service;

import java.math.BigDecimal;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "bank.transaction")
public record TransactionProperties(
        @NotNull @Positive @Digits(integer = 12, fraction = 2) BigDecimal maxAmount) {
}
