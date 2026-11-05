package com.budgetai.backend.integration;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * L'assistent sense clau de Gemini, com al CI: el backend arrenca, no hi ha
 * cap model de xat, i /ia/prova diu què falta en l'idioma de la pantalla.
 */
@AutoConfigureMockMvc
class AssistantIntegrationTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ObjectProvider<ChatModel> chatModels;

    /** Les credencials que AbstractIntegrationTest dona al backend. */
    @Value("${app.auth.username}")
    private String username;
    @Value("${app.auth.password}")
    private String rightPassword;

    private Cookie logIn() throws Exception {
        return mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", username, "password", rightPassword))))
                .andReturn().getResponse().getCookie("budget_session");
    }

    @Test
    @DisplayName("Sense clau no hi ha model de xat, i el context arrenca igualment")
    void noKeyNoChatModel() {
        assertThat(chatModels.getIfAvailable()).isNull();
    }

    @Test
    @DisplayName("/ia/prova sense clau és un 503 que diu que falta la clau, no un error intern")
    void checkWithoutKeySaysWhatIsMissing() throws Exception {
        mockMvc.perform(get("/ia/prova").cookie(logIn()).header("Accept-Language", "es"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message", containsString("GEMINI_API_KEY")))
                .andExpect(jsonPath("$.message", containsString("asistente")));
    }

    @Test
    @DisplayName("/ia/prova sense sessió no respon")
    void checkNeedsSession() throws Exception {
        mockMvc.perform(get("/ia/prova")).andExpect(status().isUnauthorized());
    }
}
