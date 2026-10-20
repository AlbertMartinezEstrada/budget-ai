package com.budgetai.backend.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * L'API sencera per HTTP, passant per la cadena de seguretat de debò.
 *
 * Els tests unitaris de seguretat comproven les peces per separat; aquests
 * comproven que muntades funcionen: que una petició sense cookie no arribi
 * mai a la base de dades.
 */
@AutoConfigureMockMvc
class ApiSecurityIntegrationTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    private static final String[] PROTECTED_ENDPOINTS = {
            "/accounts", "/gastos", "/categories", "/companies",
            "/budgets", "/goals", "/debts", "/transfers", "/recurring", "/fixed-costs", "/settings",
            "/analytics/monthly-trend"
    };

    private Cookie login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new java.util.HashMap<>(java.util.Map.of(
                                        "username", username, "password", password)))))
                .andReturn();

        return result.getResponse().getCookie("budget_session");
    }

    @Test
    @DisplayName("Sense sessió, cap endpoint retorna dades")
    void everythingIsClosedWithoutASession() throws Exception {
        for (String endpoint : PROTECTED_ENDPOINTS) {
            mockMvc.perform(get(endpoint))
                    .andExpect(status().isUnauthorized());
        }

        // Les escriptures també.
        mockMvc.perform(delete("/accounts/1")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/transfers").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    /** Les úniques rutes que han de respondre sense sessió: entrar i sortir. */
    private static final Set<String> PUBLIC_ROUTES = Set.of("/auth/login", "/auth/logout");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping routes;

    @Test
    @DisplayName("Sense sessió, cap ruta del backend respon: totes, també les que s'afegeixin")
    void everyRouteIsClosedWithoutASession() throws Exception {
        // La llista de dalt només en té una dotzena; el backend en té moltes
        // més i en guanya a cada fase. Aquí es pregunten a Spring totes les
        // que existeixen, de manera que una ruta nova queda comprovada sola,
        // i una que algú obrís per error faria fallar el test.
        List<String> open = new ArrayList<>();
        int checked = 0;
        for (RequestMappingInfo route : routes.getHandlerMethods().keySet()) {
            Set<RequestMethod> methods = route.getMethodsCondition().getMethods();
            for (String pattern : route.getPatternValues()) {
                if (PUBLIC_ROUTES.contains(pattern)) continue;
                // Qualsevol valor serveix per als {id}: sense sessió no s'hi arriba.
                String path = pattern.replaceAll("\\{[^}]+}", "1");
                for (RequestMethod requestMethod : methods.isEmpty() ? Set.of(RequestMethod.GET) : methods) {
                    int status = mockMvc.perform(request(HttpMethod.valueOf(requestMethod.name()), path)
                                    .contentType(MediaType.APPLICATION_JSON).content("{}"))
                            .andReturn().getResponse().getStatus();
                    checked++;
                    if (status != 401) open.add(requestMethod + " " + pattern + " -> " + status);
                }
            }
        }
        assertThat(checked).as("rutes comprovades").isGreaterThan(40);
        assertThat(open).as("rutes que responen sense sessió").isEmpty();
    }

    @Test
    @DisplayName("Amb sessió, els endpoints responen")
    void sessionOpensTheApi() throws Exception {
        Cookie session = login("test-user", "test-password");
        assertThat(session).isNotNull();

        for (String endpoint : PROTECTED_ENDPOINTS) {
            mockMvc.perform(get(endpoint).cookie(session))
                    .andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("La cookie de sessió és httpOnly")
    void sessionCookieIsHttpOnly() throws Exception {
        Cookie session = login("test-user", "test-password");

        // Si no fos httpOnly, un XSS futur se l'enduria.
        assertThat(session.isHttpOnly()).isTrue();
        assertThat(session.getValue()).isNotBlank();
    }

    @Test
    @DisplayName("Les credencials incorrectes no obren sessió")
    void wrongCredentialsAreRejected() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"test-user\",\"password\":\"incorrecta\"}"))
                .andExpect(status().isUnauthorized())
                // El missatge no ha de dir quin dels dos camps ha fallat.
                .andExpect(jsonPath("$.error").value("Usuari o contrasenya incorrectes"));

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"un-altre\",\"password\":\"test-password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Usuari o contrasenya incorrectes"));
    }

    /** La contrasenya bona, la que AbstractIntegrationTest dona al backend. */
    @Value("${app.auth.password}")
    private String rightPassword;

    /** Un login des de l'adreça {@code address}, amb una capçalera X-Forwarded-For si no és null. */
    private MvcResult loginFrom(String address, String forwardedFor, String password) throws Exception {
        var request = post("/auth/login")
                .with(mockRequest -> { mockRequest.setRemoteAddr(address); return mockRequest; })
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        java.util.Map.of("username", "test-user", "password", password)));
        if (forwardedFor != null) request.header("X-Forwarded-For", forwardedFor);
        return mockMvc.perform(request).andReturn();
    }

    private int loginStatusFrom(String address, String forwardedFor, String password) throws Exception {
        return loginFrom(address, forwardedFor, password).getResponse().getStatus();
    }

    @Test
    @DisplayName("Els errors des d'una adreça la bloquegen a ella: des d'una altra es pot entrar")
    void failuresLockOnlyTheirAddress() throws Exception {
        // Adreces de documentació (RFC 5737): no les fa servir cap altre test.
        for (int attempt = 0; attempt < 5; attempt++) {
            assertThat(loginStatusFrom("203.0.113.10", null, "incorrecta")).isEqualTo(401);
        }

        // Bloquejada, ni amb la contrasenya bona: si no, es podria seguir provant.
        MvcResult locked = loginFrom("203.0.113.10", null, rightPassword);
        assertThat(locked.getResponse().getStatus()).isEqualTo(429);
        assertThat(locked.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .contains("Massa intents");
        assertThat(loginStatusFrom("203.0.113.20", null, rightPassword)).isEqualTo(200);
    }

    @Test
    @DisplayName("Una capçalera X-Forwarded-For inventada no canvia l'adreça que es compta")
    void forwardedForHeaderIsIgnored() throws Exception {
        // Si es fes cas de la capçalera, cada intent amb una adreça inventada
        // començaria de zero i el bloqueig per adreça no frenaria ningú.
        for (int attempt = 0; attempt < 5; attempt++) {
            assertThat(loginStatusFrom("198.51.100.10", "192.0.2." + attempt, "incorrecta")).isEqualTo(401);
        }

        assertThat(loginStatusFrom("198.51.100.10", "192.0.2.99", rightPassword)).isEqualTo(429);
    }

    @Test
    @DisplayName("Una cookie amb un token inventat no serveix")
    void forgedCookieIsRejected() throws Exception {
        mockMvc.perform(get("/accounts").cookie(new Cookie("budget_session", "token-inventat")))
                .andExpect(status().isUnauthorized());

        Cookie session = login("test-user", "test-password");
        String tampered = session.getValue().substring(0, session.getValue().lastIndexOf('.')) + ".falsa";

        mockMvc.perform(get("/accounts").cookie(new Cookie("budget_session", tampered)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Després de tancar sessió, la cookie caduca")
    void logoutExpiresTheCookie() throws Exception {
        Cookie session = login("test-user", "test-password");

        MvcResult result = mockMvc.perform(post("/auth/logout").cookie(session))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getCookie("budget_session").getMaxAge()).isZero();
    }

    @Test
    @DisplayName("/auth/me identifica la sessió oberta")
    void meReturnsTheUser() throws Exception {
        Cookie session = login("test-user", "test-password");

        mockMvc.perform(get("/auth/me").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("test-user"));

        mockMvc.perform(get("/auth/me"))
                .andExpect(status().isUnauthorized());
    }
}
