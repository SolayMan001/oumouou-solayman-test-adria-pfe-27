package com.adria.bank.account.web.rest;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import com.adria.bank.account.service.AccountService;
import com.adria.bank.account.web.rest.dto.AccountResponse;
import com.adria.bank.account.web.rest.dto.AmountRequest;
import com.adria.bank.account.web.rest.dto.CreateAccountRequest;
import com.adria.bank.account.web.rest.dto.UpdateAccountStatusRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/accounts")
public class AccountRestController {

    private final AccountService service;

    public AccountRestController(AccountService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<AccountResponse> create(@Valid @RequestBody CreateAccountRequest request) {
        AccountResponse response = AccountResponse.from(
                service.create(request.customerReference(), request.currency(), request.balance()));
        return ResponseEntity.created(URI.create("/api/accounts/" + response.id())).body(response);
    }

    @GetMapping
    public List<AccountResponse> list() {
        return service.list().stream().map(AccountResponse::from).toList();
    }

    @GetMapping("/{id}")
    public AccountResponse get(@PathVariable UUID id) {
        return AccountResponse.from(service.get(id));
    }

    @PatchMapping("/{id}/status")
    public AccountResponse updateStatus(@PathVariable UUID id,
                                        @Valid @RequestBody UpdateAccountStatusRequest request) {
        return AccountResponse.from(service.updateStatus(id, request.status()));
    }

    @PostMapping("/{id}/debit")
    public AccountResponse debit(@PathVariable UUID id, @Valid @RequestBody AmountRequest request) {
        return AccountResponse.from(service.debit(id, request.amount()));
    }

    @PostMapping("/{id}/credit")
    public AccountResponse credit(@PathVariable UUID id, @Valid @RequestBody AmountRequest request) {
        return AccountResponse.from(service.credit(id, request.amount()));
    }
}
