package com.adria.bank.transaction.web.rest.dto;

import java.math.BigDecimal;
import java.util.UUID;

import com.adria.bank.transaction.service.TransactionCommand;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateTransactionRequest(
        @NotNull UUID accountId,
        @NotNull @Positive @Digits(integer = 12, fraction = 2) BigDecimal amount,
        @NotBlank @Pattern(regexp = "[A-Za-z]{3}", message = "must be a three-letter currency code") String currency,
        @NotBlank @Size(max = 100) String merchantCategory,
        @NotBlank @Size(max = 100) String country) {

    public TransactionCommand toCommand() {
        return new TransactionCommand(accountId, amount, currency, merchantCategory, country);
    }
}
