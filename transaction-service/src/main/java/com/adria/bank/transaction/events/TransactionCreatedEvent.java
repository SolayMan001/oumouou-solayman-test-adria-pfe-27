package com.adria.bank.transaction.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import com.adria.bank.transaction.domain.BankTransaction;

public record TransactionCreatedEvent(UUID eventId, String eventType, UUID transactionId,
        UUID accountId, BigDecimal amount, String currency, Instant occurredAt) {
    public static final String TOPIC = "bank.transactions.created";
    public static TransactionCreatedEvent from(BankTransaction transaction) {
        return new TransactionCreatedEvent(UUID.randomUUID(), "TransactionCreated", transaction.getId(),
                transaction.getAccountId(), transaction.getAmount(), transaction.getCurrency(), transaction.getCreatedAt());
    }
}
