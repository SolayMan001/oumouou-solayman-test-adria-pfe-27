package com.adria.bank.account.mcp;

import java.util.Arrays;
import com.adria.bank.account.repository.AccountRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountToolsTest {
    @Autowired ToolCallbackProvider callbacks;
    @Autowired AccountRepository accounts;
    @Autowired ObjectMapper mapper;
    @BeforeEach void clean() { accounts.deleteAll(); }

    @Test
    void requiredToolsAreRegisteredWithInputSchemas() {
        assertThat(Arrays.stream(callbacks.getToolCallbacks()).map(tool -> tool.getToolDefinition().name()))
                .contains("getAccount", "listAccounts", "createAccount", "updateAccountStatus");
        assertThat(tool("createAccount").getToolDefinition().inputSchema()).contains("customerReference", "balance");
    }

    @Test
    void actualToolCallbacksCreateReadListAndBlockAccount() throws Exception {
        var result = mapper.readTree(tool("createAccount").call("{\"input\":{\"customerReference\":\"mcp\",\"currency\":\"MAD\",\"balance\":\"10.25\"}}"));
        String id = result.path("id").asText();
        assertThat(mapper.readTree(tool("getAccount").call("{\"id\":\"" + id + "\"}")).path("balance").asDouble()).isEqualTo(10.25);
        assertThat(mapper.readTree(tool("listAccounts").call("{}")).size()).isEqualTo(1);
        assertThat(mapper.readTree(tool("updateAccountStatus").call("{\"id\":\"" + id + "\",\"status\":\"BLOCKED\"}")).path("status").asText()).isEqualTo("BLOCKED");
    }

    @Test
    void invalidToolInputCannotPersistAnAccount() {
        assertThatThrownBy(() -> tool("createAccount").call("{\"input\":{\"customerReference\":\"mcp\",\"currency\":\"MAD\",\"balance\":\"-1\"}}"))
                .isInstanceOf(RuntimeException.class);
        assertThat(accounts.count()).isZero();
    }

    private ToolCallback tool(String name) {
        return Arrays.stream(callbacks.getToolCallbacks()).filter(tool -> tool.getToolDefinition().name().equals(name)).findFirst().orElseThrow();
    }
}
