package com.adria.bank.transaction.web.rest;

import java.math.BigDecimal;
import java.util.UUID;

import com.adria.bank.transaction.repository.TransactionRepository;
import com.adria.bank.transaction.service.TransactionCommand;
import com.adria.bank.transaction.service.internal.TransactionPersistence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "bank.transaction.max-amount=25.00",
        "spring.datasource.url=jdbc:h2:mem:transaction-limit-tests;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TransactionLimitConfigurationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TransactionPersistence persistence;

    @Autowired
    private TransactionRepository repository;

    @BeforeEach
    void cleanDatabase() {
        repository.deleteAll();
    }

    @Test
    void configuredMaximumIsAppliedToPublicAndInternalCreation() throws Exception {
        UUID accountId = UUID.randomUUID();
        mvc.perform(post("/api/transactions").contentType(MediaType.APPLICATION_JSON)
                        .content(request(accountId, "25.01")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        assertThatThrownBy(() -> persistence.persist(new TransactionCommand(accountId,
                new BigDecimal("25.01"), "MAD", "GROCERIES", "MA")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("25.00");
        assertThat(repository.count()).isZero();
    }

    @Test
    void requestAtConfiguredMaximumStillReturns503AndSavesNothing() throws Exception {
        mvc.perform(post("/api/transactions").contentType(MediaType.APPLICATION_JSON)
                        .content(request(UUID.randomUUID(), "25.00")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ACCOUNT_INTEGRATION_UNAVAILABLE"));
        assertThat(repository.count()).isZero();
    }

    private String request(UUID accountId, String amount) {
        return "{\"accountId\":\"" + accountId + "\",\"amount\":" + amount
                + ",\"currency\":\"MAD\",\"merchantCategory\":\"GROCERIES\",\"country\":\"MA\"}";
    }
}
