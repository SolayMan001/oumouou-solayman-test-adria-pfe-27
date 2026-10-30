package com.adria.bank.account.domain;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 100)
    private String customerReference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private AccountStatus status;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false)
    private double balance;

    protected Account() {
    }

    public Account(String customerReference, String currency, BigDecimal initialBalance) {
        this.customerReference = customerReference.trim();
        this.currency = currency.toUpperCase(Locale.ROOT);
        this.status = AccountStatus.ACTIVE;
        this.balance = Money.balance(initialBalance).doubleValue();
    }

    public void changeStatus(AccountStatus status) {
        this.status = Objects.requireNonNull(status, "Status is required.");
    }

    public void debit(BigDecimal amount) {
        BigDecimal debit = Money.amount(amount);
        requireActive();
        BigDecimal currentBalance = decimalBalance();
        if (currentBalance.compareTo(debit) < 0) {
            throw new AccountConflictException("INSUFFICIENT_BALANCE", "Account has insufficient balance.");
        }
        balance = Money.balance(currentBalance.subtract(debit)).doubleValue();
    }

    public void credit(BigDecimal amount) {
        BigDecimal credit = Money.amount(amount);
        requireActive();
        balance = Money.balance(decimalBalance().add(credit)).doubleValue();
    }

    private void requireActive() {
        if (status != AccountStatus.ACTIVE) {
            throw new AccountConflictException("ACCOUNT_BLOCKED", "Only ACTIVE accounts can be debited or credited.");
        }
    }

    private BigDecimal decimalBalance() {
        return Money.balance(BigDecimal.valueOf(balance));
    }

    public UUID getId() {
        return id;
    }

    public String getCustomerReference() {
        return customerReference;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public String getCurrency() {
        return currency;
    }

    public double getBalance() {
        return balance;
    }
}
