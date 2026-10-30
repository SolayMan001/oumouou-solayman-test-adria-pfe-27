package com.adria.bank.transaction.web.graphql;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import com.adria.bank.transaction.repository.TransactionRepository;
import com.adria.bank.transaction.service.TransactionCommand;
import com.adria.bank.transaction.service.internal.TransactionPersistence;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TransactionGraphqlTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired TransactionRepository transactions;
    @Autowired TransactionPersistence persistence;
    @BeforeEach void clean() { transactions.deleteAll(); }

    @Test
    void allReadQueriesUseSharedPersistence() throws Exception {
        UUID account = UUID.randomUUID();
        var saved = persistence.persist(new TransactionCommand(account, new BigDecimal("10.25"), "MAD", "FOOD", "MA"));
        var response = mapper.readTree(graphql("{ transactionById(id:\"" + saved.getId() + "\") { id amount } transactions { id } transactionsByAccountId(accountId:\"" + account + "\") { id } }"));
        assertThat(response.path("errors").isMissingNode()).isTrue();
        assertThat(response.at("/data/transactionById/amount").asText()).isEqualTo("10.25");
        assertThat(response.at("/data/transactionsByAccountId").size()).isEqualTo(1);
        assertThat(response.at("/data/transactions").size()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "1000.01", "1.001"})
    void invalidAmountsDoNotPersist(String amount) throws Exception {
        var response = mapper.readTree(graphql(mutation(amount)));
        assertThat(response.at("/errors/0/extensions/httpStatus").asInt()).isEqualTo(400);
        assertThat(transactions.count()).isZero();
    }

    @Test
    void unavailableDependencyProducesGraphqlErrorWithoutSuccess() throws Exception {
        var response = mapper.readTree(graphql(mutation("10.00")));
        assertThat(response.at("/errors/0/extensions/httpStatus").asInt()).isEqualTo(503);
        assertThat(transactions.count()).isZero();
    }

    @Test
    void keyIsRequiredBySchemaAndMissingTransactionIsReported() throws Exception {
        var response = mapper.readTree(graphql("{ transactionById(id:\"" + UUID.randomUUID() + "\") { id } }"));
        assertThat(response.at("/errors/0/extensions/httpStatus").asInt()).isEqualTo(404);
        response = mapper.readTree(graphql(mutation("10").replaceAll(",idempotencyKey:\"[^\"]+\"", "")));
        assertThat(response.path("errors").isEmpty()).isFalse();
        assertThat(transactions.count()).isZero();
    }

    private String mutation(String amount) {
        return "mutation { createTransaction(input:{accountId:\"" + UUID.randomUUID() + "\",amount:\"" + amount
                + "\",currency:\"MAD\",merchantCategory:\"FOOD\",country:\"MA\"},idempotencyKey:\"" + UUID.randomUUID() + "\") { id } }";
    }
    private String graphql(String query) throws Exception {
        return mvc.perform(post("/graphql").contentType("application/json").content(mapper.writeValueAsString(Map.of("query", query))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }
}
