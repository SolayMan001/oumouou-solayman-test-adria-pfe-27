package com.adria.bank.aiassistant.service;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import com.fasterxml.jackson.databind.JsonNode;

/** Prevent a model from filling missing UUIDs or money amounts with invented values. */
final class ArgumentGrounding {
    private static final Pattern UUID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern NUMBER = Pattern.compile("(?<![\\p{L}\\d])[-+]?\\d+(?:[.,]\\d+)?(?![\\p{L}\\d])");
    private final Set<String> ids = new HashSet<>();
    private final Set<BigDecimal> amounts = new HashSet<>();

    ArgumentGrounding(String message) {
        var uuid = UUID.matcher(message);
        while (uuid.find()) { ids.add(uuid.group().toLowerCase(Locale.ROOT)); }
        var number = NUMBER.matcher(UUID.matcher(message).replaceAll(" "));
        while (number.find()) {
            try { amounts.add(new BigDecimal(number.group().replace(',', '.')).stripTrailingZeros()); }
            catch (NumberFormatException ignored) { }
        }
    }
    void remember(String verifiedResult) {
        var uuid = UUID.matcher(verifiedResult);
        while (uuid.find()) { ids.add(uuid.group().toLowerCase(Locale.ROOT)); }
    }
    void verify(JsonNode input) {
        input.fields().forEachRemaining(field -> {
            String name = field.getKey();
            JsonNode value = field.getValue();
            if (name.equals("idempotencyKey")) { return; }
            if (value.isObject()) { verify(value); return; }
            if (name.equals("id") || name.endsWith("Id")) {
                if (!ids.contains(value.asText().toLowerCase(Locale.ROOT))) {
                    throw new AssistantException(400, "MISSING_ACCOUNT_INFORMATION", "Provide the exact account or transaction UUID, or ask to list existing records first. No identifier was invented.");
                }
            }
            if (name.equals("amount") || name.equals("balance")) {
                try {
                    if (amounts.contains(new BigDecimal(value.asText()).stripTrailingZeros())) { return; }
                } catch (NumberFormatException ignored) { }
                throw new AssistantException(400, "MISSING_AMOUNT", "Provide the monetary amount explicitly. No amount was guessed.");
            }
        });
    }
}
