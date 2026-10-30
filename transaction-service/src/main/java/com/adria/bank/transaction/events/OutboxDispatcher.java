package com.adria.bank.transaction.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "bank.events.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxDispatcher {
    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);
    private final OutboxRepository repository;
    private final OutboxPublisher publisher;
    public OutboxDispatcher(OutboxRepository repository, OutboxPublisher publisher) {
        this.repository = repository; this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${bank.events.poll-interval-ms:5000}", initialDelayString = "${bank.events.initial-delay-ms:5000}")
    public void dispatch() {
        for (var id : repository.pending(PageRequest.of(0, 20))) {
            try { publisher.publish(id); }
            catch (RuntimeException failure) { log.warn("Outbox eventId={} requires another delivery attempt ({})", id, failure.getClass().getSimpleName()); }
        }
    }
}
