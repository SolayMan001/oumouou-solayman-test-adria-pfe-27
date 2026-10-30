package com.adria.bank.account.service;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.UUID;
import com.adria.bank.account.domain.Account;
import com.adria.bank.account.domain.AccountConflictException;
import com.adria.bank.account.domain.DebitOperation;
import com.adria.bank.account.repository.AccountRepository;
import com.adria.bank.account.repository.DebitOperationRepository;
import jakarta.validation.constraints.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

@Service
@Validated
public class DebitOperationService {
    private final AccountRepository accounts;
    private final DebitOperationRepository operations;

    public DebitOperationService(AccountRepository accounts, DebitOperationRepository operations) {
        this.accounts = accounts;
        this.operations = operations;
    }

    @Transactional
    public DebitOperation debit(@NotNull UUID accountId, @NotNull UUID operationId,
            @NotNull @Positive @Digits(integer = 12, fraction = 2) BigDecimal amount,
            @NotBlank @Pattern(regexp = "[A-Za-z]{3}") String currency) {
        Account account = lock(accountId);
        String normalized = currency.toUpperCase(Locale.ROOT);
        DebitOperation existing = operations.findById(operationId).orElse(null);
        if (existing != null) {
            existing.verify(accountId, amount, normalized);
            if (existing.getState() != DebitOperation.State.DEBITED) {
                throw new AccountConflictException("OPERATION_CANCELLED", "The debit operation has already been reversed or cancelled.");
            }
            return existing;
        }
        if (!account.getCurrency().equals(normalized)) {
            throw new AccountConflictException("CURRENCY_MISMATCH", "Transaction currency must match account currency.");
        }
        account.debit(amount);
        // The balance and unique ledger row commit in the same database transaction.
        return operations.saveAndFlush(new DebitOperation(operationId, accountId, amount, normalized, DebitOperation.State.DEBITED));
    }

    @Transactional
    public DebitOperation compensate(@NotNull UUID accountId, @NotNull UUID operationId,
            @NotNull @Positive @Digits(integer = 12, fraction = 2) BigDecimal amount,
            @NotBlank @Pattern(regexp = "[A-Za-z]{3}") String currency) {
        Account account = lock(accountId);
        String normalized = currency.toUpperCase(Locale.ROOT);
        DebitOperation operation = operations.findById(operationId).orElse(null);
        if (operation == null) {
            // A durable tombstone prevents a delayed debit from executing after cancellation.
            return operations.saveAndFlush(new DebitOperation(operationId, accountId, amount, normalized, DebitOperation.State.CANCELLED));
        }
        operation.verify(accountId, amount, normalized);
        if (operation.getState() == DebitOperation.State.DEBITED) {
            account.reverseDebit(operation.getAmount());
            operation.compensate();
        }
        return operation;
    }

    @Transactional(readOnly = true)
    public DebitOperation get(UUID accountId, UUID operationId) {
        return operations.findById(operationId).filter(op -> op.getAccountId().equals(accountId))
                .orElseThrow(() -> new DebitOperationNotFoundException(operationId));
    }

    private Account lock(UUID id) {
        return accounts.findByIdForUpdate(id).orElseThrow(() -> new AccountNotFoundException(id));
    }
}
