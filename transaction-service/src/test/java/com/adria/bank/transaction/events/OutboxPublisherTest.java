package com.adria.bank.transaction.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OutboxPublisherTest {
    @Test
    @SuppressWarnings("unchecked")
    void kafkaFailureRetainsEventAndLaterDeliveryUsesSameKeyAndPayload() {
        var repository = mock(OutboxRepository.class);
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        var event = new TransactionCreatedEvent(UUID.randomUUID(), "TransactionCreated", UUID.randomUUID(),
                UUID.randomUUID(), BigDecimal.TEN, "MAD", Instant.now());
        String payload = "{\"eventId\":\"" + event.eventId() + "\"}";
        var row = new OutboxEvent(event, payload);
        when(repository.lock(event.eventId())).thenReturn(Optional.of(row));
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new TimeoutException("Broker unavailable"));
        when(kafka.send(TransactionCreatedEvent.TOPIC, event.accountId().toString(), payload))
                .thenReturn(failed).thenReturn(CompletableFuture.completedFuture(null));
        var publisher = new OutboxPublisher(repository, kafka);
        publisher.publish(event.eventId());
        assertThat(row.getPublishedAt()).isNull();
        publisher.publish(event.eventId());
        assertThat(row.getPublishedAt()).isNotNull();
        publisher.publish(event.eventId());
        assertThat(row.getAttempts()).isEqualTo(2);
        verify(kafka, times(2)).send(TransactionCreatedEvent.TOPIC, event.accountId().toString(), payload);
    }
}
