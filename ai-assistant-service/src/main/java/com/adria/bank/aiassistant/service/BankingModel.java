package com.adria.bank.aiassistant.service;

import org.springframework.ai.tool.ToolCallback;

public interface BankingModel {
    String respond(String message, ToolCallback[] tools);
}
