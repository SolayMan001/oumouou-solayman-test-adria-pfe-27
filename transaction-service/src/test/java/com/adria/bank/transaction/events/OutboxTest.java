package com.adria.bank.transaction.events;

import java.math.BigDecimal;
import java.util.UUID;
import com.adria.bank.transaction.repository.TransactionRepository;
import com.adria.bank.transaction.service.TransactionCommand;
import com.adria.bank.transaction.service.internal.TransactionPersistence;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OutboxTest {
    @Autowired TransactionPersistence persistence;
    @Autowired TransactionRepository transactions;
    @Autowired OutboxRepository outbox;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired EntityManager entityManager;
    @BeforeEach void clean() { outbox.deleteAll(); transactions.deleteAll(); }

    @Test
    void committedTransactionHasOneDurablePendingEventWithExactContract() throws Exception {
        UUID account = UUID.randomUUID();
        var transaction = persistence.persist(new TransactionCommand(account, new BigDecimal("10.25"), "MAD", "FOOD", "MA"));
        assertThat(outbox.count()).isEqualTo(1);
        var row = outbox.findAll().getFirst();
        var event = mapper.readTree(row.getPayload());
        assertThat(row.getPublishedAt()).isNull();
        assertThat(row.getAccountId()).isEqualTo(account);
        assertThat(event.size()).isEqualTo(7);
        assertThat(event.path("eventId").asText()).isEqualTo(row.getEventId().toString());
        assertThat(event.path("eventType").asText()).isEqualTo("TransactionCreated");
        assertThat(event.path("transactionId").asText()).isEqualTo(transaction.getId().toString());
        assertThat(event.path("accountId").asText()).isEqualTo(account.toString());
        assertThat(event.path("amount").decimalValue()).isEqualByComparingTo("10.25");
        assertThat(event.path("currency").asText()).isEqualTo("MAD");
        assertThat(java.time.Instant.parse(event.path("occurredAt").asText())).isEqualTo(transaction.getCreatedAt());
    }

    @Test
    void transactionRollbackAlsoRemovesItsFlushedEvent() {
        var transaction = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            persistence.persist(new TransactionCommand(UUID.randomUUID(), BigDecimal.TEN, "MAD", "FOOD", "MA"));
            entityManager.flush();
            throw new IllegalStateException("Injected failure before commit");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(transactions.count()).isZero();
        assertThat(outbox.count()).isZero();
    }
}
