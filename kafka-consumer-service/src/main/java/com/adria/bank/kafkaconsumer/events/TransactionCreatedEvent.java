package com.adria.bank.kafkaconsumer.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Wire contract only; this consumer has no account or transaction persistence dependencies. */
public record TransactionCreatedEvent(UUID eventId, String eventType, UUID transactionId,
        UUID accountId, BigDecimal amount, String currency, Instant occurredAt) {
    public boolean valid() {
        return eventId != null && "TransactionCreated".equals(eventType) && transactionId != null
                && accountId != null && amount != null && amount.signum() > 0 && amount.scale() <= 2
                && currency != null && currency.matches("[A-Z]{3}") && occurredAt != null;
    }
}
