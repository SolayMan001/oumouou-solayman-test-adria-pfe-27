package com.adria.bank.transaction.client;

import java.time.Duration;
import feign.FeignException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AccountCircuitConfiguration {
    @Bean
    public CircuitBreakerRegistry accountCircuits(
            @Value("${bank-account-circuit.sliding-window-size:10}") int window,
            @Value("${bank-account-circuit.minimum-calls:5}") int minimum,
            @Value("${bank-account-circuit.failure-rate-threshold:50}") float threshold,
            @Value("${bank-account-circuit.open-duration:5s}") Duration openDuration,
            @Value("${bank-account-circuit.half-open-calls:2}") int halfOpen) {
        var configuration = CircuitBreakerConfig.custom()
                .slidingWindowSize(window).minimumNumberOfCalls(minimum)
                .failureRateThreshold(threshold).waitDurationInOpenState(openDuration)
                .permittedNumberOfCallsInHalfOpenState(halfOpen)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .ignoreException(AccountCircuitConfiguration::businessFailure)
                .recordException(error -> !businessFailure(error)).build();
        return CircuitBreakerRegistry.of(configuration);
    }

    static boolean businessFailure(Throwable error) {
        return error instanceof FeignException feign && feign.status() >= 400 && feign.status() < 500
                && feign.status() != 408 && feign.status() != 429;
    }
}
