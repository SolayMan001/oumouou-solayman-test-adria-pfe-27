package com.adria.bank.account.domain;

public class AccountConflictException extends RuntimeException {

    private final String code;

    public AccountConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
