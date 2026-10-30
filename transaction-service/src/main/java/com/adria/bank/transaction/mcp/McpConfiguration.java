package com.adria.bank.transaction.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpConfiguration {
    @Bean
    public ToolCallbackProvider transactionToolCallbacks(TransactionTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }
}
