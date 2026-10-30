package com.adria.bank.account.web.rest.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record AmountRequest(
        @NotNull @Positive @Digits(integer = 12, fraction = 2) BigDecimal amount) {
}
