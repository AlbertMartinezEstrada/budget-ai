package com.budgetai.backend.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sense clau de Gemini, el model de xat s'apaga i el backend arrenca: amb
 * Spring AI, la seva autoconfiguració llançava una excepció i no arrencava
 * res.
 */
class ChatModelWithoutKeyTest {

    private static String chatModelAfter(MockEnvironment environment) {
        new ChatModelWithoutKey().postProcessEnvironment(environment, new SpringApplication());
        return environment.getProperty(ChatModelWithoutKey.CHAT_MODEL);
    }

    @Test
    @DisplayName("Sense clau, o amb la clau buida de .env, el model de xat queda apagat")
    void noKeyTurnsTheChatModelOff() {
        assertThat(chatModelAfter(new MockEnvironment())).isEqualTo("none");
        assertThat(chatModelAfter(new MockEnvironment().withProperty(ChatModelWithoutKey.API_KEY, "  ")))
                .isEqualTo("none");
    }

    @Test
    @DisplayName("Amb clau no es toca res: l'autoconfiguració de Gemini fa la seva feina")
    void keyLeavesItAlone() {
        // Una clau qualsevol, generada: un literal aquí semblaria una clau de debò.
        String anyKey = UUID.randomUUID().toString();
        assertThat(chatModelAfter(new MockEnvironment().withProperty(ChatModelWithoutKey.API_KEY, anyKey)))
                .isNull();
    }

    @Test
    @DisplayName("Un spring.ai.model.chat explícit mana, i amb Vertex AI la clau no fa falta")
    void explicitChoiceAndVertexAreRespected() {
        assertThat(chatModelAfter(new MockEnvironment().withProperty(ChatModelWithoutKey.CHAT_MODEL, "google-genai")))
                .isEqualTo("google-genai");
        assertThat(chatModelAfter(new MockEnvironment().withProperty(ChatModelWithoutKey.VERTEX_AI, "true")))
                .isNull();
    }
}
