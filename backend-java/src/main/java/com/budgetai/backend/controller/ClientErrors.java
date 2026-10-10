package com.budgetai.backend.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

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

    /**
     * Missatge per al navegador; si no és per a l'usuari, l'error sencer va al log.
     *
     * Un error intern porta una referència curta que surt tant al missatge com
     * al log. Sense ella, "el detall és al log" obligava a endevinar quina de
     * les línies d'error era la d'aquell moment.
     *
     * Un error per a l'usuari també s'apunta, en una línia d'avís: el mateix
     * missatge que ha vist i, si en té, la causa tècnica (la de commons-csv,
     * la del parser de dates). Abans no deixava cap rastre, i davant d'un "no
     * m'ho importa" el log no deia res.
     */
    static String messageFor(Exception exception, String context) {
        if (isForTheUser(exception) && exception.getMessage() != null) {
            LOGGER.warn("{}: {}{}", context, exception.getMessage(), causeOf(exception));
            return exception.getMessage();
        }
        String reference = UUID.randomUUID().toString().substring(0, 8);
        LOGGER.error("{}: error inesperat [ref {}]", context, reference, exception);
        return GENERIC + " Referència " + reference + ": busca-la amb «docker compose logs backend».";
    }

    /** " (causa: CSVException: Invalid character…)", o res si no en té. */
    private static String causeOf(Exception exception) {
        Throwable root = exception.getCause();
        if (root == null) return "";
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        return " (causa: " + root.getClass().getSimpleName() + ": " + root.getMessage() + ")";
    }

    static boolean isForTheUser(Exception exception) {
        return exception instanceof IllegalArgumentException || exception instanceof IllegalStateException;
    }
}
