package com.adria.bank.kafkaconsumer.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import static org.assertj.core.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class TransactionEventListenerTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final TransactionEventListener listener = new TransactionEventListener(mapper);

    @Test
    void validEventLogsAllRequiredFieldsAndDuplicateDeliveryOnlyLogsAgain(CapturedOutput output) throws Exception {
        var event = new TransactionCreatedEvent(UUID.randomUUID(), "TransactionCreated", UUID.randomUUID(),
                UUID.randomUUID(), new BigDecimal("10.25"), "MAD", Instant.parse("2026-10-30T12:00:00Z"));
        var record = new ConsumerRecord<>("bank.transactions.created", 0, 1L, event.accountId().toString(), mapper.writeValueAsString(event));
        listener.consume(record);
        listener.consume(record);
        assertThat(output.getOut()).contains(event.eventId().toString(), event.transactionId().toString(),
                event.accountId().toString(), "10.25", "MAD", "2026-10-30T12:00:00Z");
    }

    @Test
    void malformedIncompleteAndNullRecordsAreSkippedWithoutCrashing(CapturedOutput output) {
        for (String payload : new String[]{"{broken", "{}", "null", null}) {
            assertThatCode(() -> listener.consume(new ConsumerRecord<>("bank.transactions.created", 0, 2L, "bad-key", payload)))
                    .doesNotThrowAnyException();
        }
        assertThat(output.getOut()).contains("Skipping malformed transaction event");
    }
}
