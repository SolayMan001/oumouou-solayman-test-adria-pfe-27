package com.adria.bank.aiassistant;

import java.util.*;
import com.adria.bank.aiassistant.domain.Confirmation;
import com.adria.bank.aiassistant.mcp.BankingTools;
import com.adria.bank.aiassistant.repository.ConfirmationRepository;
import com.adria.bank.aiassistant.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AssistantTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ConfirmationRepository confirmations;
    @Autowired Environment environment;
    @MockitoBean BankingModel model;
    @MockitoBean BankingTools tools;
    private final UUID source = UUID.randomUUID();
    private final UUID destination = UUID.randomUUID();

    @BeforeEach void clean() { confirmations.deleteAll(); }

    @Test
    void restMessageValidationDoesNotInvokeTheModelOrTools() throws Exception {
        mvc.perform(post("/api/assistant/chat").contentType("application/json").content("{\"message\":\" \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/assistant/chat").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(model, tools);
    }

    @Test
    void configurationUsesBothStreamableEndpointsAndStatusDoesNotExposeSecrets() throws Exception {
        assertThat(environment.getProperty("assistant.mcp.account-url")).endsWith("8081/mcp");
        assertThat(environment.getProperty("assistant.mcp.transaction-url")).endsWith("8082/mcp");
        when(tools.status()).thenReturn(Map.of("account", "UP (5 tools)", "transaction", "UP (4 tools)"));
        String response = mvc.perform(get("/api/assistant/status")).andExpect(status().isOk())
                .andExpect(jsonPath("$.mcp.account").value("UP (5 tools)"))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("test-only-not-a-real-key");
        verifyNoInteractions(model);
    }

    @Test
    void modelCannotExecuteTransferWithoutSeparateExplicitConfirmationAndReplayIsStable() throws Exception {
        ToolCallback delegate = mutationTool("account_transfer");
        String arguments = mapper.writeValueAsString(Map.of("input", Map.of("sourceAccountId", source, "destinationAccountId", destination, "amount", 10)));
        propose("account_transfer", delegate, arguments);
        var pending = mapper.readTree(chat("Transfer 10 from " + source + " to " + destination, 200));
        assertThat(pending.path("status").asText()).isEqualTo("CONFIRMATION_REQUIRED");
        assertThat(pending.path("response").asText()).contains("Nothing has been executed");
        verify(delegate, never()).call(anyString());
        verify(tools, never()).call(anyString(), anyString());
        UUID id = UUID.fromString(pending.path("confirmationId").asText());
        UUID key = UUID.fromString(pending.path("idempotencyKey").asText());
        assertThat(confirmations.findById(id).orElseThrow().getState()).isEqualTo(Confirmation.State.PENDING);
        confirm(id, false, 409);
        when(tools.call(eq("account_transfer"), anyString())).thenReturn(mapper.writeValueAsString(Map.of(
                "id", key, "sourceAccountId", source, "destinationAccountId", destination, "amount", 10, "currency", "MAD")));
        var first = mapper.readTree(confirm(id, true, 200));
        var second = mapper.readTree(confirm(id, true, 200));
        assertThat(first.path("status").asText()).isEqualTo("EXECUTED");
        assertThat(first.path("operation")).isEqualTo(second.path("operation"));
        verify(tools, times(1)).call(eq("account_transfer"), contains(key.toString()));
    }

    @Test
    void ambiguousMutationRetryKeepsExactStoredPayloadAndKey() throws Exception {
        ToolCallback delegate = mutationTool("transaction_createTransaction");
        String arguments = mapper.writeValueAsString(Map.of("input", Map.of("accountId", source, "amount", 10, "currency", "MAD", "merchantCategory", "FOOD", "country", "MA")));
        propose("transaction_createTransaction", delegate, arguments);
        var pending = mapper.readTree(chat("Create a transaction of 10 MAD for account " + source + " merchant FOOD country MA", 200));
        UUID id = UUID.fromString(pending.path("confirmationId").asText());
        String key = pending.path("idempotencyKey").asText();
        String result = mapper.writeValueAsString(Map.of("id", UUID.randomUUID(), "accountId", source, "amount", 10, "currency", "MAD"));
        when(tools.call(eq("transaction_createTransaction"), anyString()))
                .thenThrow(new AssistantException(503, "MCP_UNAVAILABLE", "Ambiguous timeout"))
                .thenReturn(result);
        confirm(id, true, 503);
        assertThat(confirmations.findById(id).orElseThrow().getState()).isEqualTo(Confirmation.State.UNKNOWN);
        confirm(id, true, 200);
        var argumentsCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(tools, times(2)).call(eq("transaction_createTransaction"), argumentsCaptor.capture());
        assertThat(argumentsCaptor.getAllValues().get(0)).isEqualTo(argumentsCaptor.getAllValues().get(1)).contains(key);
        assertThat(confirmations.count()).isEqualTo(1);
    }

    @Test
    void inventedIdentifiersOrMissingAmountsNeverReachMcp() throws Exception {
        ToolCallback delegate = mutationTool("account_transfer");
        String arguments = mapper.writeValueAsString(Map.of("input", Map.of("sourceAccountId", source, "destinationAccountId", destination, "amount", 10)));
        propose("account_transfer", delegate, arguments);
        chat("Transfer money", 400);
        chat("Transfer from " + source + " to " + destination, 400);
        assertThat(confirmations.count()).isZero();
        verify(delegate, never()).call(anyString());
        verify(tools, never()).call(anyString(), anyString());
    }

    @Test
    void onlyVerifiedReadResultsAreReturnedInsteadOfFabricatedModelClaims() throws Exception {
        ToolCallback delegate = mutationTool("account_getAccount");
        when(delegate.call(anyString())).thenReturn(mapper.writeValueAsString(Map.of("id", source, "balance", 100)));
        when(tools.callbacks()).thenReturn(Map.of("account_getAccount", delegate));
        when(model.respond(anyString(), any(ToolCallback[].class))).thenAnswer(invocation -> {
            ToolCallback[] callbacks = invocation.getArgument(1);
            callbacks[0].call("{\"id\":\"" + source + "\"}");
            return "Your balance is 999999 and a transfer succeeded.";
        });
        String result = chat("Show balance for " + source, 200);
        assertThat(result).contains("100").doesNotContain("999999", "transfer succeeded");
        assertThat(confirmations.count()).isZero();
    }

    private ToolCallback mutationTool(String name) {
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(ToolDefinition.builder().name(name).description("Banking tool")
                .inputSchema("{\"type\":\"object\",\"properties\":{\"input\":{\"type\":\"object\"},\"idempotencyKey\":{\"type\":\"string\"}},\"required\":[\"input\",\"idempotencyKey\"]}").build());
        return callback;
    }
    private void propose(String name, ToolCallback delegate, String arguments) {
        when(tools.callbacks()).thenReturn(Map.of(name, delegate));
        when(model.respond(anyString(), any(ToolCallback[].class))).thenAnswer(invocation -> {
            ToolCallback[] callbacks = invocation.getArgument(1);
            assertThat(callbacks[0].getToolDefinition().inputSchema()).doesNotContain("idempotencyKey");
            callbacks[0].call(arguments);
            return "I executed the operation.";
        });
    }
    private String chat(String message, int status) throws Exception {
        return mvc.perform(post("/api/assistant/chat").contentType("application/json").content(mapper.writeValueAsString(Map.of("message", message))))
                .andExpect(status().is(status)).andReturn().getResponse().getContentAsString();
    }
    private String confirm(UUID id, boolean confirmed, int status) throws Exception {
        return mvc.perform(post("/api/assistant/chat").contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("message", "Confirm", "confirmationId", id, "confirmed", confirmed))))
                .andExpect(status().is(status)).andReturn().getResponse().getContentAsString();
    }
}
