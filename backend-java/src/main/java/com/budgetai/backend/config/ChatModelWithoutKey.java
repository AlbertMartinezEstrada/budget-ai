package com.budgetai.backend.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * Sense clau de Gemini, el model de xat de Spring AI queda apagat i el backend
 * arrenca igual.
 *
 * L'autoconfiguració de Google GenAI s'activa sempre que
 * spring.ai.model.chat no digui el contrari, i sense clau (ni projecte de
 * Vertex AI) llança una excepció: no arrencava res, ni l'API, quan fins ara
 * sense clau només es quedava sense classificar. Aquí, abans de crear cap
 * bean, si no hi ha clau es posa spring.ai.model.chat=none.
 *
 * Només si ningú l'ha posat: un spring.ai.model.chat explícit (a l'entorn o
 * a les propietats) mana. Va a la font de menys prioritat per això mateix.
 *
 * Es registra a META-INF/spring.factories: ha de passar abans que existeixi
 * el context, i un @Configuration arribaria tard.
 */
public class ChatModelWithoutKey implements EnvironmentPostProcessor {

    static final String CHAT_MODEL = "spring.ai.model.chat";
    static final String API_KEY = "spring.ai.google.genai.api-key";
    static final String VERTEX_AI = "spring.ai.google.genai.vertex-ai";
    static final String NO_MODEL = "none";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.containsProperty(CHAT_MODEL)) return;
        // Amb Vertex AI la clau no fa falta: hi van el projecte i la regió.
        if (environment.getProperty(VERTEX_AI, Boolean.class, false)) return;

        String apiKey = environment.getProperty(API_KEY, "");
        if (apiKey.isBlank()) {
            environment.getPropertySources()
                    .addLast(new MapPropertySource("chatModelWithoutKey", Map.of(CHAT_MODEL, NO_MODEL)));
        }
    }
}
