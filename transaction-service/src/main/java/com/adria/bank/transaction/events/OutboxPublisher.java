package com.adria.bank.transaction.events;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * At-least-once delivery: acknowledge Kafka before marking a row published.
 * A crash between these commits can redeliver the SAME eventId. No banking mutation is retried.
 * Pending rows and their payloads survive Kafka outages and service restarts in the local database.
 */
@Service
public class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private final OutboxRepository repository;
    private final KafkaTemplate<String, String> kafka;
    public OutboxPublisher(OutboxRepository repository, KafkaTemplate<String, String> kafka) {
        this.repository = repository; this.kafka = kafka;
    }

    @Transactional
    public void publish(UUID eventId) {
        var event = repository.lock(eventId).orElse(null);
        if (event == null || event.getPublishedAt() != null) { return; }
        event.attempted();
        try {
            kafka.send(TransactionCreatedEvent.TOPIC, event.getAccountId().toString(), event.getPayload()).get(7, TimeUnit.SECONDS);
            event.published();
            log.info("Published TransactionCreated eventId={} accountId={}", eventId, event.getAccountId());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            log.warn("Outbox eventId={} remains pending after interruption", eventId);
        } catch (Exception unavailable) {
            log.warn("Outbox eventId={} remains pending; publish failed ({})", eventId, unavailable.getClass().getSimpleName());
        }
    }
}
