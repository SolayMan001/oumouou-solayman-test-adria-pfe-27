package com.adria.bank.transaction.service.internal;

import com.adria.bank.transaction.domain.BankTransaction;
import com.adria.bank.transaction.repository.TransactionRepository;
import com.adria.bank.transaction.service.TransactionCommand;
import com.adria.bank.transaction.service.TransactionValidator;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Internal local persistence primitive, not a complete banking operation.
 * It performs no account verification or balance update and is not exposed by REST.
 * A future integrated workflow must complete those prerequisites before using it.
 */
@Component
public class TransactionPersistence {

    private final TransactionRepository repository;
    private final TransactionValidator validator;

    public TransactionPersistence(TransactionRepository repository, TransactionValidator validator) {
        this.repository = repository;
        this.validator = validator;
    }

    @Transactional
    public BankTransaction persist(TransactionCommand command) {
        validator.validate(command);
        return repository.save(new BankTransaction(command.accountId(), command.amount(), command.currency(),
                command.merchantCategory(), command.country()));
    }
}
