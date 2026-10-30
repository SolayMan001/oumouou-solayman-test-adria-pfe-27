package com.adria.bank.aiassistant.web;

import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatResponse(String response, String status, UUID confirmationId, UUID idempotencyKey, JsonNode operation) { }
