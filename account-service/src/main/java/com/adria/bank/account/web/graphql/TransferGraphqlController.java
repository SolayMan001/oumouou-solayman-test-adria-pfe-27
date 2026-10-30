package com.adria.bank.account.web.graphql;

import java.util.UUID;
import com.adria.bank.account.service.TransferCommand;
import com.adria.bank.account.service.TransferService;
import com.adria.bank.account.web.rest.dto.TransferResponse;
import org.springframework.graphql.data.method.annotation.*;
import org.springframework.stereotype.Controller;

@Controller
public class TransferGraphqlController {
    private final TransferService service;
    public TransferGraphqlController(TransferService service) { this.service = service; }
    @MutationMapping
    public TransferResponse transfer(@Argument TransferCommand input, @Argument UUID idempotencyKey) {
        return TransferResponse.from(service.transfer(idempotencyKey, input));
    }
}
