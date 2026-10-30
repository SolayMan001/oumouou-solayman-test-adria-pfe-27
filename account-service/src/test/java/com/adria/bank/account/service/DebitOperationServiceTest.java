package com.adria.bank.account.service;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.*;
import com.adria.bank.account.domain.*;
import com.adria.bank.account.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DebitOperationServiceTest {
    @Autowired DebitOperationService operations;
    @Autowired AccountService accounts;
    @Autowired AccountRepository accountRepository;
    @Autowired DebitOperationRepository ledger;
    @Autowired MockMvc mvc;
    UUID accountId;
    UUID operationId;
    final BigDecimal amount = new BigDecimal("10.00");

    @BeforeEach
    void setup() {
        ledger.deleteAll(); accountRepository.deleteAll();
        accountId = accounts.create("customer", "MAD", new BigDecimal("100.00")).getId();
        operationId = UUID.randomUUID();
    }

    @Test
    void repeatedDebitIsAppliedOnce() {
        operations.debit(accountId, operationId, amount, "MAD");
        operations.debit(accountId, operationId, amount, "mad");
        assertThat(accounts.get(accountId).getBalance()).isEqualTo(90.0);
        assertThat(ledger.count()).isEqualTo(1);
    }

    @Test
    void changedPayloadConflictsWithoutAnotherDebit() {
        operations.debit(accountId, operationId, amount, "MAD");
        assertThatThrownBy(() -> operations.debit(accountId, operationId, BigDecimal.ONE, "MAD"))
                .isInstanceOf(AccountConflictException.class);
        assertThat(accounts.get(accountId).getBalance()).isEqualTo(90.0);
    }

    @Test
    void compensationIsIdempotentAndWorksAfterAccountIsBlocked() {
        operations.debit(accountId, operationId, amount, "MAD");
        accounts.updateStatus(accountId, AccountStatus.BLOCKED);
        operations.compensate(accountId, operationId, amount, "MAD");
        operations.compensate(accountId, operationId, amount, "MAD");
        assertThat(accounts.get(accountId).getBalance()).isEqualTo(100.0);
        assertThat(operations.get(accountId, operationId).getState()).isEqualTo(DebitOperation.State.COMPENSATED);
        assertThatThrownBy(() -> operations.debit(accountId, operationId, amount, "MAD"))
                .isInstanceOf(AccountConflictException.class);
    }

    @Test
    void cancellationBeforeDelayedDebitCreatesADurableBarrier() {
        operations.compensate(accountId, operationId, amount, "MAD");
        operations.compensate(accountId, operationId, amount, "MAD");
        assertThatThrownBy(() -> operations.debit(accountId, operationId, amount, "MAD"))
                .isInstanceOf(AccountConflictException.class);
        assertThat(accounts.get(accountId).getBalance()).isEqualTo(100.0);
        assertThat(operations.get(accountId, operationId).getState()).isEqualTo(DebitOperation.State.CANCELLED);
    }

    @Test
    void compensationWithChangedPayloadIsRejected() {
        operations.debit(accountId, operationId, amount, "MAD");
        assertThatThrownBy(() -> operations.compensate(accountId, operationId, BigDecimal.ONE, "MAD"))
                .isInstanceOf(AccountConflictException.class);
        assertThat(accounts.get(accountId).getBalance()).isEqualTo(90.0);
    }

    @Test
    void concurrentDuplicatesDebitOnce() throws Exception {
        try (var pool = Executors.newFixedThreadPool(6)) {
            CountDownLatch gate = new CountDownLatch(1);
            List<Future<?>> results = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                results.add(pool.submit(() -> {
                    gate.await(); operations.debit(accountId, operationId, amount, "MAD"); return null;
                }));
            }
            gate.countDown();
            for (var result : results) { result.get(15, TimeUnit.SECONDS); }
        }
        assertThat(accounts.get(accountId).getBalance()).isEqualTo(90.0);
        assertThat(ledger.count()).isEqualTo(1);
    }

    @Test
    void sameOperationCannotDebitTwoDifferentAccountsEvenConcurrently() throws Exception {
        UUID other = accounts.create("other", "MAD", new BigDecimal("100.00")).getId();
        try (var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch gate = new CountDownLatch(1);
            List<Future<Boolean>> results = new ArrayList<>();
            for (UUID id : List.of(accountId, other)) {
                results.add(pool.submit(() -> {
                    gate.await();
                    try { operations.debit(id, operationId, amount, "MAD"); return true; }
                    catch (AccountConflictException | org.springframework.dao.DataIntegrityViolationException conflict) { return false; }
                }));
            }
            gate.countDown();
            int successes = 0;
            for (var result : results) { if (result.get(15, TimeUnit.SECONDS)) { successes++; } }
            assertThat(successes).isEqualTo(1);
        }
        assertThat(accounts.get(accountId).getBalance() + accounts.get(other).getBalance()).isEqualTo(190.0);
    }

    @Test
    void currencyMismatchDoesNotDebitOrWriteLedger() {
        assertThatThrownBy(() -> operations.debit(accountId, operationId, amount, "EUR"))
                .isInstanceOf(AccountConflictException.class);
        assertThat(ledger.count()).isZero();
        assertThat(accounts.get(accountId).getBalance()).isEqualTo(100.0);
    }

    @Test
    void failedCompensationDoesNotMarkLedgerAsRefunded() {
        operations.debit(accountId, operationId, amount, "MAD");
        accounts.credit(accountId, new BigDecimal("999999999909.99"));
        assertThatThrownBy(() -> operations.compensate(accountId, operationId, amount, "MAD"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(operations.get(accountId, operationId).getState()).isEqualTo(DebitOperation.State.DEBITED);
        assertThat(accounts.get(accountId).getBalance()).isEqualTo(999999999999.99);
    }

    @Test
    void cancellationRacingWithDebitNeverLeavesAnUncompensatedDebit() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch gate = new CountDownLatch(1);
            Future<?> debit = pool.submit(() -> {
                gate.await();
                try { operations.debit(accountId, operationId, amount, "MAD"); }
                catch (AccountConflictException cancelled) { /* Cancellation won the race. */ }
                return null;
            });
            Future<?> cancel = pool.submit(() -> {
                gate.await(); operations.compensate(accountId, operationId, amount, "MAD"); return null;
            });
            gate.countDown(); debit.get(15, TimeUnit.SECONDS); cancel.get(15, TimeUnit.SECONDS);
        }
        assertThat(accounts.get(accountId).getBalance()).isEqualTo(100.0);
        assertThat(operations.get(accountId, operationId).getState())
                .isIn(DebitOperation.State.CANCELLED, DebitOperation.State.COMPENSATED);
    }

    @Test
    void operationRestContractSupportsDebitQueryAndCompensation() throws Exception {
        String path = "/api/accounts/" + accountId + "/debit-operations/" + operationId;
        String body = "{\"amount\":10.00,\"currency\":\"MAD\"}";
        mvc.perform(put(path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("DEBITED"));
        mvc.perform(get(path)).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(operationId.toString()));
        mvc.perform(put(path + "/compensation").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("COMPENSATED"));
        assertThat(accounts.get(accountId).getBalance()).isEqualTo(100.0);
    }
}
