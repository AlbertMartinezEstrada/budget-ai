package com.budgetai.backend.controller;

import com.budgetai.backend.i18n.Messages;
import com.budgetai.backend.service.AssistantService;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * L'assistent. Com la resta de l'API, només amb la sessió iniciada
 * (SecurityConfig): no cal fer res aquí.
 */
@RestController
public class AssistantController {

    private final AssistantService assistant;

    public AssistantController(AssistantService assistant) {
        this.assistant = assistant;
    }

    public record CheckResponse(@JsonProperty("resposta") String answer) {}

    /**
     * Comprova que Gemini respon. Sense clau, un 503 que diu què falta en
     * lloc d'un error intern: no és cap fallada, és que no està configurat.
     */
    @GetMapping("/ia/prova")
    public CheckResponse check() {
        return assistant.checkConnection()
                .map(CheckResponse::new)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        Messages.get("assistant.not-configured")));
    }
}
