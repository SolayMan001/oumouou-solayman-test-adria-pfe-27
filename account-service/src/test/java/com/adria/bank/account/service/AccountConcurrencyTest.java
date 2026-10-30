package com.adria.bank.account.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.adria.bank.account.domain.AccountConflictException;
import com.adria.bank.account.repository.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountConcurrencyTest {

    @Autowired
    private AccountService service;

    @Autowired
    private AccountRepository repository;

    @BeforeEach
    void cleanDatabase() {
        repository.deleteAll();
    }

    @Test
    void concurrentDebitsAndCreditsDoNotLoseUpdates() throws Exception {
        UUID id = service.create("customer", "MAD", new BigDecimal("100.00")).getId();
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        // Each invocation goes through the Spring service proxy in its own transaction.
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int worker = 0; worker < 8; worker++) {
                boolean credit = worker % 2 == 0;
                futures.add(executor.submit(() -> {
                    awaitStart(ready, start);
                    for (int operation = 0; operation < 10; operation++) {
                        if (credit) {
                            service.credit(id, new BigDecimal("0.20"));
                        } else {
                            service.debit(id, new BigDecimal("0.10"));
                        }
                    }
                }));
            }
            releaseWorkers(ready, start);
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        }
        assertThat(service.get(id).getBalance()).isEqualTo(104.0);
    }

    @Test
    void concurrentDebitsCannotSpendTheSameFundsTwice() throws Exception {
        UUID id = service.create("customer", "MAD", new BigDecimal("10.00")).getId();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int worker = 0; worker < 2; worker++) {
                futures.add(executor.submit(() -> {
                    awaitStart(ready, start);
                    try {
                        service.debit(id, new BigDecimal("7.00"));
                        return true;
                    } catch (AccountConflictException exception) {
                        assertThat(exception.getCode()).isEqualTo("INSUFFICIENT_BALANCE");
                        return false;
                    }
                }));
            }
            releaseWorkers(ready, start);
            int successfulDebits = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(30, TimeUnit.SECONDS)) {
                    successfulDebits++;
                }
            }
            assertThat(successfulDebits).isEqualTo(1);
        }
        assertThat(service.get(id).getBalance()).isEqualTo(3.0);
    }

    private static void releaseWorkers(CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        try {
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            start.countDown();
        }
    }

    private static void awaitStart(CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for concurrent workers.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent worker was interrupted.", exception);
        }
    }
}
