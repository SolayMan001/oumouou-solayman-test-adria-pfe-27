package com.adria.bank.transaction.events;

import com.adria.bank.transaction.domain.BankTransaction;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OutboxRecorder {
    private final OutboxRepository repository;
    private final ObjectMapper mapper;
    public OutboxRecorder(OutboxRepository repository, ObjectMapper mapper) { this.repository = repository; this.mapper = mapper; }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(BankTransaction transaction) {
        var event = TransactionCreatedEvent.from(transaction);
        try { repository.save(new OutboxEvent(event, mapper.writeValueAsString(event))); }
        catch (JsonProcessingException failure) {
            throw new IllegalStateException("Unable to serialize TransactionCreated event; transaction must roll back.", failure);
        }
    }
}
