package com.adria.bank.account.web.rest;

import java.math.BigDecimal;
import java.util.UUID;
import com.adria.bank.account.domain.DebitOperation;
import com.adria.bank.account.service.DebitOperationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/accounts/{accountId}/debit-operations/{operationId}")
public class DebitOperationController {
    private final DebitOperationService service;
    public DebitOperationController(DebitOperationService service) { this.service = service; }

    public record DebitRequest(@NotNull @Positive @Digits(integer = 12, fraction = 2) BigDecimal amount,
            @NotBlank @Pattern(regexp = "[A-Za-z]{3}") String currency) { }
    public record DebitResponse(UUID id, UUID accountId, BigDecimal amount, String currency, DebitOperation.State state) {
        static DebitResponse from(DebitOperation op) {
            return new DebitResponse(op.getId(), op.getAccountId(), op.getAmount(), op.getCurrency(), op.getState());
        }
    }

    @PutMapping
    public DebitResponse debit(@PathVariable UUID accountId, @PathVariable UUID operationId, @Valid @RequestBody DebitRequest request) {
        return DebitResponse.from(service.debit(accountId, operationId, request.amount(), request.currency()));
    }

    @GetMapping
    public DebitResponse get(@PathVariable UUID accountId, @PathVariable UUID operationId) {
        return DebitResponse.from(service.get(accountId, operationId));
    }

    @PutMapping("/compensation")
    public DebitResponse compensate(@PathVariable UUID accountId, @PathVariable UUID operationId, @Valid @RequestBody DebitRequest request) {
        return DebitResponse.from(service.compensate(accountId, operationId, request.amount(), request.currency()));
    }
}
