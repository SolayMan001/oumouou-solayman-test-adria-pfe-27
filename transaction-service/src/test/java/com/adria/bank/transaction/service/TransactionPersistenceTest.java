package com.adria.bank.transaction.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.stream.Stream;

import com.adria.bank.transaction.domain.BankTransaction;
import com.adria.bank.transaction.repository.TransactionRepository;
import com.adria.bank.transaction.service.internal.TransactionPersistence;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TransactionPersistenceTest {

    @Autowired
    private TransactionPersistence persistence;

    @Autowired
    private TransactionRepository repository;

    @BeforeEach
    void cleanDatabase() {
        repository.deleteAll();
    }

    @Test
    void validInternalPersistenceStoresExactDecimalAmountAndNormalizedFields() {
        UUID accountId = UUID.randomUUID();
        BankTransaction saved = persistence.persist(new TransactionCommand(accountId,
                new BigDecimal("19.90"), "mad", " GROCERIES ", " ma "));

        BankTransaction reloaded = repository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getAccountId()).isEqualTo(accountId);
        assertThat(reloaded.getAmount()).isEqualTo(new BigDecimal("19.90"));
        assertThat(reloaded.getCurrency()).isEqualTo("MAD");
        assertThat(reloaded.getMerchantCategory()).isEqualTo("GROCERIES");
        assertThat(reloaded.getCountry()).isEqualTo("MA");
        assertThat(reloaded.getCreatedAt()).isEqualTo(saved.getCreatedAt());
    }

    @Test
    void serverGeneratesDistinctTransactionIdsAndCurrentTimestamps() {
        Instant before = Instant.now().truncatedTo(ChronoUnit.MICROS);
        TransactionCommand command = new TransactionCommand(UUID.randomUUID(), new BigDecimal("1000.00"),
                "MAD", "GROCERIES", "MA");
        BankTransaction first = persistence.persist(command);
        BankTransaction second = persistence.persist(command);
        Instant after = Instant.now();

        assertThat(first.getId()).isNotNull().isNotEqualTo(second.getId());
        assertThat(second.getId()).isNotNull();
        assertThat(first.getCreatedAt()).isBetween(before, after);
        assertThat(second.getCreatedAt()).isBetween(before, after);
        assertThat(repository.count()).isEqualTo(2);
    }

    @ParameterizedTest
    @MethodSource("invalidCommands")
    void internalPersistenceEnforcesValidationAndSavesNothing(TransactionCommand command,
                                                              Class<? extends Exception> error) {
        assertThatThrownBy(() -> persistence.persist(command)).isInstanceOf(error);
        assertThat(repository.count()).isZero();
    }

    static Stream<Arguments> invalidCommands() {
        UUID accountId = UUID.randomUUID();
        return Stream.of(
                Arguments.of(new TransactionCommand(accountId, BigDecimal.ZERO, "MAD", "GROCERIES", "MA"), ConstraintViolationException.class),
                Arguments.of(new TransactionCommand(accountId, new BigDecimal("-1"), "MAD", "GROCERIES", "MA"), ConstraintViolationException.class),
                Arguments.of(new TransactionCommand(accountId, new BigDecimal("1000.01"), "MAD", "GROCERIES", "MA"), IllegalArgumentException.class),
                Arguments.of(new TransactionCommand(accountId, new BigDecimal("1.001"), "MAD", "GROCERIES", "MA"), ConstraintViolationException.class),
                Arguments.of(new TransactionCommand(null, BigDecimal.ONE, "MAD", "GROCERIES", "MA"), ConstraintViolationException.class),
                Arguments.of(new TransactionCommand(accountId, null, "MAD", "GROCERIES", "MA"), ConstraintViolationException.class),
                Arguments.of(new TransactionCommand(accountId, BigDecimal.ONE, null, "GROCERIES", "MA"), ConstraintViolationException.class),
                Arguments.of(new TransactionCommand(accountId, BigDecimal.ONE, " ", "GROCERIES", "MA"), ConstraintViolationException.class),
                Arguments.of(new TransactionCommand(accountId, BigDecimal.ONE, "MAD", null, "MA"), ConstraintViolationException.class),
                Arguments.of(new TransactionCommand(accountId, BigDecimal.ONE, "MAD", " ", "MA"), ConstraintViolationException.class),
                Arguments.of(new TransactionCommand(accountId, BigDecimal.ONE, "MAD", "GROCERIES", null), ConstraintViolationException.class),
                Arguments.of(new TransactionCommand(accountId, BigDecimal.ONE, "MAD", "GROCERIES", " "), ConstraintViolationException.class));
    }
}
