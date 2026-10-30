package com.adria.bank.aiassistant.web;

import java.util.Map;
import com.adria.bank.aiassistant.config.ModelSettings;
import com.adria.bank.aiassistant.mcp.BankingTools;
import com.adria.bank.aiassistant.service.AssistantService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/assistant")
public class AssistantController {
    private final AssistantService service;
    private final BankingTools tools;
    private final ModelSettings settings;
    public AssistantController(AssistantService service, BankingTools tools, ModelSettings settings) {
        this.service = service; this.tools = tools; this.settings = settings;
    }
    @PostMapping("/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) { return service.chat(request); }
    @GetMapping("/status")
    public Map<String, Object> status() { return Map.of("modelConfigured", settings.configured(), "mcp", tools.status()); }
}
