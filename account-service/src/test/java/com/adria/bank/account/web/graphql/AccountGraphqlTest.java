package com.adria.bank.account.web.graphql;

import java.util.Map;
import java.util.UUID;
import com.adria.bank.account.repository.AccountRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
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
class AccountGraphqlTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AccountRepository accounts;
    @BeforeEach void clean() { accounts.deleteAll(); }

    @Test
    void createQueryListAndStatusUseExistingService() throws Exception {
        String response = graphql("mutation { createAccount(input:{customerReference:\"graphql\", currency:\"mad\", balance:\"100.25\"}) { id balance currency status } }");
        var account = mapper.readTree(response).path("data").path("createAccount");
        assertThat(account.path("balance").asDouble()).isEqualTo(100.25);
        assertThat(account.path("currency").asText()).isEqualTo("MAD");
        String id = account.path("id").asText();
        assertThat(accounts.findById(UUID.fromString(id))).isPresent();
        assertThat(mapper.readTree(graphql("{ accountById(id:\"" + id + "\") { id } accounts { id } }"))
                .path("data").path("accounts").size()).isEqualTo(1);
        assertThat(mapper.readTree(graphql("mutation { updateAccountStatus(id:\"" + id + "\", status:BLOCKED) { status } }"))
                .path("data").path("updateAccountStatus").path("status").asText()).isEqualTo("BLOCKED");
    }

    @Test
    void negativeBalanceIsRejectedWithoutPersistence() throws Exception {
        String response = graphql("mutation { createAccount(input:{customerReference:\"graphql\", currency:\"MAD\", balance:\"-1\"}) { id } }");
        assertThat(mapper.readTree(response).path("errors").get(0).path("extensions").path("httpStatus").asInt()).isEqualTo(400);
        assertThat(accounts.count()).isZero();
    }

    @Test
    void missingAccountIsAnExplicitGraphqlError() throws Exception {
        var response = mapper.readTree(graphql("{ accountById(id:\"" + UUID.randomUUID() + "\") { id } }"));
        assertThat(response.path("errors").get(0).path("extensions").path("httpStatus").asInt()).isEqualTo(404);
    }

    @Test
    void unknownInputFieldsCannotSupplyServerIdentifiers() throws Exception {
        var response = mapper.readTree(graphql("mutation { createAccount(input:{id:\"client-id\",customerReference:\"g\",currency:\"MAD\",balance:\"0\"}) { id } }"));
        assertThat(response.path("errors").isEmpty()).isFalse();
        assertThat(accounts.count()).isZero();
    }

    private String graphql(String query) throws Exception {
        return mvc.perform(post("/graphql").contentType("application/json").content(mapper.writeValueAsString(Map.of("query", query))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }
}
