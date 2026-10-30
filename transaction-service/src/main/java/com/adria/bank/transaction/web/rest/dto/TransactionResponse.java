package com.adria.bank.transaction.web.rest.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.adria.bank.transaction.domain.BankTransaction;

public record TransactionResponse(UUID id, UUID accountId, BigDecimal amount, String currency,
                                  String merchantCategory, String country, Instant createdAt) {

    public static TransactionResponse from(BankTransaction transaction) {
        return new TransactionResponse(transaction.getId(), transaction.getAccountId(), transaction.getAmount(),
                transaction.getCurrency(), transaction.getMerchantCategory(), transaction.getCountry(),
                transaction.getCreatedAt());
    }
}
