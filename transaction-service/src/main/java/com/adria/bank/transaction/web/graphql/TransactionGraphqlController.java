package com.adria.bank.transaction.web.graphql;

import java.util.List;
import java.util.UUID;
import com.adria.bank.transaction.service.TransactionService;
import com.adria.bank.transaction.service.InputValidation;
import com.adria.bank.transaction.web.rest.dto.CreateTransactionRequest;
import com.adria.bank.transaction.web.rest.dto.TransactionResponse;
import org.springframework.graphql.data.method.annotation.*;
import org.springframework.stereotype.Controller;

@Controller
public class TransactionGraphqlController {
    private final TransactionService service;
    private final InputValidation validation;
    public TransactionGraphqlController(TransactionService service, InputValidation validation) {
        this.service = service; this.validation = validation;
    }
    @QueryMapping
    public TransactionResponse transactionById(@Argument UUID id) { return TransactionResponse.from(service.get(id)); }
    @QueryMapping
    public List<TransactionResponse> transactions() { return service.list().stream().map(TransactionResponse::from).toList(); }
    @QueryMapping
    public List<TransactionResponse> transactionsByAccountId(@Argument UUID accountId) {
        return service.listByAccount(accountId).stream().map(TransactionResponse::from).toList();
    }
    @MutationMapping
    public TransactionResponse createTransaction(@Argument CreateTransactionRequest input, @Argument UUID idempotencyKey) {
        validation.validate(input);
        return TransactionResponse.from(service.create(idempotencyKey, input.toCommand()));
    }
}
