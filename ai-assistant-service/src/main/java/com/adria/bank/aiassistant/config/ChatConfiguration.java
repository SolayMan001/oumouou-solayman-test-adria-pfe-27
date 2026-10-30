package com.adria.bank.aiassistant.config;

import java.time.Duration;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;

@Configuration
public class ChatConfiguration {
    @Bean
    @Lazy
    public ChatClient bankingChatClient(ModelSettings settings) {
        var requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(Duration.ofSeconds(5));
        requests.setReadTimeout(Duration.ofSeconds(30));
        var api = OpenAiApi.builder().apiKey(settings.apiKey()).baseUrl(settings.baseUrl())
                .restClientBuilder(RestClient.builder().requestFactory(requests)).build();
        var model = OpenAiChatModel.builder().openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder().model(settings.model()).temperature(0.0)
                        .parallelToolCalls(false).maxTokens(800).build())
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).build()).build();
        return ChatClient.create(model);
    }
}
