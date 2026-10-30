package com.adria.bank.account.web.rest;

import java.net.URI;
import java.util.UUID;
import com.adria.bank.account.service.TransferCommand;
import com.adria.bank.account.service.TransferService;
import com.adria.bank.account.web.rest.dto.TransferResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/transfers")
public class TransferRestController {
    private final TransferService service;
    public TransferRestController(TransferService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<TransferResponse> transfer(@RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody TransferCommand input) {
        TransferResponse response = TransferResponse.from(service.transfer(key, input));
        return ResponseEntity.created(URI.create("/api/transfers/" + response.id())).body(response);
    }
    @GetMapping("/{id}")
    public TransferResponse get(@PathVariable UUID id) { return TransferResponse.from(service.get(id)); }
}
