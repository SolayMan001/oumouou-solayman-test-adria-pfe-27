package com.adria.bank.account.web.rest.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import com.adria.bank.account.domain.AccountTransfer;

public record TransferResponse(UUID id, UUID sourceAccountId, UUID destinationAccountId,
        BigDecimal amount, String currency, Instant createdAt) {
    public static TransferResponse from(AccountTransfer transfer) {
        return new TransferResponse(transfer.getId(), transfer.getSourceAccountId(), transfer.getDestinationAccountId(),
                transfer.getAmount(), transfer.getCurrency(), transfer.getCreatedAt());
    }
}
