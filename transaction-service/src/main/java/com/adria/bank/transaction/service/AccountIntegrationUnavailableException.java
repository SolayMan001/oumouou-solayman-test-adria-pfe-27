package com.adria.bank.transaction.service;

public class AccountIntegrationUnavailableException extends RuntimeException {

    public AccountIntegrationUnavailableException() {
        super("Transaction processing is unavailable until Account Service integration is implemented in C3. "
                + "Account verification and balance updates have not been performed; no transaction was saved.");
    }
}
