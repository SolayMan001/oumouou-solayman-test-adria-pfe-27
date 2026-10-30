package com.adria.bank.account.web.rest.dto;

import com.adria.bank.account.domain.AccountStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateAccountStatusRequest(@NotNull AccountStatus status) {
}
