package com.adria.bank.transaction.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "bank_transactions", indexes = @Index(name = "idx_transaction_account", columnList = "account_id"))
public class BankTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(nullable = false, precision = 14, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(nullable = false, length = 100, updatable = false)
    private String merchantCategory;

    @Column(nullable = false, length = 100, updatable = false)
    private String country;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected BankTransaction() {
    }

    public BankTransaction(UUID accountId, BigDecimal amount, String currency,
                           String merchantCategory, String country) {
        this.accountId = accountId;
        this.amount = amount.setScale(2, RoundingMode.UNNECESSARY);
        this.currency = currency.toUpperCase(Locale.ROOT);
        this.merchantCategory = merchantCategory.trim();
        this.country = country.trim().toUpperCase(Locale.ROOT);
    }

    @PrePersist
    private void generateTimestamp() {
        // Match database timestamp precision so returned and reloaded values agree.
        createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public UUID getId() {
        return id;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getMerchantCategory() {
        return merchantCategory;
    }

    public String getCountry() {
        return country;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
