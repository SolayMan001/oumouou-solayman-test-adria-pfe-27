package com.adria.bank.transaction.client;

import java.math.BigDecimal;
import java.util.UUID;
import feign.Retryer;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.*;

@FeignClient(name = "account-service", url = "${account.service.url}", configuration = AccountFeignClient.Configuration.class)
public interface AccountFeignClient {
    record AccountView(UUID id, String customerReference, String status, String currency, double balance) { }
    record DebitRequest(BigDecimal amount, String currency) { }
    record DebitView(UUID id, UUID accountId, BigDecimal amount, String currency, String state) { }

    @GetMapping("/api/accounts/{accountId}")
    AccountView get(@PathVariable("accountId") UUID accountId);

    @PutMapping("/api/accounts/{accountId}/debit-operations/{operationId}")
    DebitView debit(@PathVariable("accountId") UUID accountId, @PathVariable("operationId") UUID operationId,
                    @RequestBody DebitRequest request);

    @GetMapping("/api/accounts/{accountId}/debit-operations/{operationId}")
    DebitView operation(@PathVariable("accountId") UUID accountId, @PathVariable("operationId") UUID operationId);

    @PutMapping("/api/accounts/{accountId}/debit-operations/{operationId}/compensation")
    DebitView compensate(@PathVariable("accountId") UUID accountId, @PathVariable("operationId") UUID operationId,
                         @RequestBody DebitRequest request);

    class Configuration {
        @Bean
        public Retryer accountRetryer() { return Retryer.NEVER_RETRY; }
    }
}
