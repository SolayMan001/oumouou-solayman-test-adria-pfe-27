package com.adria.bank.account.web.rest.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateAccountRequest(
        @NotBlank @Size(max = 100) String customerReference,
        @NotBlank @Pattern(regexp = "[A-Za-z]{3}", message = "must be a three-letter currency code") String currency,
        @NotNull @DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal balance) {
}
