package com.adria.bank.account.domain;

import java.math.BigDecimal;
import java.util.UUID;
import jakarta.persistence.*;

@Entity
@Table(name = "debit_operations")
public class DebitOperation {
    public enum State { DEBITED, COMPENSATED, CANCELLED }

    @Id
    private UUID id;
    @Version
    private Long version;
    @Column(nullable = false, updatable = false)
    private UUID accountId;
    @Column(nullable = false, precision = 14, scale = 2, updatable = false)
    private BigDecimal amount;
    @Column(nullable = false, length = 3, updatable = false)
    private String currency;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private State state;

    protected DebitOperation() { }

    public DebitOperation(UUID id, UUID accountId, BigDecimal amount, String currency, State state) {
        this.id = id;
        this.accountId = accountId;
        this.amount = amount;
        this.currency = currency;
        this.state = state;
    }

    public void verify(UUID accountId, BigDecimal amount, String currency) {
        if (!this.accountId.equals(accountId) || this.amount.compareTo(amount) != 0 || !this.currency.equals(currency)) {
            throw new AccountConflictException("OPERATION_CONFLICT", "Operation ID is already bound to a different debit.");
        }
    }

    public void compensate() { state = State.COMPENSATED; }
    public UUID getId() { return id; }
    public UUID getAccountId() { return accountId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public State getState() { return state; }
}
