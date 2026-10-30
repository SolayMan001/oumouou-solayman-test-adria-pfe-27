package com.adria.bank.transaction.mcp;

import java.util.List;
import java.util.UUID;
import com.adria.bank.transaction.service.TransactionService;
import com.adria.bank.transaction.service.InputValidation;
import com.adria.bank.transaction.web.rest.dto.CreateTransactionRequest;
import com.adria.bank.transaction.web.rest.dto.TransactionResponse;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class TransactionTools {
    private final TransactionService service;
    private final InputValidation validation;
    public TransactionTools(TransactionService service, InputValidation validation) { this.service = service; this.validation = validation; }

    @Tool(description = "Retrieve a transaction by UUID.")
    public TransactionResponse getTransaction(@ToolParam(required = true) String id) {
        return TransactionResponse.from(service.get(UUID.fromString(id)));
    }

    @Tool(description = "List recorded transactions.")
    public List<TransactionResponse> listTransactions() {
        return service.list().stream().map(TransactionResponse::from).toList();
    }

    @Tool(description = "List transactions for an account UUID. This read does not verify account existence.")
    public List<TransactionResponse> getTransactionsByAccount(@ToolParam(required = true) String accountId) {
        return service.listByAccount(UUID.fromString(accountId)).stream().map(TransactionResponse::from).toList();
    }

    @Tool(description = "Debit a verified ACTIVE account and record a transaction using a UUID idempotency key. Reuse the same key and payload after any timeout; never use a fresh key to retry an uncertain operation.")
    public TransactionResponse createTransaction(@ToolParam(required = true) CreateTransactionRequest input,
            @ToolParam(required = true) String idempotencyKey) {
        validation.validate(input);
        if (idempotencyKey == null || idempotencyKey.isBlank()) { throw new IllegalArgumentException("Idempotency key is required."); }
        return TransactionResponse.from(service.create(UUID.fromString(idempotencyKey), input.toCommand()));
    }
}
