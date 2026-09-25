package com.budgetai.backend.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Quin missatge d'error pot arribar al navegador.
 *
 * Les validacions pròpies es llancen com a IllegalArgumentException o
 * IllegalStateException amb un text pensat per a l'usuari ("Data il·legible al
 * CSV", "Un cost fix ha d'anar a una subcategoria fixa"), i aquest sí que ha
 * d'arribar: sense ell, la pantalla només diu "Error 500".
 *
 * Qualsevol altra excepció porta el text intern de Java, Hibernate o el driver
 * de PostgreSQL: noms de taules, consultes, classes. Això es queda al log del
 * backend i al navegador li arriba un missatge genèric. Abans uns quants
 * catch (Exception) el retornaven tal qual.
 */
final class ClientErrors {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientErrors.class);

    static final String GENERIC = "Error intern. El detall és al log del backend.";

    private ClientErrors() {
    }

    /** Missatge per al navegador; si no és per a l'usuari, l'error sencer va al log. */
    static String messageFor(Exception exception, String context) {
        if (isForTheUser(exception) && exception.getMessage() != null) {
            return exception.getMessage();
        }
        LOGGER.error("{}: error inesperat", context, exception);
        return GENERIC;
    }

    static boolean isForTheUser(Exception exception) {
        return exception instanceof IllegalArgumentException || exception instanceof IllegalStateException;
    }
}
