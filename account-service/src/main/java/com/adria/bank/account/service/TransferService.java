package com.adria.bank.account.service;

import java.util.UUID;
import com.adria.bank.account.domain.*;
import com.adria.bank.account.repository.AccountRepository;
import com.adria.bank.account.repository.TransferRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

@Service
@Validated
public class TransferService {
    private final AccountRepository accounts;
    private final TransferRepository transfers;
    public TransferService(AccountRepository accounts, TransferRepository transfers) {
        this.accounts = accounts; this.transfers = transfers;
    }

    @Transactional
    public AccountTransfer transfer(@NotNull UUID key, @NotNull @Valid TransferCommand command) {
        if (command.sourceAccountId().equals(command.destinationAccountId())) {
            throw new IllegalArgumentException("Source and destination accounts must differ.");
        }
        var existing = transfers.findById(key);
        if (existing.isPresent()) { existing.get().verify(command); return existing.get(); }

        // Every transfer acquires individual row locks in the same total UUID order.
        UUID first = command.sourceAccountId().compareTo(command.destinationAccountId()) < 0
                ? command.sourceAccountId() : command.destinationAccountId();
        UUID second = first.equals(command.sourceAccountId()) ? command.destinationAccountId() : command.sourceAccountId();
        Account a = lock(first);
        Account b = lock(second);
        // A concurrent identical request may have completed while these locks were awaited.
        existing = transfers.findById(key);
        if (existing.isPresent()) { existing.get().verify(command); return existing.get(); }
        Account source = a.getId().equals(command.sourceAccountId()) ? a : b;
        Account destination = source == a ? b : a;
        if (source.getStatus() != AccountStatus.ACTIVE || destination.getStatus() != AccountStatus.ACTIVE) {
            throw new AccountConflictException("ACCOUNT_BLOCKED", "Both transfer accounts must be ACTIVE.");
        }
        if (!source.getCurrency().equals(destination.getCurrency())) {
            throw new AccountConflictException("CURRENCY_MISMATCH", "Transfer account currencies must match.");
        }
        source.debit(command.amount());
        destination.credit(command.amount());
        // If credit, ledger insertion or commit fails, BOTH balance changes roll back.
        return transfers.saveAndFlush(new AccountTransfer(key, command, source.getCurrency()));
    }

    @Transactional(readOnly = true)
    public AccountTransfer get(@NotNull UUID id) {
        return transfers.findById(id).orElseThrow(() -> new TransferNotFoundException(id));
    }
    private Account lock(UUID id) {
        return accounts.findByIdForUpdate(id).orElseThrow(() -> new AccountNotFoundException(id));
    }
}
