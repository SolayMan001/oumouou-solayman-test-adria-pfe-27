package com.adria.bank.account.web.rest.dto;

import java.util.UUID;

import com.adria.bank.account.domain.Account;
import com.adria.bank.account.domain.AccountStatus;

public record AccountResponse(UUID id, String customerReference, AccountStatus status,
                              String currency, double balance) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(account.getId(), account.getCustomerReference(), account.getStatus(),
                account.getCurrency(), account.getBalance());
    }
}
