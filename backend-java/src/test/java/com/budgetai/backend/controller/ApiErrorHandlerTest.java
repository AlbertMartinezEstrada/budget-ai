package com.budgetai.backend.controller;

import com.budgetai.backend.service.NotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.sql.SQLException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El format i el missatge de cada error que arriba al navegador.
 *
 * Els casos que necessiten Spring MVC de debò (una ruta desconeguda, un JSON
 * mal format) són a ApiErrorsIntegrationTest.
 */
class ApiErrorHandlerTest {

    private final ApiErrorHandler handler = new ApiErrorHandler();

    private static MockHttpServletRequest request(String method, String path) {
        return new MockHttpServletRequest(method, path);
    }

    @Test
    @DisplayName("Tots els errors porten status, message i path")
    void everyErrorHasTheSameShape() {
        ResponseEntity<Map<String, Object>> response = handler.notFound(
                new NotFoundException("el moviment", 12), request("PUT", "/gastos/12/parts"));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).containsKeys("status", "message", "path");
        assertThat(response.getBody().get("message")).asString().contains("No existeix el moviment 12");
        assertThat(response.getBody().get("path")).isEqualTo("/gastos/12/parts");
    }

    @Test
    @DisplayName("Un error intern no ensenya el seu text, però porta una referència per trobar-lo al log")
    void unexpectedErrorsCarryAReferenceAndHideDetails() {
        ResponseEntity<Map<String, Object>> response = handler.unexpected(
                new RuntimeException(new SQLException("relation \"transaction_parts\" does not exist")),
                request("GET", "/gastos"));

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody().get("message")).asString()
                .doesNotContain("transaction_parts")
                .containsPattern("Referència [0-9a-f]{8}")
                .contains("docker compose logs backend");
    }

    @Test
    @DisplayName("Una validació pròpia és un 400 amb el seu text, i un estat que no ho permet, un 409")
    void ownValidationsReachTheUser() {
        assertThat(handler.invalid(new IllegalArgumentException("La quota ha de ser més gran que zero."),
                request("POST", "/debts")).getStatusCode().value()).isEqualTo(400);
        assertThat(handler.conflict(new IllegalStateException("La categoria té moviments"),
                request("DELETE", "/categories/3")).getBody().get("message"))
                .isEqualTo("La categoria té moviments");
    }
}
