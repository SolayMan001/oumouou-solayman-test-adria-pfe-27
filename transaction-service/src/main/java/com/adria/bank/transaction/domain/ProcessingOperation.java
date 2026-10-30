package com.adria.bank.transaction.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import com.adria.bank.transaction.service.TransactionCommand;
import com.adria.bank.transaction.service.TransactionProcessingException;
import jakarta.persistence.*;

@Entity
@Table(name = "processing_operations")
public class ProcessingOperation {
    public enum State { PENDING, UNCERTAIN, COMPLETED, REJECTED, CANCELLED }
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
    @Column(nullable = false, length = 100, updatable = false)
    private String merchantCategory;
    @Column(nullable = false, length = 100, updatable = false)
    private String country;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private State state;
    private UUID transactionId;
    private Integer errorStatus;
    private String errorCode;
    @Column(length = 1000)
    private String detail;
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected ProcessingOperation() { }
    public ProcessingOperation(UUID id, TransactionCommand command) {
        this.id = id;
        this.accountId = command.accountId();
        this.amount = command.amount().setScale(2);
        this.currency = command.currency().toUpperCase(Locale.ROOT);
        this.merchantCategory = command.merchantCategory().trim();
        this.country = command.country().trim().toUpperCase(Locale.ROOT);
        state = State.PENDING;
        createdAt = Instant.now();
    }
    public void verify(TransactionCommand command) {
        ProcessingOperation proposed = new ProcessingOperation(id, command);
        if (!accountId.equals(proposed.accountId) || amount.compareTo(proposed.amount) != 0
                || !currency.equals(proposed.currency) || !merchantCategory.equals(proposed.merchantCategory)
                || !country.equals(proposed.country)) {
            throw new TransactionProcessingException(409, "IDEMPOTENCY_CONFLICT", "Idempotency-Key is already bound to different transaction details.");
        }
    }
    public TransactionCommand command() { return new TransactionCommand(accountId, amount, currency, merchantCategory, country); }
    public void complete(UUID transactionId) { this.transactionId = transactionId; state = State.COMPLETED; }
    public void uncertain() { state = State.UNCERTAIN; }
    public void cancel() { state = State.CANCELLED; }
    public void reject(int status, String code, String detail) {
        state = State.REJECTED; errorStatus = status; errorCode = code; this.detail = detail;
    }
    public UUID getId() { return id; }
    public State getState() { return state; }
    public UUID getTransactionId() { return transactionId; }
    public Integer getErrorStatus() { return errorStatus; }
    public String getErrorCode() { return errorCode; }
    public String getDetail() { return detail; }
    public Instant getCreatedAt() { return createdAt; }
}
