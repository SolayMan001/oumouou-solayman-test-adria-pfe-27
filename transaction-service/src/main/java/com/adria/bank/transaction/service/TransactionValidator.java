package com.adria.bank.transaction.service;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

@Component
public class TransactionValidator {

    private final Validator validator;
    private final TransactionProperties properties;

    public TransactionValidator(Validator validator, TransactionProperties properties) {
        this.validator = validator;
        this.properties = properties;
    }

    public void validate(TransactionCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("Transaction details are required.");
        }
        var violations = validator.validate(command);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
        if (command.amount().compareTo(properties.maxAmount()) > 0) {
            throw new IllegalArgumentException("Amount must not exceed the configured maximum of "
                    + properties.maxAmount().toPlainString() + ".");
        }
    }
}
