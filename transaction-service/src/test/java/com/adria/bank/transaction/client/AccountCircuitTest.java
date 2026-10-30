package com.adria.bank.transaction.client;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import feign.FeignException;
import feign.Request;
import feign.Response;
import io.github.resilience4j.circuitbreaker.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountCircuitTest {
    private final AccountFeignClient client = mock(AccountFeignClient.class);
    private final CircuitBreakerRegistry registry = new AccountCircuitConfiguration()
            .accountCircuits(4, 2, 50, Duration.ofSeconds(1), 2);
    private final CircuitBreaker circuit = registry.circuitBreaker("account-service");
    private final AccountGateway gateway = new AccountGateway(client, registry);
    private final UUID id = UUID.randomUUID();

    @Test
    void dependencyFailuresOpenCircuitAndHalfOpenSuccessClosesIt() {
        when(client.get(id)).thenThrow(error(503));
        for (int i = 0; i < 2; i++) { assertThatThrownBy(() -> gateway.get(id)).isInstanceOf(FeignException.class); }
        assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThatThrownBy(() -> gateway.get(id)).isInstanceOf(CallNotPermittedException.class);
        verify(client, times(2)).get(id);
        circuit.transitionToHalfOpenState();
        doReturn(new AccountFeignClient.AccountView(id, "customer", "ACTIVE", "MAD", 100)).when(client).get(id);
        gateway.get(id); gateway.get(id);
        assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void business4xxDoNotCountAsDependencyFailures() {
        for (int status : new int[]{400, 404, 409, 422}) {
            doThrow(error(status)).when(client).get(id);
            assertThatThrownBy(() -> gateway.get(id)).isInstanceOf(FeignException.class);
        }
        assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(circuit.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {-1, 408, 429, 500, 503})
    void networkTimeoutRateLimitAndServerFailuresCountAgainstCircuit(int status) {
        when(client.get(id)).thenThrow(error(status));
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> gateway.get(id)).isInstanceOf(FeignException.class);
        }
        assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(circuit.getMetrics().getNumberOfFailedCalls()).isEqualTo(2);
    }

    @Test
    void mutationFailureIsNotRetriedAndRecoveryReadsAreProtected() {
        var request = new AccountFeignClient.DebitRequest(java.math.BigDecimal.ONE, "MAD");
        when(client.debit(id, id, request)).thenThrow(error(-1));
        assertThatThrownBy(() -> gateway.debit(id, id, request)).isInstanceOf(FeignException.class);
        verify(client, times(1)).debit(id, id, request);
        when(client.operation(id, id)).thenThrow(error(500));
        assertThatThrownBy(() -> gateway.operation(id, id)).isInstanceOf(FeignException.class);
        assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThatThrownBy(() -> gateway.compensate(id, id, request)).isInstanceOf(CallNotPermittedException.class);
        verify(client, never()).compensate(any(), any(), any());
    }

    private FeignException error(int status) {
        Request request = Request.create(Request.HttpMethod.GET, "http://account/api/accounts", Map.of(), null, java.nio.charset.StandardCharsets.UTF_8, null);
        return FeignException.errorStatus("account", Response.builder().request(request).status(status).headers(Map.of()).build());
    }
}
