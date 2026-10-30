package com.adria.bank.aiassistant.service;

import java.util.Set;
import java.util.UUID;
import com.adria.bank.aiassistant.domain.Confirmation;
import com.adria.bank.aiassistant.repository.ConfirmationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConfirmationStore {
    private static final Set<String> KEYED = Set.of("account_transfer", "transaction_createTransaction");
    private final ConfirmationRepository repository;
    public ConfirmationStore(ConfirmationRepository repository) { this.repository = repository; }

    @Transactional
    public Confirmation propose(UUID key, String tool, String arguments) {
        return repository.saveAndFlush(new Confirmation(key, tool, arguments));
    }

    @Transactional
    public Confirmation reserve(UUID id) {
        Confirmation confirmation = lock(id);
        if (confirmation.getState() == Confirmation.State.SUCCEEDED) { return confirmation; }
        if (confirmation.getState() == Confirmation.State.IN_PROGRESS) {
            throw new AssistantException(503, "CONFIRMATION_IN_PROGRESS", "This confirmation is processing or was interrupted. Keep its ID and idempotency key for reconciliation; do not create a replacement operation.");
        }
        if (confirmation.getState() == Confirmation.State.UNKNOWN && !KEYED.contains(confirmation.getTool())) {
            throw new AssistantException(409, "MANUAL_RECONCILIATION_REQUIRED", "The outcome of this non-idempotent tool is unknown. Inspect the account before any further action; it will not be repeated automatically.");
        }
        confirmation.start();
        return confirmation;
    }

    @Transactional
    public void succeeded(UUID id, String result) { lock(id).succeed(result); }
    @Transactional
    public void unknown(UUID id) { lock(id).unknown(); }
    private Confirmation lock(UUID id) {
        return repository.lock(id).orElseThrow(() -> new AssistantException(404, "CONFIRMATION_NOT_FOUND", "Confirmation was not found."));
    }
}
