package com.adria.bank.account.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import com.adria.bank.account.service.TransferCommand;
import jakarta.persistence.*;

/** Immutable receipt committed atomically with both account balance updates. */
@Entity
@Table(name = "account_transfers")
public class AccountTransfer {
    @Id
    private UUID id;
    @Version
    private Long version;
    @Column(nullable = false, updatable = false)
    private UUID sourceAccountId;
    @Column(nullable = false, updatable = false)
    private UUID destinationAccountId;
    @Column(nullable = false, precision = 14, scale = 2, updatable = false)
    private BigDecimal amount;
    @Column(nullable = false, length = 3, updatable = false)
    private String currency;
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected AccountTransfer() { }
    public AccountTransfer(UUID key, TransferCommand command, String currency) {
        id = key; sourceAccountId = command.sourceAccountId(); destinationAccountId = command.destinationAccountId();
        amount = command.amount().setScale(2); this.currency = currency;
        createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
    public void verify(TransferCommand command) {
        if (!sourceAccountId.equals(command.sourceAccountId()) || !destinationAccountId.equals(command.destinationAccountId())
                || amount.compareTo(command.amount()) != 0) {
            throw new AccountConflictException("IDEMPOTENCY_CONFLICT", "Transfer key is already bound to different transfer details.");
        }
    }
    public UUID getId() { return id; }
    public UUID getSourceAccountId() { return sourceAccountId; }
    public UUID getDestinationAccountId() { return destinationAccountId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public Instant getCreatedAt() { return createdAt; }
}
