package com.adria.bank.kafkaconsumer.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Logging only. Duplicate delivery cannot change balances or create transactions. */
@Component
public class TransactionEventListener {
    private static final Logger log = LoggerFactory.getLogger(TransactionEventListener.class);
    private final ObjectMapper mapper;
    public TransactionEventListener(ObjectMapper mapper) { this.mapper = mapper; }

    @KafkaListener(topics = "bank.transactions.created")
    public void consume(ConsumerRecord<String, String> record) {
        try {
            TransactionCreatedEvent event = mapper.readValue(record.value(), TransactionCreatedEvent.class);
            if (event == null || !event.valid() || !event.accountId().toString().equals(record.key())) {
                throw new IllegalArgumentException("Invalid TransactionCreated contract or account key");
            }
            log.info("TransactionCreated eventId={} transactionId={} accountId={} amount={} currency={} occurredAt={}",
                    event.eventId(), event.transactionId(), event.accountId(), event.amount(), event.currency(), event.occurredAt());
        } catch (Exception malformed) {
            // Invalid records are deliberately skipped with a warning; no infinite retry or banking side effects.
            log.warn("Skipping malformed transaction event topic={} partition={} offset={} reason={}",
                    record.topic(), record.partition(), record.offset(), malformed.getClass().getSimpleName());
        }
    }
}
