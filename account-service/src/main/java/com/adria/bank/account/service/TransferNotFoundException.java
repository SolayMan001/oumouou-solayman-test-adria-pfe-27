package com.adria.bank.account.service;

import java.util.UUID;

public class TransferNotFoundException extends RuntimeException {
    public TransferNotFoundException(UUID id) { super("Transfer " + id + " was not found."); }
}
