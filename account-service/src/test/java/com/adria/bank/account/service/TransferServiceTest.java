package com.adria.bank.account.service;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import com.adria.bank.account.domain.*;
import com.adria.bank.account.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TransferServiceTest {
    @Autowired AccountService accounts;
    @Autowired AccountRepository accountRepository;
    @Autowired TransferService service;
    @Autowired TransferRepository transfers;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ToolCallbackProvider callbacks;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired EntityManager entityManager;
    UUID source;
    UUID destination;

    @BeforeEach void setup() {
        transfers.deleteAll(); accountRepository.deleteAll();
        source = accounts.create("source", "MAD", new BigDecimal("100.00")).getId();
        destination = accounts.create("destination", "MAD", new BigDecimal("50.00")).getId();
    }

    @Test void successfulTransferIsAtomicAndIdempotent() {
        UUID key = UUID.randomUUID();
        var first = service.transfer(key, command("10.25"));
        assertThat(service.transfer(key, command("10.25")).getId()).isEqualTo(first.getId());
        balances(89.75, 60.25);
        assertThat(transfers.count()).isEqualTo(1);
    }

    @Test void insufficientFundsRejectBothChanges() {
        assertThatThrownBy(() -> service.transfer(UUID.randomUUID(), command("100.01")))
                .isInstanceOf(AccountConflictException.class);
        unchanged();
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void eitherBlockedAccountRejectsTransfer(boolean blockSource) {
        accounts.updateStatus(blockSource ? source : destination, AccountStatus.BLOCKED);
        assertThatThrownBy(() -> service.transfer(UUID.randomUUID(), command("10"))).isInstanceOf(AccountConflictException.class);
        unchanged();
    }

    @Test void currencyMismatchRejectsTransfer() {
        UUID euro = accounts.create("euro", "EUR", BigDecimal.TEN).getId();
        assertThatThrownBy(() -> service.transfer(UUID.randomUUID(), new TransferCommand(source, euro, BigDecimal.ONE)))
                .isInstanceOf(AccountConflictException.class);
        unchanged();
        assertThat(accounts.get(euro).getBalance()).isEqualTo(10.0);
    }

    @ParameterizedTest @ValueSource(strings = {"0", "-1", "0.001"})
    void invalidAmountsAreRejectedAtTheSharedServiceBoundary(String amount) {
        assertThatThrownBy(() -> service.transfer(UUID.randomUUID(), command(amount)))
                .isInstanceOf(jakarta.validation.ConstraintViolationException.class);
        unchanged();
    }

    @Test void missingAccountsAndSelfTransferAreRejected() {
        assertThatThrownBy(() -> service.transfer(UUID.randomUUID(), new TransferCommand(source, UUID.randomUUID(), BigDecimal.ONE)))
                .isInstanceOf(AccountNotFoundException.class);
        assertThatThrownBy(() -> service.transfer(UUID.randomUUID(), new TransferCommand(source, source, BigDecimal.ONE)))
                .isInstanceOf(IllegalArgumentException.class);
        unchanged();
    }

    @Test void keyWithChangedAmountOrDirectionConflicts() {
        UUID key = UUID.randomUUID();
        service.transfer(key, command("10"));
        assertThatThrownBy(() -> service.transfer(key, command("11"))).isInstanceOf(AccountConflictException.class);
        assertThatThrownBy(() -> service.transfer(key, new TransferCommand(destination, source, BigDecimal.TEN)))
                .isInstanceOf(AccountConflictException.class);
        balances(90, 60);
        assertThat(transfers.count()).isEqualTo(1);
    }

    @Test void destinationLimitFailureRollsBackSourceDebit() {
        accounts.credit(destination, new BigDecimal("999999999949.99"));
        assertThatThrownBy(() -> service.transfer(UUID.randomUUID(), command("1"))).isInstanceOf(IllegalArgumentException.class);
        balances(100, 999999999999.99);
        assertThat(transfers.count()).isZero();
    }

    @Test void failureAfterFlushedBalanceAndReceiptWritesRollsEverythingBack() {
        var transaction = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            service.transfer(UUID.randomUUID(), command("10"));
            entityManager.flush();
            throw new IllegalStateException("Injected failure before commit");
        })).isInstanceOf(IllegalStateException.class);
        unchanged();
    }

    @Test void concurrentDuplicatesMoveMoneyOnce() throws Exception {
        UUID key = UUID.randomUUID();
        concurrent(6, worker -> { service.transfer(key, command("10")); });
        balances(90, 60);
        assertThat(transfers.count()).isEqualTo(1);
    }

    @Test void oppositeConcurrentTransfersUseStableLockOrderingAndConserveMoney() throws Exception {
        concurrent(4, worker -> {
            for (int i = 0; i < 10; i++) {
                var command = worker % 2 == 0 ? command("1") : new TransferCommand(destination, source, BigDecimal.ONE);
                service.transfer(UUID.randomUUID(), command);
            }
        });
        balances(100, 50);
        assertThat(transfers.count()).isEqualTo(40);
    }

    @Test void oneKeyAcrossDisjointPairsCannotCommitTwoTransfers() throws Exception {
        UUID otherSource = accounts.create("other-source", "MAD", new BigDecimal("100")).getId();
        UUID otherDestination = accounts.create("other-dest", "MAD", new BigDecimal("50")).getId();
        UUID key = UUID.randomUUID();
        concurrent(2, worker -> {
            var command = worker == 0 ? command("10") : new TransferCommand(otherSource, otherDestination, BigDecimal.TEN);
            try { service.transfer(key, command); }
            catch (AccountConflictException | org.springframework.dao.DataIntegrityViolationException conflict) { /* One winner only. */ }
        });
        assertThat(transfers.count()).isEqualTo(1);
        assertThat(accounts.get(source).getBalance() + accounts.get(otherSource).getBalance()).isEqualTo(190);
        assertThat(accounts.get(destination).getBalance() + accounts.get(otherDestination).getBalance()).isEqualTo(110);
    }

    @Test void restGraphqlAndMcpShareTheSameTransferKeyAndReceipt() throws Exception {
        UUID key = UUID.randomUUID();
        String body = mapper.writeValueAsString(command("10"));
        mvc.perform(post("/api/transfers").header("Idempotency-Key", key).contentType("application/json").content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(key.toString()));
        String query = "mutation { transfer(input:{sourceAccountId:\"" + source + "\",destinationAccountId:\"" + destination
                + "\",amount:\"10\"},idempotencyKey:\"" + key + "\") { id amount } }";
        mvc.perform(post("/graphql").contentType("application/json").content(mapper.writeValueAsString(Map.of("query", query))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.transfer.id").value(key.toString()));
        var callback = Arrays.stream(callbacks.getToolCallbacks()).filter(tool -> tool.getToolDefinition().name().equals("transfer")).findFirst().orElseThrow();
        String result = callback.call(mapper.writeValueAsString(Map.of("input", command("10"), "idempotencyKey", key)));
        assertThat(mapper.readTree(result).path("id").asText()).isEqualTo(key.toString());
        mvc.perform(get("/api/transfers/{id}", key)).andExpect(status().isOk());
        balances(90, 60);
        assertThat(transfers.count()).isEqualTo(1);
    }

    @Test void invalidGraphqlAndMcpTransfersCannotBypassValidation() throws Exception {
        UUID key = UUID.randomUUID();
        String query = "mutation { transfer(input:{sourceAccountId:\"" + source + "\",destinationAccountId:\"" + destination
                + "\",amount:\"-1\"},idempotencyKey:\"" + key + "\") { id } }";
        mvc.perform(post("/graphql").contentType("application/json").content(mapper.writeValueAsString(Map.of("query", query))))
                .andExpect(jsonPath("$.errors[0].extensions.httpStatus").value(400));
        var callback = Arrays.stream(callbacks.getToolCallbacks()).filter(tool -> tool.getToolDefinition().name().equals("transfer")).findFirst().orElseThrow();
        assertThatThrownBy(() -> callback.call(mapper.writeValueAsString(Map.of("input", command("-1"), "idempotencyKey", key))))
                .isInstanceOf(RuntimeException.class);
        unchanged();
    }

    private TransferCommand command(String amount) { return new TransferCommand(source, destination, new BigDecimal(amount)); }
    private void balances(double expectedSource, double expectedDestination) {
        assertThat(accounts.get(source).getBalance()).isEqualTo(expectedSource);
        assertThat(accounts.get(destination).getBalance()).isEqualTo(expectedDestination);
    }
    private void unchanged() { balances(100, 50); assertThat(transfers.count()).isZero(); }
    private void concurrent(int workers, java.util.function.IntConsumer action) throws Exception {
        try (var pool = Executors.newFixedThreadPool(workers)) {
            var start = new CountDownLatch(1);
            List<Future<?>> results = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                int worker = i;
                results.add(pool.submit(() -> { start.await(); action.accept(worker); return null; }));
            }
            start.countDown();
            for (var result : results) { result.get(30, TimeUnit.SECONDS); }
        }
    }
}
