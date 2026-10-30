package com.adria.bank.aiassistant.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Deliberately not a record: never include the API key in generated toString output. */
@Component
public class ModelSettings {
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    public ModelSettings(@Value("${assistant.model.api-key:}") String apiKey,
            @Value("${assistant.model.base-url:https://api.openai.com}") String baseUrl,
            @Value("${assistant.model.name:gpt-4.1-mini}") String model) {
        this.apiKey = apiKey; this.baseUrl = baseUrl; this.model = model;
    }
    public boolean configured() { return apiKey != null && !apiKey.isBlank(); }
    public String apiKey() { return apiKey; }
    public String baseUrl() { return baseUrl; }
    public String model() { return model; }
}
