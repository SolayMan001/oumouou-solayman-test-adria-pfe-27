package com.adria.bank.transaction.service;

import java.math.BigDecimal;
import java.util.UUID;

import com.adria.bank.transaction.repository.TransactionRepository;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class TransactionServiceTest {

    private static ValidatorFactory validatorFactory;

    @BeforeAll
    static void initializeValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    @Test
    void validPublicCreationIsUnavailableAndNeverTouchesRepository() {
        TransactionRepository repository = mock(TransactionRepository.class);
        TransactionWorkflow workflow = mock(TransactionWorkflow.class);
        TransactionService service = new TransactionService(repository, validator("1000"), workflow);

        org.mockito.Mockito.when(workflow.create(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new TransactionProcessingException(503, "ACCOUNT_INTEGRATION_UNAVAILABLE", "Account Service is unavailable."));
        assertThatThrownBy(() -> service.create(UUID.randomUUID(), command("100.00")))
                .isInstanceOf(TransactionProcessingException.class)
                .hasMessageContaining("Account Service is unavailable");
        verifyNoInteractions(repository);
    }

    @Test
    void amountAboveLimitIsRejectedBeforeIntegrationError() {
        TransactionRepository repository = mock(TransactionRepository.class);
        TransactionService service = new TransactionService(repository, validator("1000"), mock(TransactionWorkflow.class));

        assertThatThrownBy(() -> service.create(UUID.randomUUID(), command("1000.01")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1000");
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "0.001", "1000000000000"})
    void invalidAmountsAreRejectedWithoutRestOrRepositoryAccess(String amount) {
        TransactionRepository repository = mock(TransactionRepository.class);
        TransactionService service = new TransactionService(repository, validator("1000"), mock(TransactionWorkflow.class));

        assertThatThrownBy(() -> service.create(UUID.randomUUID(), command(amount)))
                .isInstanceOf(ConstraintViolationException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void nullInputIsRejected() {
        assertThatThrownBy(() -> validator("1000").validate(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void maximumCanBeConfiguredBelowTheDefault() {
        TransactionValidator validator = validator("25.00");
        assertThatCode(() -> validator.validate(command("25.00"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validate(command("25.01")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void maximumCanBeConfiguredAboveTheDefaultWithoutAHardCodedLimit() {
        TransactionValidator validator = validator("2500.00");
        assertThatCode(() -> validator.validate(command("1500.00"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validate(command("2500.01")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private TransactionValidator validator(String maximum) {
        return new TransactionValidator(validatorFactory.getValidator(),
                new TransactionProperties(new BigDecimal(maximum)));
    }

    private TransactionCommand command(String amount) {
        return new TransactionCommand(UUID.randomUUID(), new BigDecimal(amount), "MAD", "GROCERIES", "MA");
    }
}
