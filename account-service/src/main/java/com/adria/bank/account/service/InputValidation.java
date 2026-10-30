package com.adria.bank.account.service;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

/** Bean Validation for transport adapters before calling the shared service. */
@Component
public class InputValidation {
    private final Validator validator;
    public InputValidation(Validator validator) { this.validator = validator; }
    public <T> T validate(T input) {
        if (input == null) { throw new IllegalArgumentException("Input is required."); }
        var errors = validator.validate(input);
        if (!errors.isEmpty()) { throw new ConstraintViolationException(errors); }
        return input;
    }
}
