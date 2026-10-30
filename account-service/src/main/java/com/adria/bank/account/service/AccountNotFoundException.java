package com.adria.bank.account.service;

import java.util.UUID;

public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException(UUID id) {
        super("Account " + id + " was not found.");
    }
}
