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
 * The integrated workflow confirms the debit ledger before using this component.
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
        return persist(command, null);
    }

    @Transactional
    public BankTransaction persist(TransactionCommand command, java.util.UUID operationId) {
        validator.validate(command);
        BankTransaction transaction = new BankTransaction(command.accountId(), command.amount(), command.currency(),
                command.merchantCategory(), command.country());
        if (operationId != null) { transaction.assignOperation(operationId); }
        return repository.saveAndFlush(transaction);
    }
}
