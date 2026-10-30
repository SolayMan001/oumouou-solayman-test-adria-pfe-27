package com.adria.bank.transaction.service;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Shared input for future interfaces and the internal persistence component. */
public record TransactionCommand(
        @NotNull UUID accountId,
        @NotNull @Positive @Digits(integer = 12, fraction = 2) BigDecimal amount,
        @NotBlank @Pattern(regexp = "[A-Za-z]{3}", message = "must be a three-letter currency code") String currency,
        @NotBlank @Size(max = 100) String merchantCategory,
        @NotBlank @Size(max = 100) String country) {
}
