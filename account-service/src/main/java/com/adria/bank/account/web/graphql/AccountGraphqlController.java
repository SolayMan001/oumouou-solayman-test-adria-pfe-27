package com.adria.bank.account.web.graphql;

import java.util.List;
import java.util.UUID;
import com.adria.bank.account.domain.AccountStatus;
import com.adria.bank.account.service.AccountService;
import com.adria.bank.account.service.InputValidation;
import com.adria.bank.account.web.rest.dto.AccountResponse;
import com.adria.bank.account.web.rest.dto.CreateAccountRequest;
import org.springframework.graphql.data.method.annotation.*;
import org.springframework.stereotype.Controller;

@Controller
public class AccountGraphqlController {
    private final AccountService service;
    private final InputValidation validation;
    public AccountGraphqlController(AccountService service, InputValidation validation) {
        this.service = service; this.validation = validation;
    }
    @QueryMapping
    public AccountResponse accountById(@Argument UUID id) { return AccountResponse.from(service.get(id)); }
    @QueryMapping
    public List<AccountResponse> accounts() { return service.list().stream().map(AccountResponse::from).toList(); }
    @MutationMapping
    public AccountResponse createAccount(@Argument CreateAccountRequest input) {
        validation.validate(input);
        return AccountResponse.from(service.create(input.customerReference(), input.currency(), input.balance()));
    }
    @MutationMapping
    public AccountResponse updateAccountStatus(@Argument UUID id, @Argument AccountStatus status) {
        return AccountResponse.from(service.updateStatus(id, status));
    }
}
