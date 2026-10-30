package com.adria.bank.transaction.web.rest;

import java.util.List;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestHeader;
import java.util.UUID;

import com.adria.bank.transaction.service.TransactionService;
import com.adria.bank.transaction.web.rest.dto.CreateTransactionRequest;
import com.adria.bank.transaction.web.rest.dto.TransactionResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/transactions")
public class TransactionRestController {

    private final TransactionService service;

    public TransactionRestController(TransactionService service) {
        this.service = service;
    }

    @GetMapping
    public List<TransactionResponse> list() {
        return service.list().stream().map(TransactionResponse::from).toList();
    }

    @GetMapping("/{id}")
    public TransactionResponse get(@PathVariable UUID id) {
        return TransactionResponse.from(service.get(id));
    }

    @GetMapping("/account/{accountId}")
    public List<TransactionResponse> listByAccount(@PathVariable UUID accountId) {
        return service.listByAccount(accountId).stream().map(TransactionResponse::from).toList();
    }

    @PostMapping
    public ResponseEntity<TransactionResponse> create(@RequestHeader("Idempotency-Key") UUID requestId,
            @Valid @RequestBody CreateTransactionRequest request) {
        TransactionResponse response = TransactionResponse.from(service.create(requestId, request.toCommand()));
        return ResponseEntity.created(URI.create("/api/transactions/" + response.id())).body(response);
    }
}
