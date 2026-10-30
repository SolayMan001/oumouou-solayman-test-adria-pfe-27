package com.adria.bank.account.service;

import java.util.UUID;

public class DebitOperationNotFoundException extends RuntimeException {
    public DebitOperationNotFoundException(UUID id) { super("Debit operation " + id + " was not found."); }
}
