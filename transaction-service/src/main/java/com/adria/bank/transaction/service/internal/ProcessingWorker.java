package com.adria.bank.transaction.service.internal;

import java.math.BigDecimal;
import java.util.UUID;
import com.adria.bank.transaction.client.AccountFeignClient;
import com.adria.bank.transaction.domain.BankTransaction;
import com.adria.bank.transaction.domain.ProcessingOperation;
import com.adria.bank.transaction.repository.ProcessingOperationRepository;
import com.adria.bank.transaction.repository.TransactionRepository;
import com.adria.bank.transaction.service.TransactionProcessingException;
import feign.FeignException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ProcessingWorker {
    public record Result(BankTransaction transaction, int status, String code, String detail) {
        static Result success(BankTransaction transaction) { return new Result(transaction, 201, null, null); }
        static Result error(int status, String code, String detail) { return new Result(null, status, code, detail); }
    }
    private final ProcessingOperationRepository operations;
    private final TransactionRepository transactions;
    private final TransactionPersistence persistence;
    private final AccountFeignClient accounts;

    public ProcessingWorker(ProcessingOperationRepository operations, TransactionRepository transactions,
            TransactionPersistence persistence, AccountFeignClient accounts) {
        this.operations = operations; this.transactions = transactions; this.persistence = persistence; this.accounts = accounts;
    }

    @Transactional
    public Result process(UUID id, boolean newlyRegistered) {
        ProcessingOperation op = lock(id);
        if (op.getState() == ProcessingOperation.State.COMPLETED) {
            return Result.success(transactions.findById(op.getTransactionId()).orElseThrow());
        }
        if (op.getState() == ProcessingOperation.State.REJECTED) {
            return Result.error(op.getErrorStatus(), op.getErrorCode(), op.getDetail());
        }
        if (op.getState() == ProcessingOperation.State.CANCELLED) { return cancelled(); }
        if (!newlyRegistered || op.getState() != ProcessingOperation.State.PENDING) { return reconcile(op); }

        var command = op.command();
        AccountFeignClient.AccountView account;
        try { account = accounts.get(command.accountId()); }
        catch (FeignException error) { return remoteFailure(op, error, false); }
        if (!command.accountId().equals(account.id())) { return Result.error(503, "ACCOUNT_INTEGRATION_UNAVAILABLE", "Account Service returned an unexpected account."); }
        if (!"ACTIVE".equals(account.status())) { return reject(op, "ACCOUNT_BLOCKED", "Only ACTIVE accounts can be debited."); }
        if (!command.currency().equals(account.currency())) { return reject(op, "CURRENCY_MISMATCH", "Transaction currency must match account currency."); }
        if (!Double.isFinite(account.balance()) || BigDecimal.valueOf(account.balance()).compareTo(command.amount()) < 0) {
            return reject(op, "INSUFFICIENT_BALANCE", "Account has insufficient balance.");
        }
        try {
            var debit = accounts.debit(command.accountId(), id, new AccountFeignClient.DebitRequest(command.amount(), command.currency()));
            if (!matches(op, debit) || !"DEBITED".equals(debit.state())) {
                op.uncertain(); return recoveryRequired();
            }
        } catch (FeignException error) { return remoteFailure(op, error, true); }
        return complete(op);
    }

    private Result reconcile(ProcessingOperation op) {
        try {
            var debit = accounts.operation(op.command().accountId(), op.getId());
            if (!matches(op, debit)) { return Result.error(409, "OPERATION_CONFLICT", "Account debit ledger payload does not match this request."); }
            if ("DEBITED".equals(debit.state())) { return complete(op); }
            if ("CANCELLED".equals(debit.state()) || "COMPENSATED".equals(debit.state())) { op.cancel(); return cancelled(); }
            return recoveryRequired();
        } catch (FeignException error) {
            // Absence is not proof that an earlier debit is no longer in flight.
            return recoveryRequired();
        }
    }

    @Transactional
    public Result cancel(UUID id) {
        ProcessingOperation op = lock(id);
        if (op.getState() == ProcessingOperation.State.COMPLETED) {
            return Result.error(409, "ALREADY_COMPLETED", "A completed transaction cannot be cancelled by recovery.");
        }
        if (op.getState() == ProcessingOperation.State.CANCELLED) { return cancelled(); }
        try {
            var command = op.command();
            var debit = accounts.compensate(command.accountId(), id,
                    new AccountFeignClient.DebitRequest(command.amount(), command.currency()));
            if (!matches(op, debit) || !("COMPENSATED".equals(debit.state()) || "CANCELLED".equals(debit.state()))) {
                return recoveryRequired();
            }
            op.cancel();
            return cancelled();
        } catch (FeignException error) { return recoveryRequired(); }
    }

    private boolean matches(ProcessingOperation op, AccountFeignClient.DebitView debit) {
        return debit != null && debit.amount() != null && op.getId().equals(debit.id()) && op.command().accountId().equals(debit.accountId())
                && op.command().amount().compareTo(debit.amount()) == 0 && op.command().currency().equals(debit.currency());
    }

    private Result complete(ProcessingOperation op) {
        BankTransaction transaction = persistence.persist(op.command(), op.getId());
        op.complete(transaction.getId());
        // The transaction row and COMPLETED marker commit together in this local transaction.
        return Result.success(transaction);
    }

    private Result remoteFailure(ProcessingOperation op, FeignException error, boolean debitAttempted) {
        int status = error.status();
        if (status == 400 || status == 404 || status == 409) {
            String code = status == 404 ? "ACCOUNT_NOT_FOUND" : "ACCOUNT_REJECTED";
            String detail = "Account Service rejected the operation (HTTP " + status + ").";
            op.reject(status, code, detail);
            return Result.error(status, code, detail);
        }
        if (debitAttempted) { op.uncertain(); }
        return Result.error(503, "ACCOUNT_INTEGRATION_UNAVAILABLE", "Account Service is unavailable; no transaction was saved. Reuse this Idempotency-Key to reconcile the outcome.");
    }

    private Result reject(ProcessingOperation op, String code, String detail) {
        op.reject(409, code, detail); return Result.error(409, code, detail);
    }
    private Result cancelled() { return Result.error(409, "OPERATION_CANCELLED", "Operation has been cancelled or compensated."); }
    private Result recoveryRequired() { return Result.error(503, "RECOVERY_REQUIRED", "Outcome requires reconciliation. Reuse the same key, or explicitly cancel the operation; do not submit a new key."); }
    private ProcessingOperation lock(UUID id) {
        return operations.lock(id).orElseThrow(() -> new TransactionProcessingException(404, "OPERATION_NOT_FOUND", "Processing operation was not found."));
    }
}
