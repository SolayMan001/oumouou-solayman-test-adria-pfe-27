package com.adria.bank.transaction.service;

import java.util.UUID;
import com.adria.bank.transaction.domain.BankTransaction;
import com.adria.bank.transaction.service.internal.OperationRegistry;
import com.adria.bank.transaction.service.internal.ProcessingWorker;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

@Service
public class TransactionWorkflow {
    private final OperationRegistry registry;
    private final ProcessingWorker worker;
    public TransactionWorkflow(OperationRegistry registry, ProcessingWorker worker) { this.registry = registry; this.worker = worker; }

    public BankTransaction create(UUID id, TransactionCommand command) {
        boolean created;
        try { created = registry.register(id, command); }
        catch (DataIntegrityViolationException collision) {
            registry.get(id).verify(command); created = false;
        }
        try { return unwrap(worker.process(id, created)); }
        catch (DataAccessException | TransactionException failure) {
            // The separately committed intent survives any rollback of local completion.
            throw new TransactionProcessingException(503, "RECOVERY_REQUIRED", "Local completion failed. Reuse the same Idempotency-Key to reconcile the durable operation.");
        }
    }
    public void cancel(UUID id) {
        ProcessingWorker.Result result = worker.cancel(id);
        if (!"OPERATION_CANCELLED".equals(result.code())) { unwrap(result); }
    }
    private BankTransaction unwrap(ProcessingWorker.Result result) {
        if (result.transaction() != null) { return result.transaction(); }
        throw new TransactionProcessingException(result.status(), result.code(), result.detail());
    }
}
