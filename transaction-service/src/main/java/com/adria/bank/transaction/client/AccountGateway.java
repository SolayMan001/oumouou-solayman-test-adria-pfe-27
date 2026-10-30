package com.adria.bank.transaction.client;

import java.util.UUID;
import java.util.function.Supplier;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.stereotype.Component;

/** Every dependency call is circuit protected; financial writes are never retried here. */
@Component
public class AccountGateway {
    private final AccountFeignClient client;
    private final CircuitBreaker circuit;

    public AccountGateway(AccountFeignClient client, CircuitBreakerRegistry registry) {
        this.client = client;
        this.circuit = registry.circuitBreaker("account-service");
    }

    public AccountFeignClient.AccountView get(UUID accountId) {
        return call(() -> client.get(accountId));
    }

    public AccountFeignClient.DebitView debit(UUID accountId, UUID id, AccountFeignClient.DebitRequest request) {
        return call(() -> client.debit(accountId, id, request));
    }

    public AccountFeignClient.DebitView operation(UUID accountId, UUID id) {
        return call(() -> client.operation(accountId, id));
    }

    public AccountFeignClient.DebitView compensate(UUID accountId, UUID id, AccountFeignClient.DebitRequest request) {
        return call(() -> client.compensate(accountId, id, request));
    }

    private <T> T call(Supplier<T> action) { return circuit.executeSupplier(action); }
}
