package com.adria.bank.aiassistant.mcp;

import java.util.Map;
import org.springframework.ai.tool.ToolCallback;

public interface BankingTools {
    Map<String, ToolCallback> callbacks();
    String call(String name, String arguments);
    Map<String, String> status();
}
