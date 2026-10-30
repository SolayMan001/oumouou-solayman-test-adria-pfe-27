package com.adria.bank.transaction.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.adria.bank.transaction.domain.ProcessingOperation;
import com.adria.bank.transaction.repository.*;
import com.adria.bank.transaction.service.internal.TransactionPersistence;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Exercises real OpenFeign HTTP encoding, error mapping and timeouts against a fault-injection fixture. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TransactionWorkflowTest {
    static final AccountHttpFixture remote = new AccountHttpFixture();
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("account.service.url", () -> "http://127.0.0.1:" + remote.server.getAddress().getPort());
        properties.add("spring.cloud.openfeign.client.config.account-service.readTimeout", () -> 300);
        properties.add("spring.datasource.url", () -> "jdbc:h2:mem:workflow-tests;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
    }
    @Autowired io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry circuits;
    @Autowired org.springframework.ai.tool.ToolCallbackProvider toolCallbacks;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired com.adria.bank.transaction.events.OutboxRepository outbox;
    @Autowired TransactionRepository transactions;
    @Autowired ProcessingOperationRepository operations;
    @MockitoSpyBean TransactionPersistence persistence;

    @BeforeEach
    void setup() { circuits.circuitBreaker("account-service").reset(); outbox.deleteAll(); operations.deleteAll(); transactions.deleteAll(); remote.reset(); }
    @AfterAll
    static void shutdown() { remote.server.stop(0); remote.executor.shutdownNow(); }

    @Test
    void validTransactionReturns201DebitsAndPersists() throws Exception {
        UUID key = UUID.randomUUID();
        String result = submit(key, "10.00", "MAD", 201);
        assertThat(remote.balance).isEqualByComparingTo("90.00");
        assertThat(transactions.count()).isEqualTo(1);
        assertThat(operations.findById(key).orElseThrow().getState()).isEqualTo(ProcessingOperation.State.COMPLETED);
        assertThat(mapper.readTree(result).get("amount").decimalValue()).isEqualByComparingTo("10.00");
    }

    @ParameterizedTest
    @CsvSource({"missing,404,ACCOUNT_NOT_FOUND", "blocked,409,ACCOUNT_BLOCKED", "insufficient,409,INSUFFICIENT_BALANCE", "unavailable,503,ACCOUNT_INTEGRATION_UNAVAILABLE"})
    void accountErrorsDoNotDebitOrPersist(String condition, int status, String code) throws Exception {
        remote.condition = condition;
        String response = submit(UUID.randomUUID(), "10.00", "MAD", status);
        assertThat(mapper.readTree(response).get("code").asText()).isEqualTo(code);
        assertThat(remote.debitCalls.get()).isZero();
        assertThat(transactions.count()).isZero();
    }

    @Test
    void currencyMismatchDoesNotDebitOrPersist() throws Exception {
        String response = submit(UUID.randomUUID(), "10.00", "EUR", 409);
        assertThat(mapper.readTree(response).get("code").asText()).isEqualTo("CURRENCY_MISMATCH");
        assertThat(remote.debitCalls.get()).isZero();
        assertThat(transactions.count()).isZero();
    }

    @Test
    void excessiveAmountDoesNotContactAccountService() throws Exception {
        submit(UUID.randomUUID(), "1000.01", "MAD", 400);
        assertThat(remote.accountCalls.get()).isZero();
        assertThat(remote.debitCalls.get()).isZero();
        assertThat(operations.count()).isZero();
    }

    @Test
    void duplicateRequestReturnsTheSameTransactionWithoutAnotherDebit() throws Exception {
        UUID key = UUID.randomUUID();
        String first = submit(key, "10.00", "MAD", 201);
        String duplicate = submit(key, "10.00", "MAD", 201);
        assertThat(mapper.readTree(duplicate).get("id")).isEqualTo(mapper.readTree(first).get("id"));
        assertThat(remote.debitCalls.get()).isEqualTo(1);
        assertThat(remote.balance).isEqualByComparingTo("90.00");
        assertThat(transactions.count()).isEqualTo(1);
        assertThat(outbox.count()).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentPayloadReturns409() throws Exception {
        UUID key = UUID.randomUUID();
        submit(key, "10.00", "MAD", 201);
        String response = submit(key, "11.00", "MAD", 409);
        assertThat(mapper.readTree(response).get("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(remote.debitCalls.get()).isEqualTo(1);
        assertThat(transactions.count()).isEqualTo(1);
    }

    @Test
    void concurrentDuplicatesCauseAtMostOneDebitAndOneTransaction() throws Exception {
        UUID key = UUID.randomUUID();
        try (var executor = Executors.newFixedThreadPool(6)) {
            var start = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return mvc.perform(post("/api/transactions").header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON).content(body("10.00", "MAD")))
                            .andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            for (var result : results) { assertThat(result.get(15, TimeUnit.SECONDS)).isIn(201, 503); }
        }
        submit(key, "10.00", "MAD", 201);
        assertThat(remote.debitCalls.get()).isEqualTo(1);
        assertThat(remote.balance).isEqualByComparingTo("90.00");
        assertThat(transactions.count()).isEqualTo(1);
    }

    @Test
    void failedLocalPersistenceLeavesDurableIntentAndCanReconcileWithoutAnotherDebit() throws Exception {
        UUID key = UUID.randomUUID();
        doThrow(new DataIntegrityViolationException("Injected database failure"))
                .when(persistence).persist(any(TransactionCommand.class), any(UUID.class));
        submit(key, "10.00", "MAD", 503);
        assertThat(remote.balance).isEqualByComparingTo("90.00");
        assertThat(transactions.count()).isZero();
        assertThat(operations.findById(key).orElseThrow().getState()).isEqualTo(ProcessingOperation.State.PENDING);
        reset(persistence);
        submit(key, "10.00", "MAD", 201);
        assertThat(remote.debitCalls.get()).isEqualTo(1);
        assertThat(transactions.count()).isEqualTo(1);
    }

    @Test
    void failedLocalPersistenceCanBeCompensatedExactlyOnce() throws Exception {
        UUID key = UUID.randomUUID();
        doThrow(new DataIntegrityViolationException("Injected database failure"))
                .when(persistence).persist(any(TransactionCommand.class), any(UUID.class));
        submit(key, "10.00", "MAD", 503);
        mvc.perform(post("/api/transactions/operations/{id}/cancel", key))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("CANCELLED"));
        mvc.perform(post("/api/transactions/operations/{id}/cancel", key)).andExpect(status().isOk());
        assertThat(remote.balance).isEqualByComparingTo("100.00");
        assertThat(remote.compensationCalls.get()).isEqualTo(1);
        submit(key, "10.00", "MAD", 409);
        assertThat(transactions.count()).isZero();
    }

    @Test
    void debitTimeoutAfterCommitIsReconciledUsingLedgerWithoutResendingDebit() throws Exception {
        UUID key = UUID.randomUUID();
        remote.condition = "timeout-after-debit";
        submit(key, "10.00", "MAD", 503);
        assertThat(operations.findById(key).orElseThrow().getState()).isEqualTo(ProcessingOperation.State.UNCERTAIN);
        submit(key, "10.00", "MAD", 201);
        assertThat(remote.debitCalls.get()).isEqualTo(1);
        assertThat(remote.balance).isEqualByComparingTo("90.00");
        assertThat(transactions.count()).isEqualTo(1);
    }

    @Test
    void missingLedgerAfterTimeoutDoesNotTriggerAnotherDebitAndCancellationStopsDelayedDebit() throws Exception {
        UUID key = UUID.randomUUID();
        remote.condition = "timeout-before-debit";
        submit(key, "10.00", "MAD", 503);
        submit(key, "10.00", "MAD", 503);
        assertThat(remote.debitCalls.get()).isEqualTo(1);
        mvc.perform(post("/api/transactions/operations/{id}/cancel", key)).andExpect(status().isOk());
        assertThat(remote.finished.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(remote.balance).isEqualByComparingTo("100.00");
        assertThat(transactions.count()).isZero();
    }

    @Test
    void recoveryObservesCompensationCommittedBeforeALocalCrash() throws Exception {
        UUID key = UUID.randomUUID();
        doThrow(new DataIntegrityViolationException("Injected database failure"))
                .when(persistence).persist(any(TransactionCommand.class), any(UUID.class));
        submit(key, "10.00", "MAD", 503);
        // Model compensation having committed remotely before the local cancellation marker committed.
        remote.ledger.put(key.toString(), remote.operation(key.toString(), new BigDecimal("10.00"), "COMPENSATED"));
        remote.balance = new BigDecimal("100.00");
        reset(persistence);
        submit(key, "10.00", "MAD", 409);
        assertThat(operations.findById(key).orElseThrow().getState()).isEqualTo(ProcessingOperation.State.CANCELLED);
        assertThat(transactions.count()).isZero();
        assertThat(remote.debitCalls.get()).isEqualTo(1);
    }

    @Test
    void completedTransactionCannotBeCompensatedByRecovery() throws Exception {
        UUID key = UUID.randomUUID();
        submit(key, "10.00", "MAD", 201);
        mvc.perform(post("/api/transactions/operations/{id}/cancel", key))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_COMPLETED"));
        assertThat(remote.compensationCalls.get()).isZero();
        assertThat(remote.balance).isEqualByComparingTo("90.00");
    }

    @Test
    void idempotencyKeyIsRequired() throws Exception {
        mvc.perform(post("/api/transactions").contentType(MediaType.APPLICATION_JSON).content(body("10.00", "MAD")))
                .andExpect(status().isBadRequest());
        assertThat(remote.debitCalls.get()).isZero();
    }

    @Test
    void graphqlCreationAndRestMcpReplaysShareOneDebit() throws Exception {
        UUID key = UUID.randomUUID();
        var response = mapper.readTree(graphql(key, "MAD"));
        String id = response.at("/data/createTransaction/id").asText();
        assertThat(response.path("errors").isMissingNode()).isTrue();
        assertThat(id).isNotEmpty();
        assertThat(mapper.readTree(createTool().call(toolInput(key, "MAD"))).path("id").asText()).isEqualTo(id);
        assertThat(mapper.readTree(submit(key, "10.00", "MAD", 201)).path("id").asText()).isEqualTo(id);
        assertThat(remote.debitCalls.get()).isEqualTo(1);
        assertThat(remote.balance).isEqualByComparingTo("90.00");
        assertThat(transactions.count()).isEqualTo(1);
    }

    @Test
    void mcpCreationUsesAccountDebitAndOriginalWorkflow() throws Exception {
        UUID key = UUID.randomUUID();
        String id = mapper.readTree(createTool().call(toolInput(key, "MAD"))).path("id").asText();
        assertThat(id).isNotEmpty();
        assertThat(mapper.readTree(graphql(key, "MAD")).at("/data/createTransaction/id").asText()).isEqualTo(id);
        assertThat(remote.balance).isEqualByComparingTo("90.00");
        assertThat(remote.debitCalls.get()).isEqualTo(1);
        assertThat(transactions.count()).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({"blocked,MAD,ACCOUNT_BLOCKED", "insufficient,MAD,INSUFFICIENT_BALANCE", "active,EUR,CURRENCY_MISMATCH"})
    void graphqlAndMcpCannotBypassAccountBusinessRules(String condition, String currency, String code) throws Exception {
        remote.condition = condition;
        var response = mapper.readTree(graphql(UUID.randomUUID(), currency));
        assertThat(response.at("/errors/0/extensions/code").asText()).isEqualTo(code);
        assertThatThrownBy(() -> createTool().call(toolInput(UUID.randomUUID(), currency))).isInstanceOf(RuntimeException.class);
        assertThat(remote.debitCalls.get()).isZero();
        assertThat(transactions.count()).isZero();
    }

    @Test
    void ambiguousMcpTimeoutCanBeReconciledThroughGraphqlWithoutAnotherDebit() throws Exception {
        UUID key = UUID.randomUUID();
        remote.condition = "timeout-after-debit";
        assertThatThrownBy(() -> createTool().call(toolInput(key, "MAD"))).isInstanceOf(RuntimeException.class);
        var response = mapper.readTree(graphql(key, "MAD"));
        assertThat(response.path("errors").isMissingNode()).isTrue();
        assertThat(response.at("/data/createTransaction/id").asText()).isNotEmpty();
        assertThat(remote.debitCalls.get()).isEqualTo(1);
        assertThat(remote.balance).isEqualByComparingTo("90.00");
        assertThat(transactions.count()).isEqualTo(1);
    }

    private org.springframework.ai.tool.ToolCallback createTool() {
        return Arrays.stream(toolCallbacks.getToolCallbacks()).filter(tool -> tool.getToolDefinition().name().equals("createTransaction")).findFirst().orElseThrow();
    }
    private String toolInput(UUID key, String currency) throws Exception {
        return mapper.writeValueAsString(Map.of("idempotencyKey", key.toString(), "input",
                mapper.readTree(body("10.00", currency))));
    }
    private String graphql(UUID key, String currency) throws Exception {
        var input = Map.of("accountId", remote.accountId.toString(), "amount", "10.00", "currency", currency,
                "merchantCategory", "GROCERIES", "country", "MA");
        String query = "mutation($input:CreateTransactionInput!,$key:ID!) { createTransaction(input:$input,idempotencyKey:$key) { id } }";
        return mvc.perform(post("/graphql").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("query", query, "variables", Map.of("input", input, "key", key.toString())))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private String submit(UUID key, String amount, String currency, int expectedStatus) throws Exception {
        return mvc.perform(post("/api/transactions").header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body(amount, currency)))
                .andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString();
    }
    private String body(String amount, String currency) {
        return "{\"accountId\":\"" + remote.accountId + "\",\"amount\":" + amount
                + ",\"currency\":\"" + currency + "\",\"merchantCategory\":\"GROCERIES\",\"country\":\"MA\"}";
    }

    static class AccountHttpFixture {
        final HttpServer server;
        final ExecutorService executor = Executors.newCachedThreadPool();
        final ObjectMapper mapper = new ObjectMapper();
        final UUID accountId = UUID.randomUUID();
        final AtomicInteger accountCalls = new AtomicInteger();
        final AtomicInteger debitCalls = new AtomicInteger();
        final AtomicInteger compensationCalls = new AtomicInteger();
        final Map<String, Map<String, Object>> ledger = new ConcurrentHashMap<>();
        volatile BigDecimal balance;
        volatile String condition;
        volatile CountDownLatch finished;

        AccountHttpFixture() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.setExecutor(executor);
                server.createContext("/api/accounts/", this::handle);
                server.start(); reset();
            } catch (IOException error) { throw new IllegalStateException(error); }
        }
        void reset() {
            balance = new BigDecimal("100.00"); condition = "active"; ledger.clear();
            accountCalls.set(0); debitCalls.set(0); compensationCalls.set(0); finished = new CountDownLatch(1);
        }
        void handle(HttpExchange exchange) throws IOException {
            String[] segments = exchange.getRequestURI().getPath().split("/");
            if (segments.length == 4) {
                accountCalls.incrementAndGet();
                if (condition.equals("unavailable")) { respond(exchange, 503, Map.of()); return; }
                if (condition.equals("missing")) { respond(exchange, 404, Map.of()); return; }
                respond(exchange, 200, Map.of("id", accountId, "customerReference", "customer",
                        "status", condition.equals("blocked") ? "BLOCKED" : "ACTIVE", "currency", "MAD",
                        "balance", condition.equals("insufficient") ? BigDecimal.ONE : balance));
                return;
            }
            String key = segments[5];
            if (exchange.getRequestMethod().equals("GET")) {
                var op = ledger.get(key); respond(exchange, op == null ? 404 : 200, op == null ? Map.of() : op); return;
            }
            boolean compensation = segments.length == 7;
            var input = mapper.readTree(exchange.getRequestBody());
            BigDecimal amount = input.get("amount").decimalValue();
            if (!compensation) { debitCalls.incrementAndGet(); }
            String currentCondition = condition;
            CountDownLatch completion = finished;
            if (!compensation && currentCondition.equals("timeout-before-debit")) { pause(); }
            Map<String, Object> operation;
            int status = 200;
            synchronized (this) {
                operation = ledger.get(key);
                if (compensation) {
                    compensationCalls.incrementAndGet();
                    if (operation == null) { operation = operation(key, amount, "CANCELLED"); }
                    else if (operation.get("state").equals("DEBITED")) {
                        balance = balance.add(amount); operation = operation(key, amount, "COMPENSATED");
                    }
                    ledger.put(key, operation);
                } else if (operation == null) {
                    balance = balance.subtract(amount); operation = operation(key, amount, "DEBITED"); ledger.put(key, operation);
                } else if (!operation.get("state").equals("DEBITED")) { status = 409; }
            }
            if (!compensation && currentCondition.equals("timeout-after-debit")) { pause(); }
            try { respond(exchange, status, operation); }
            finally { if (!compensation) { completion.countDown(); } }
        }
        Map<String, Object> operation(String key, BigDecimal amount, String state) {
            return Map.of("id", key, "accountId", accountId, "amount", amount, "currency", "MAD", "state", state);
        }
        void pause() {
            try { Thread.sleep(800); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        }
        void respond(HttpExchange exchange, int status, Object body) throws IOException {
            byte[] bytes = mapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            try { exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); }
            finally { exchange.close(); }
        }
    }
}
