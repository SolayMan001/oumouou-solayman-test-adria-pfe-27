package com.adria.bank.transaction.service.internal;

import java.util.UUID;
import com.adria.bank.transaction.domain.ProcessingOperation;
import com.adria.bank.transaction.repository.ProcessingOperationRepository;
import com.adria.bank.transaction.service.TransactionCommand;
import com.adria.bank.transaction.service.TransactionProcessingException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

@Component
public class OperationRegistry {
    private final ProcessingOperationRepository operations;
    public OperationRegistry(ProcessingOperationRepository operations) { this.operations = operations; }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean register(UUID id, TransactionCommand command) {
        var existing = operations.findById(id);
        if (existing.isPresent()) { existing.get().verify(command); return false; }
        operations.saveAndFlush(new ProcessingOperation(id, command));
        return true;
    }

    @Transactional(readOnly = true)
    public ProcessingOperation get(UUID id) {
        return operations.findById(id).orElseThrow(() -> new TransactionProcessingException(404, "OPERATION_NOT_FOUND", "Processing operation was not found."));
    }
}
