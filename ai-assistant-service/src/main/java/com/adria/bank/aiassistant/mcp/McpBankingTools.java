package com.adria.bank.aiassistant.mcp;

import java.net.URI;
import java.time.Duration;
import java.util.*;
import com.adria.bank.aiassistant.service.AssistantException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Lazy Streamable HTTP clients: offline MCP servers do not prevent the assistant from starting. */
@Component
public class McpBankingTools implements BankingTools {
    private static final Map<String, Set<String>> ALLOWED = Map.of(
            "account", Set.of("getAccount", "listAccounts", "createAccount", "updateAccountStatus", "transfer"),
            "transaction", Set.of("getTransaction", "listTransactions", "getTransactionsByAccount", "createTransaction"));
    private final Map<String, Connection> connections = new LinkedHashMap<>();
    private final ObjectMapper mapper;
    private final Duration timeout;

    public McpBankingTools(ObjectMapper mapper,
            @Value("${assistant.mcp.account-url}") String account,
            @Value("${assistant.mcp.transaction-url}") String transaction,
            @Value("${assistant.mcp.timeout:10s}") Duration timeout) {
        this.mapper = mapper; this.timeout = timeout;
        connections.put("account", new Connection("account", account));
        connections.put("transaction", new Connection("transaction", transaction));
    }

    @Override
    public Map<String, ToolCallback> callbacks() {
        Map<String, ToolCallback> tools = new LinkedHashMap<>();
        connections.values().forEach(connection -> {
            try { tools.putAll(connection.tools()); } catch (AssistantException unavailable) { /* Partial read availability. */ }
        });
        if (tools.isEmpty()) { throw new AssistantException(503, "MCP_UNAVAILABLE", "No banking MCP server is available."); }
        return Map.copyOf(tools);
    }

    @Override
    public String call(String name, String arguments) {
        int separator = name.indexOf('_');
        Connection connection = separator < 0 ? null : connections.get(name.substring(0, separator));
        if (connection == null || !connection.tools().containsKey(name)) {
            throw new AssistantException(400, "UNKNOWN_TOOL", "The requested banking tool is not available.");
        }
        return connection.invoke(name.substring(separator + 1), arguments);
    }

    @Override
    public Map<String, String> status() {
        Map<String, String> status = new LinkedHashMap<>();
        connections.forEach((name, connection) -> {
            try { status.put(name, "UP (" + connection.tools().size() + " tools)"); }
            catch (AssistantException unavailable) { status.put(name, "DOWN"); }
        });
        return status;
    }

    @PreDestroy
    public void close() { connections.values().forEach(Connection::disconnect); }

    private final class Connection {
        private final String name;
        private final URI endpoint;
        private McpSyncClient client;
        private Map<String, ToolCallback> registered;
        Connection(String name, String endpoint) { this.name = name; this.endpoint = URI.create(endpoint); }

        synchronized Map<String, ToolCallback> tools() {
            if (registered != null) { return registered; }
            try {
                String base = endpoint.getScheme() + "://" + endpoint.getRawAuthority();
                var transport = HttpClientStreamableHttpTransport.builder(base).endpoint(endpoint.getPath())
                        .connectTimeout(Duration.ofSeconds(2)).resumableStreams(false).build();
                client = McpClient.sync(transport).requestTimeout(timeout)
                        .clientInfo(new McpSchema.Implementation("adria-assistant-" + name, "1.0.0")).build();
                client.initialize();
                Map<String, ToolCallback> tools = new LinkedHashMap<>();
                for (var tool : client.listTools().tools()) {
                    if (!ALLOWED.get(name).contains(tool.name())) { continue; }
                    var definition = ToolDefinition.builder().name(name + "_" + tool.name())
                            .description(tool.description()).inputSchema(mapper.writeValueAsString(tool.inputSchema())).build();
                    tools.put(definition.name(), new ToolCallback() {
                        @Override public ToolDefinition getToolDefinition() { return definition; }
                        @Override public String call(String input) { return invoke(tool.name(), input); }
                    });
                }
                registered = Map.copyOf(tools);
                return registered;
            } catch (Exception failure) {
                disconnect();
                throw new AssistantException(503, "MCP_UNAVAILABLE", name + " MCP server is unavailable.");
            }
        }

        String invoke(String tool, String arguments) {
            try {
                Map<String, Object> input = mapper.readValue(arguments, new TypeReference<>() { });
                var result = client.callTool(new McpSchema.CallToolRequest(tool, input));
                String text = result.content().stream().filter(McpSchema.TextContent.class::isInstance)
                        .map(McpSchema.TextContent.class::cast).map(McpSchema.TextContent::text)
                        .collect(java.util.stream.Collectors.joining("\n"));
                if (Boolean.TRUE.equals(result.isError())) {
                    throw new AssistantException(502, "MCP_TOOL_FAILED", "Banking tool rejected or failed the operation: " + text);
                }
                if (text.isBlank() && result.structuredContent() != null) { text = mapper.writeValueAsString(result.structuredContent()); }
                if (text.isBlank()) { throw new AssistantException(502, "EMPTY_TOOL_RESULT", "Banking tool returned no result; success is not confirmed."); }
                return text;
            } catch (AssistantException rejected) { throw rejected; }
            catch (Exception failure) {
                disconnect();
                throw new AssistantException(503, "MCP_UNAVAILABLE", name + " MCP call failed or timed out. The outcome may be unknown; retain the confirmation ID.");
            }
        }
        synchronized void disconnect() {
            if (client != null) { try { client.close(); } catch (Exception ignored) { } }
            client = null; registered = null;
        }
    }
}
