package com.adria.bank.transaction.mcp;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.UUID;
import com.adria.bank.transaction.repository.TransactionRepository;
import com.adria.bank.transaction.service.TransactionCommand;
import com.adria.bank.transaction.service.internal.TransactionPersistence;
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
class TransactionToolsTest {
    @Autowired ToolCallbackProvider callbacks;
    @Autowired TransactionRepository transactions;
    @Autowired com.adria.bank.transaction.repository.ProcessingOperationRepository operations;
    @Autowired TransactionPersistence persistence;
    @Autowired ObjectMapper mapper;
    @BeforeEach void clean() { transactions.deleteAll(); }

    @Test
    void requiredToolsAndIdempotencyParameterAreRegistered() {
        assertThat(Arrays.stream(callbacks.getToolCallbacks()).map(tool -> tool.getToolDefinition().name()))
                .containsExactlyInAnyOrder("getTransaction", "listTransactions", "getTransactionsByAccount", "createTransaction");
        assertThat(tool("createTransaction").getToolDefinition().inputSchema()).contains("idempotencyKey");
    }

    @Test
    void readCallbacksUseTheSharedPersistence() throws Exception {
        UUID account = UUID.randomUUID();
        var saved = persistence.persist(new TransactionCommand(account, new BigDecimal("10.25"), "MAD", "FOOD", "MA"));
        assertThat(mapper.readTree(tool("getTransaction").call("{\"id\":\"" + saved.getId() + "\"}")).path("amount").decimalValue()).isEqualByComparingTo("10.25");
        assertThat(mapper.readTree(tool("listTransactions").call("{}")).size()).isEqualTo(1);
        assertThat(mapper.readTree(tool("getTransactionsByAccount").call("{\"accountId\":\"" + account + "\"}")).size()).isEqualTo(1);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"id", "createdAt"})
    void clientServerFieldsAreRejectedBeforeAnyProcessingIntent(String field) {
        UUID key = UUID.randomUUID();
        String input = "{\"input\":{\"accountId\":\"" + UUID.randomUUID()
                + "\",\"amount\":10,\"currency\":\"MAD\",\"merchantCategory\":\"FOOD\",\"country\":\"MA\","
                + "\"" + field + "\":\"2020-01-01T00:00:00Z\"},\"idempotencyKey\":\"" + key + "\"}";
        assertThatThrownBy(() -> tool("createTransaction").call(input)).isInstanceOf(RuntimeException.class);
        assertThat(operations.findById(key)).isEmpty();
        assertThat(transactions.count()).isZero();
    }

    @Test
    void missingKeyAndInvalidAmountCannotBypassFinancialWorkflow() {
        String input = "{\"input\":{\"accountId\":\"" + UUID.randomUUID() + "\",\"amount\":10,\"currency\":\"MAD\",\"merchantCategory\":\"FOOD\",\"country\":\"MA\"}}";
        assertThatThrownBy(() -> tool("createTransaction").call(input)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> tool("createTransaction").call(input.replace("\"amount\":10", "\"amount\":-1").replace("}}", "},\"idempotencyKey\":\"" + UUID.randomUUID() + "\"}")))
                .isInstanceOf(RuntimeException.class);
        assertThat(transactions.count()).isZero();
    }

    private ToolCallback tool(String name) {
        return Arrays.stream(callbacks.getToolCallbacks()).filter(tool -> tool.getToolDefinition().name().equals(name)).findFirst().orElseThrow();
    }
}
