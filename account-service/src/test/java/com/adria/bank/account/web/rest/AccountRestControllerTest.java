package com.adria.bank.account.web.rest;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.stream.Stream;

import com.adria.bank.account.domain.Account;
import com.adria.bank.account.domain.AccountStatus;
import com.adria.bank.account.repository.AccountRepository;
import com.adria.bank.account.service.AccountService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountRestControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AccountRepository repository;

    @Autowired
    private AccountService service;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanDatabase() {
        repository.deleteAll();
    }

    @Test
    void accountCreationSucceedsAndPersists() throws Exception {
        String response = mvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerReference":"customer-123","currency":"mad","balance":100.25}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.customerReference").value("customer-123"))
                .andExpect(jsonPath("$.currency").value("MAD"))
                .andExpect(jsonPath("$.balance").value(100.25))
                .andReturn().getResponse().getContentAsString();

        UUID id = UUID.fromString(objectMapper.readTree(response).get("id").asText());
        assertThat(repository.findById(id)).isPresent();
        assertThat(repository.findById(id).orElseThrow().getBalance()).isEqualTo(100.25);
    }

    @Test
    void creationReturnsResourceLocation() throws Exception {
        mvc.perform(post("/api/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerReference":"customer-123","currency":"MAD","balance":0}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.matchesPattern(
                        "/api/accounts/[0-9a-f-]{36}")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.01", "-100", "0.001", "1000000000000.00", "null", "\"NaN\"", "\"Infinity\""})
    void invalidInitialBalanceIsRejected(String balance) throws Exception {
        mvc.perform(post("/api/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerReference\":\"customer-123\",\"currency\":\"MAD\",\"balance\":" + balance + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").isNotEmpty());
        assertThat(repository.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"customerReference\":\"customer-123\",\"balance\":0}",
            "{\"customerReference\":\"customer-123\",\"currency\":\" \",\"balance\":0}",
            "{\"customerReference\":\"customer-123\",\"currency\":\"MAD\"}",
            "{\"customerReference\":\" \",\"currency\":\"MAD\",\"balance\":0}",
            "{\"currency\":\"MAD\",\"balance\":0}"
    })
    void mandatoryFieldsAreValidated(String request) throws Exception {
        mvc.perform(post("/api/accounts").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors").isMap());
    }

    @Test
    void missingAccountReturns404() throws Exception {
        mvc.perform(get("/api/accounts/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"debit", "credit", "status"})
    void updatesToMissingAccountReturn404(String operation) throws Exception {
        String path = "/api/accounts/" + UUID.randomUUID() + "/" + operation;
        var request = operation.equals("status")
                ? patch(path).content("{\"status\":\"BLOCKED\"}")
                : post(path).content("{\"amount\":1.00}");
        mvc.perform(request.contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
    }

    @Test
    void accountsCanBeListedAndRetrieved() throws Exception {
        Account account = newAccount("100.00");
        mvc.perform(get("/api/accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(account.getId().toString()));
        mvc.perform(get("/api/accounts/{id}", account.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(100.0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"debit", "credit"})
    void blockedAccountCannotBeDebitedOrCredited(String operation) throws Exception {
        Account account = newAccount("100.00");
        service.updateStatus(account.getId(), AccountStatus.BLOCKED);
        mvc.perform(post("/api/accounts/{id}/{operation}", account.getId(), operation)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":10.00}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_BLOCKED"));
        assertBalance(account.getId(), 100.0);
    }

    @Test
    void statusCanBeBlockedAndReactivated() throws Exception {
        Account account = newAccount("100.00");
        for (String newStatus : new String[]{"BLOCKED", "ACTIVE"}) {
            mvc.perform(patch("/api/accounts/{id}/status", account.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"" + newStatus + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(newStatus));
            assertThat(repository.findById(account.getId()).orElseThrow().getStatus().name()).isEqualTo(newStatus);
        }
        service.debit(account.getId(), new BigDecimal("1.00"));
        assertBalance(account.getId(), 99.0);
    }

    @Test
    void insufficientBalanceIsRejectedWithoutChangingBalance() throws Exception {
        Account account = newAccount("10.00");
        mvc.perform(post("/api/accounts/{id}/debit", account.getId())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":10.01}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"));
        assertBalance(account.getId(), 10.0);
    }

    @Test
    void validDebitUpdatesBalance() throws Exception {
        Account account = newAccount("100.00");
        mvc.perform(post("/api/accounts/{id}/debit", account.getId())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":25.10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(74.9));
        assertBalance(account.getId(), 74.9);
    }

    @Test
    void validCreditUpdatesBalance() throws Exception {
        Account account = newAccount("100.00");
        mvc.perform(post("/api/accounts/{id}/credit", account.getId())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":25.10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(125.1));
        assertBalance(account.getId(), 125.1);
    }

    @ParameterizedTest
    @MethodSource("invalidAmounts")
    void invalidDebitAndCreditAmountsAreRejected(String operation, String amount) throws Exception {
        Account account = newAccount("100.00");
        mvc.perform(post("/api/accounts/{id}/{operation}", account.getId(), operation)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":" + amount + "}"))
                .andExpect(status().isBadRequest());
        assertBalance(account.getId(), 100.0);
    }

    static Stream<Arguments> invalidAmounts() {
        return Stream.of("debit", "credit").flatMap(operation ->
                Stream.of("0", "-0.01", "0.001", "1000000000000.00", "null", "\"NaN\"", "\"Infinity\"")
                        .map(amount -> Arguments.of(operation, amount)));
    }

    @Test
    void creditBeyondCentSafeLimitIsRejected() throws Exception {
        Account account = newAccount("999999999999.99");
        mvc.perform(post("/api/accounts/{id}/credit", account.getId())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":0.01}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        assertBalance(account.getId(), 999999999999.99);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"status\":null}", "{\"status\":\"CLOSED\"}",
            "{\"status\":0}", "{\"status\":1}", "{broken"})
    void invalidStatusRequestsAreRejected(String request) throws Exception {
        Account account = newAccount("100.00");
        mvc.perform(patch("/api/accounts/{id}/status", account.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isBadRequest());
        assertThat(repository.findById(account.getId()).orElseThrow().getStatus()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void malformedUuidReturns400() throws Exception {
        mvc.perform(get("/api/accounts/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    private Account newAccount(String balance) {
        return service.create("customer-123", "MAD", new BigDecimal(balance));
    }

    private void assertBalance(UUID id, double expected) {
        assertThat(repository.findById(id).orElseThrow().getBalance()).isEqualTo(expected);
    }
}
