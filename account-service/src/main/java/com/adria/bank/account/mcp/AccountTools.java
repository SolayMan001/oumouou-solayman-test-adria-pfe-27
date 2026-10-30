package com.adria.bank.account.mcp;

import java.util.List;
import java.util.UUID;
import com.adria.bank.account.domain.AccountStatus;
import com.adria.bank.account.service.AccountService;
import com.adria.bank.account.service.InputValidation;
import com.adria.bank.account.web.rest.dto.AccountResponse;
import com.adria.bank.account.web.rest.dto.CreateAccountRequest;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class AccountTools {
    private final AccountService service;
    private final InputValidation validation;
    private final com.adria.bank.account.service.TransferService transfers;
    public AccountTools(AccountService service, InputValidation validation, com.adria.bank.account.service.TransferService transfers) {
        this.service = service; this.validation = validation; this.transfers = transfers;
    }

    @Tool(description = "Retrieve an account by UUID.")
    public AccountResponse getAccount(@ToolParam(required = true) String id) {
        return AccountResponse.from(service.get(UUID.fromString(id)));
    }

    @Tool(description = "List bank accounts.")
    public List<AccountResponse> listAccounts() { return service.list().stream().map(AccountResponse::from).toList(); }

    @Tool(description = "Create an ACTIVE account with a nonnegative balance. Monetary input has at most two fractional digits.")
    public AccountResponse createAccount(@ToolParam(required = true) CreateAccountRequest input) {
        validation.validate(input);
        return AccountResponse.from(service.create(input.customerReference(), input.currency(), input.balance()));
    }

    @Tool(description = "Set an account status to ACTIVE or BLOCKED.")
    public AccountResponse updateAccountStatus(@ToolParam(required = true) String id, @ToolParam(required = true) AccountStatus status) {
        return AccountResponse.from(service.updateStatus(UUID.fromString(id), status));
    }
    @Tool(description = "Atomically transfer money between two ACTIVE accounts of the same currency. Requires a UUID idempotency key; replays do not move money twice.")
    public com.adria.bank.account.web.rest.dto.TransferResponse transfer(
            @ToolParam(required = true) com.adria.bank.account.service.TransferCommand input,
            @ToolParam(required = true) String idempotencyKey) {
        validation.validate(input);
        if (idempotencyKey == null || idempotencyKey.isBlank()) { throw new IllegalArgumentException("Idempotency key is required."); }
        return com.adria.bank.account.web.rest.dto.TransferResponse.from(transfers.transfer(UUID.fromString(idempotencyKey), input));
    }
}
