package com.budgetai.backend.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Quin missatge d'error arriba al navegador.
 *
 * Les validacions pròpies han d'arribar, perquè expliquen a l'usuari què ha de
 * canviar. La resta porta detalls interns (taules, consultes, classes) i no.
 */
class ClientErrorsTest {

    @Test
    @DisplayName("Una validació pròpia arriba tal qual")
    void validationMessagesReachTheUser() {
        assertThat(ClientErrors.messageFor(new IllegalArgumentException("Data il·legible al CSV: \"32/13\""), "prova"))
                .isEqualTo("Data il·legible al CSV: \"32/13\"");
        assertThat(ClientErrors.messageFor(new IllegalStateException("El compte té moviments"), "prova"))
                .isEqualTo("El compte té moviments");
    }

    @Test
    @DisplayName("Un error intern no ensenya el seu text al navegador")
    void internalErrorsAreHidden() {
        Exception database = new RuntimeException(
                new SQLException("ERROR: duplicate key value violates unique constraint \"transactions_pkey\""));

        assertThat(ClientErrors.messageFor(database, "prova"))
                .isEqualTo(ClientErrors.GENERIC)
                .doesNotContain("transactions_pkey");
        assertThat(ClientErrors.messageFor(new NullPointerException("Cannot invoke \"Account.getId()\""), "prova"))
                .isEqualTo(ClientErrors.GENERIC);
    }

    @Test
    @DisplayName("Una validació sense missatge no deixa la pantalla en blanc")
    void validationWithoutMessageFallsBackToGeneric() {
        assertThat(ClientErrors.messageFor(new IllegalArgumentException(), "prova"))
                .isEqualTo(ClientErrors.GENERIC);
    }
}
