package com.adria.bank.account.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.adria.bank.account.domain.Account;
import com.adria.bank.account.domain.AccountStatus;
import com.adria.bank.account.repository.AccountRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

@Service
@Validated
@Transactional(readOnly = true)
public class AccountService {

    private final AccountRepository repository;

    public AccountService(AccountRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public Account create(@NotBlank @Size(max = 100) String customerReference,
                          @NotBlank @Pattern(regexp = "[A-Za-z]{3}") String currency,
                          BigDecimal initialBalance) {
        return repository.save(new Account(customerReference, currency, initialBalance));
    }

    public List<Account> list() {
        return repository.findAll();
    }

    public Account get(@NotNull UUID id) {
        return repository.findById(id).orElseThrow(() -> new AccountNotFoundException(id));
    }

    @Transactional
    public Account updateStatus(@NotNull UUID id, @NotNull AccountStatus status) {
        Account account = getForUpdate(id);
        account.changeStatus(status);
        return account;
    }

    @Transactional
    public Account debit(@NotNull UUID id, BigDecimal amount) {
        Account account = getForUpdate(id);
        account.debit(amount);
        return account;
    }

    @Transactional
    public Account credit(@NotNull UUID id, BigDecimal amount) {
        Account account = getForUpdate(id);
        account.credit(amount);
        return account;
    }

    private Account getForUpdate(UUID id) {
        // The database holds this lock through dirty checking and transaction commit.
        // Status updates use the same lock to coordinate with balance operations.
        return repository.findByIdForUpdate(id).orElseThrow(() -> new AccountNotFoundException(id));
    }
}
