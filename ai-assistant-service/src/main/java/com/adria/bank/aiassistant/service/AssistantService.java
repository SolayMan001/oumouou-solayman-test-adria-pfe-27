package com.adria.bank.aiassistant.service;

import java.util.*;
import com.adria.bank.aiassistant.config.ModelSettings;
import com.adria.bank.aiassistant.domain.Confirmation;
import com.adria.bank.aiassistant.mcp.BankingTools;
import com.adria.bank.aiassistant.web.ChatRequest;
import com.adria.bank.aiassistant.web.ChatResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Service;

@Service
public class AssistantService {
    private static final Set<String> READS = Set.of("account_getAccount", "account_listAccounts", "transaction_getTransaction",
            "transaction_listTransactions", "transaction_getTransactionsByAccount");
    private static final Set<String> WRITES = Set.of("account_createAccount", "account_updateAccountStatus",
            "account_transfer", "transaction_createTransaction");
    private static final Set<String> KEYED = Set.of("account_transfer", "transaction_createTransaction");
    private final BankingModel model;
    private final BankingTools tools;
    private final ConfirmationStore confirmations;
    private final ModelSettings settings;
    private final ObjectMapper mapper;
    public AssistantService(BankingModel model, BankingTools tools, ConfirmationStore confirmations,
            ModelSettings settings, ObjectMapper mapper) {
        this.model = model; this.tools = tools; this.confirmations = confirmations; this.settings = settings; this.mapper = mapper;
    }

    public ChatResponse chat(ChatRequest request) {
        if (request.confirmationId() != null) {
            if (!Boolean.TRUE.equals(request.confirmed())) {
                throw new AssistantException(409, "EXPLICIT_CONFIRMATION_REQUIRED", "Set confirmed=true with the exact confirmationId to execute the displayed operation.");
            }
            return execute(request.confirmationId());
        }
        if (Boolean.TRUE.equals(request.confirmed())) {
            throw new AssistantException(400, "CONFIRMATION_ID_REQUIRED", "Confirmation requires the ID of an existing displayed proposal.");
        }
        if (!settings.configured()) { throw new AssistantException(503, "MODEL_NOT_CONFIGURED", "Set OPENAI_API_KEY to enable model inference."); }
        var session = new ToolSession(request.message());
        var protectedTools = tools.callbacks().entrySet().stream()
                .filter(entry -> READS.contains(entry.getKey()) || WRITES.contains(entry.getKey()))
                .map(entry -> session.protect(entry.getKey(), entry.getValue())).toArray(ToolCallback[]::new);
        try { model.respond(request.message(), protectedTools); }
        catch (Exception failure) {
            if (session.failure != null) { throw session.failure; }
            if (session.proposal == null && session.evidence.isEmpty()) {
                throw new AssistantException(503, "MODEL_UNAVAILABLE", "The model provider is unavailable or rejected the request. Check its configuration; no banking mutation was executed.");
            }
        }
        if (session.failure != null) { throw session.failure; }
        if (session.proposal != null) { return pending(session.proposal); }
        if (!session.evidence.isEmpty()) {
            return new ChatResponse("Verified MCP banking results: " + json(session.evidence), "VERIFIED", null, null, null);
        }
        // Unverified model prose is intentionally not returned as financial facts or success claims.
        return new ChatResponse("No banking facts or actions were verified. Please state the operation and supply its required details, or ask to list accounts/transactions.",
                "NEEDS_INFORMATION", null, null, null);
    }

    private ChatResponse execute(UUID id) {
        Confirmation confirmation = confirmations.reserve(id);
        if (confirmation.getState() == Confirmation.State.SUCCEEDED) { return executed(confirmation, confirmation.getResult()); }
        try {
            String result = tools.call(confirmation.getTool(), confirmation.getArguments());
            JsonNode data = tree(result);
            if (!data.isObject() || !data.hasNonNull("id")) {
                throw new AssistantException(502, "UNCONFIRMED_TOOL_RESULT", "The tool did not confirm a successful operation. Keep this confirmation ID for reconciliation.");
            }
            java.util.UUID.fromString(data.path("id").asText());
            if (KEYED.contains(confirmation.getTool())) {
                JsonNode input = tree(confirmation.getArguments()).path("input");
                for (String field : List.of("accountId", "sourceAccountId", "destinationAccountId", "currency")) {
                    if (input.has(field) && !input.path(field).asText().equalsIgnoreCase(data.path(field).asText())) {
                        throw new AssistantException(502, "UNCONFIRMED_TOOL_RESULT", "The MCP result does not match the confirmed operation. Reconciliation is required.");
                    }
                }
                if (new java.math.BigDecimal(input.path("amount").asText()).compareTo(new java.math.BigDecimal(data.path("amount").asText())) != 0) {
                    throw new AssistantException(502, "UNCONFIRMED_TOOL_RESULT", "The MCP result amount does not match the confirmed operation.");
                }
            }
            confirmations.succeeded(id, result);
            return executed(confirmation, result);
        } catch (RuntimeException failure) {
            confirmations.unknown(id);
            if (failure instanceof AssistantException assistant) { throw assistant; }
            throw new AssistantException(503, "OPERATION_OUTCOME_UNKNOWN", "The operation could not be confirmed. Retain this confirmation ID and idempotency key; never retry with a new key.");
        }
    }

    private ChatResponse pending(Confirmation confirmation) {
        var operation = mapper.createObjectNode().put("tool", confirmation.getTool());
        operation.set("arguments", tree(confirmation.getArguments()));
        return new ChatResponse("Confirmation required. Nothing has been executed. Review the exact operation and POST confirmed=true with this confirmationId to execute it.",
                "CONFIRMATION_REQUIRED", confirmation.getId(), confirmation.getIdempotencyKey(), operation);
    }
    private ChatResponse executed(Confirmation confirmation, String result) {
        return new ChatResponse("MCP confirmed the operation: " + result, "EXECUTED", confirmation.getId(), confirmation.getIdempotencyKey(), tree(result));
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception error) { throw new AssistantException(500, "SERIALIZATION_ERROR", "Unable to serialize the banking response."); }
    }
    private JsonNode tree(String value) {
        try { return mapper.readTree(value); }
        catch (Exception error) { throw new AssistantException(502, "INVALID_TOOL_RESULT", "Banking data was not valid JSON; success is not confirmed."); }
    }

    private final class ToolSession {
        final ArgumentGrounding grounding;
        final List<Map<String, Object>> evidence = new ArrayList<>();
        Confirmation proposal;
        AssistantException failure;
        int calls;
        ToolSession(String message) { grounding = new ArgumentGrounding(message); }

        ToolCallback protect(String name, ToolCallback delegate) {
            ToolDefinition original = delegate.getToolDefinition();
            ObjectNode schema = (ObjectNode) tree(original.inputSchema()).deepCopy();
            if (KEYED.contains(name)) {
                if (schema.path("properties") instanceof ObjectNode properties) { properties.remove("idempotencyKey"); }
                if (schema.path("required").isArray()) {
                    var required = mapper.createArrayNode();
                    schema.path("required").forEach(item -> { if (!item.asText().equals("idempotencyKey")) { required.add(item); } });
                    schema.set("required", required);
                }
            }
            var definition = ToolDefinition.builder().name(name).inputSchema(json(schema))
                    .description((WRITES.contains(name) ? "PROPOSE ONLY; separate explicit REST confirmation is required. " : "") + original.description()).build();
            return new ToolCallback() {
                @Override public ToolDefinition getToolDefinition() { return definition; }
                @Override public String call(String input) { return invoke(name, delegate, input); }
            };
        }

        synchronized String invoke(String name, ToolCallback delegate, String input) {
            try {
                if (++calls > 8) { throw new AssistantException(400, "TOOL_CALL_LIMIT", "Too many tool requests. Please provide a more specific banking request."); }
                ObjectNode arguments = (ObjectNode) tree(input);
                grounding.verify(arguments);
                if (WRITES.contains(name)) {
                    if (proposal == null) {
                        UUID key = UUID.randomUUID();
                        if (KEYED.contains(name)) { arguments.put("idempotencyKey", key.toString()); }
                        proposal = confirmations.propose(key, name, json(arguments));
                    }
                    return json(pending(proposal));
                }
                String result = delegate.call(input);
                evidence.add(Map.of("tool", name, "result", tree(result)));
                grounding.remember(result);
                return result;
            } catch (AssistantException error) { failure = error; throw error; }
            catch (RuntimeException error) {
                failure = new AssistantException(503, "MCP_UNAVAILABLE", "A banking MCP tool failed. No successful action can be confirmed.");
                throw failure;
            }
        }
    }
}
