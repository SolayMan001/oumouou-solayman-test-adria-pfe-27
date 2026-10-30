package com.adria.bank.transaction.web.rest;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.stream.Stream;

import com.adria.bank.transaction.domain.BankTransaction;
import com.adria.bank.transaction.repository.TransactionRepository;
import com.adria.bank.transaction.service.TransactionCommand;
import com.adria.bank.transaction.service.internal.TransactionPersistence;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TransactionRestControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TransactionRepository repository;

    @Autowired
    private TransactionPersistence persistence;

    @BeforeEach
    void cleanDatabase() {
        repository.deleteAll();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.01", "12.30", "1000.00"})
    void validPublicPostReturns503AndPersistsNothing(String amount) throws Exception {
        ObjectNode body = validRequest();
        body.put("amount", new BigDecimal(amount));
        mvc.perform(post("/api/transactions").header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("ACCOUNT_INTEGRATION_UNAVAILABLE"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("no transaction was saved")))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.createdAt").doesNotExist());
        assertThat(repository.count()).isZero();
        mvc.perform(get("/api/transactions")).andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    @Test
    void unavailablePublicPostDoesNotChangeExistingRecords() throws Exception {
        BankTransaction existing = persist(UUID.randomUUID(), "25.00");
        mvc.perform(post("/api/transactions").header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest().toString()))
                .andExpect(status().isServiceUnavailable());
        assertThat(repository.count()).isEqualTo(1);
        assertThat(repository.findById(existing.getId()).orElseThrow().getAmount())
                .isEqualByComparingTo("25.00");
    }

    @ParameterizedTest
    @MethodSource("invalidFields")
    void invalidPublicPostReturns400AndPersistsNothing(String field, String jsonValue, String code) throws Exception {
        ObjectNode body = validRequest();
        body.set(field, objectMapper.readTree(jsonValue));
        mvc.perform(post("/api/transactions").header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(code));
        assertThat(repository.count()).isZero();
    }

    static Stream<Arguments> invalidFields() {
        return Stream.of(
                Arguments.of("amount", "0", "VALIDATION_ERROR"),
                Arguments.of("amount", "-0.01", "VALIDATION_ERROR"),
                Arguments.of("amount", "1000.01", "INVALID_REQUEST"),
                Arguments.of("amount", "1.001", "VALIDATION_ERROR"),
                Arguments.of("amount", "null", "VALIDATION_ERROR"),
                Arguments.of("amount", "\"NaN\"", "MALFORMED_REQUEST"),
                Arguments.of("amount", "\"Infinity\"", "MALFORMED_REQUEST"),
                Arguments.of("accountId", "null", "VALIDATION_ERROR"),
                Arguments.of("accountId", "\"not-a-uuid\"", "MALFORMED_REQUEST"),
                Arguments.of("currency", "null", "VALIDATION_ERROR"),
                Arguments.of("currency", "\" \"", "VALIDATION_ERROR"),
                Arguments.of("merchantCategory", "null", "VALIDATION_ERROR"),
                Arguments.of("merchantCategory", "\" \"", "VALIDATION_ERROR"),
                Arguments.of("country", "null", "VALIDATION_ERROR"),
                Arguments.of("country", "\" \"", "VALIDATION_ERROR"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"accountId", "amount", "currency", "merchantCategory", "country"})
    void missingMandatoryFieldsReturn400(String field) throws Exception {
        ObjectNode body = validRequest();
        body.remove(field);
        mvc.perform(post("/api/transactions").header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors." + field).isNotEmpty());
        assertThat(repository.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "createdAt"})
    void clientSuppliedIdentifiersAndTimestampsAreRejected(String field) throws Exception {
        ObjectNode body = validRequest();
        body.put(field, field.equals("id") ? UUID.randomUUID().toString() : "2020-01-01T00:00:00Z");
        mvc.perform(post("/api/transactions").header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        assertThat(repository.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{broken", "[]"})
    void malformedRequestsReturn400AndPersistNothing(String body) throws Exception {
        mvc.perform(post("/api/transactions").header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        assertThat(repository.count()).isZero();
    }

    @Test
    void missingTransactionReturns404() throws Exception {
        mvc.perform(get("/api/transactions/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRANSACTION_NOT_FOUND"));
    }

    @Test
    void listingTransactionsWorks() throws Exception {
        BankTransaction first = persist(UUID.randomUUID(), "19.90");
        BankTransaction second = persist(UUID.randomUUID(), "25.00");
        mvc.perform(get("/api/transactions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].id", containsInAnyOrder(first.getId().toString(), second.getId().toString())));
    }

    @Test
    void transactionCanBeRetrievedWithServerGeneratedMetadata() throws Exception {
        BankTransaction saved = persist(UUID.randomUUID(), "19.90");
        mvc.perform(get("/api/transactions/{id}", saved.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(saved.getId().toString()))
                .andExpect(jsonPath("$.accountId").value(saved.getAccountId().toString()))
                .andExpect(jsonPath("$.amount").value(19.9))
                .andExpect(jsonPath("$.currency").value("MAD"))
                .andExpect(jsonPath("$.merchantCategory").value("GROCERIES"))
                .andExpect(jsonPath("$.country").value("MA"))
                .andExpect(jsonPath("$.createdAt").value(saved.getCreatedAt().toString()));
    }

    @Test
    void filteringByAccountIdOnlyReturnsMatchingTransactions() throws Exception {
        UUID accountId = UUID.randomUUID();
        BankTransaction first = persist(accountId, "10.00");
        BankTransaction second = persist(accountId, "20.00");
        persist(UUID.randomUUID(), "30.00");

        mvc.perform(get("/api/transactions/account/{accountId}", accountId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].id", containsInAnyOrder(first.getId().toString(), second.getId().toString())))
                .andExpect(jsonPath("$[0].accountId").value(accountId.toString()))
                .andExpect(jsonPath("$[1].accountId").value(accountId.toString()));
    }

    @Test
    void accountWithNoStoredTransactionsReturnsEmptyListWithoutClaimingAccountExists() throws Exception {
        mvc.perform(get("/api/transactions/account/{accountId}", UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/transactions/not-a-uuid", "/api/transactions/account/not-a-uuid"})
    void malformedPathIdentifiersReturn400(String path) throws Exception {
        mvc.perform(get(path))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    private ObjectNode validRequest() {
        return objectMapper.createObjectNode()
                .put("accountId", UUID.randomUUID().toString())
                .put("amount", new BigDecimal("100.00"))
                .put("currency", "MAD")
                .put("merchantCategory", "GROCERIES")
                .put("country", "MA");
    }

    private BankTransaction persist(UUID accountId, String amount) {
        // Test fixture through the explicitly internal component, not a public creation route.
        return persistence.persist(new TransactionCommand(accountId, new BigDecimal(amount), "MAD", "GROCERIES", "MA"));
    }
}
