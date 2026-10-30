package com.adria.bank.transaction.web.rest;

import java.time.Instant;
import java.util.UUID;
import com.adria.bank.transaction.domain.ProcessingOperation;
import com.adria.bank.transaction.service.TransactionWorkflow;
import com.adria.bank.transaction.service.internal.OperationRegistry;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/transactions/operations/{requestId}")
public class ProcessingOperationController {
    private final OperationRegistry registry;
    private final TransactionWorkflow workflow;
    public ProcessingOperationController(OperationRegistry registry, TransactionWorkflow workflow) {
        this.registry = registry; this.workflow = workflow;
    }
    public record OperationResponse(UUID requestId, ProcessingOperation.State state, UUID transactionId, Instant createdAt) { }
    @GetMapping
    public OperationResponse get(@PathVariable UUID requestId) {
        var op = registry.get(requestId);
        return new OperationResponse(op.getId(), op.getState(), op.getTransactionId(), op.getCreatedAt());
    }
    @PostMapping("/cancel")
    public OperationResponse cancel(@PathVariable UUID requestId) {
        workflow.cancel(requestId);
        return get(requestId);
    }
}
