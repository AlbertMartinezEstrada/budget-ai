package com.budgetai.backend.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

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
            "/budgets", "/goals", "/transfers", "/recurring", "/settings",
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
