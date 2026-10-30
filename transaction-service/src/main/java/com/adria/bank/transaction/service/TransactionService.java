package com.adria.bank.transaction.service;

import java.util.List;
import java.util.UUID;

import com.adria.bank.transaction.domain.BankTransaction;
import com.adria.bank.transaction.repository.TransactionRepository;
import jakarta.validation.constraints.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

@Service
@Validated
@Transactional(readOnly = true)
public class TransactionService {

    private final TransactionRepository repository;
    private final TransactionValidator validator;
    private final TransactionWorkflow workflow;

    public TransactionService(TransactionRepository repository, TransactionValidator validator, TransactionWorkflow workflow) {
        this.repository = repository;
        this.validator = validator;
        this.workflow = workflow;
    }

    public List<BankTransaction> list() {
        return repository.findAll();
    }

    public BankTransaction get(@NotNull UUID id) {
        return repository.findById(id).orElseThrow(() -> new TransactionNotFoundException(id));
    }

    public List<BankTransaction> listByAccount(@NotNull UUID accountId) {
        // This filters locally stored records; it does not verify account existence.
        return repository.findByAccountId(accountId);
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public BankTransaction create(@NotNull UUID requestId, TransactionCommand command) {
        validator.validate(command);
        return workflow.create(requestId, command);
    }
}
