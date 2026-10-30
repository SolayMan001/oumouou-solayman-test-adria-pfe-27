package com.adria.bank.account.service;

import java.math.BigDecimal;
import java.util.UUID;
import jakarta.validation.constraints.*;

public record TransferCommand(@NotNull UUID sourceAccountId, @NotNull UUID destinationAccountId,
        @NotNull @Positive @Digits(integer = 12, fraction = 2) BigDecimal amount) { }
