package com.budgetai.backend.service;

/**
 * El que es demana no existeix: un moviment, un compte, un deute...
 *
 * Porta un missatge per a l'usuari que diu **què** no s'ha trobat, i el
 * gestor d'errors global en fa un 404 amb aquest text. Abans els controladors
 * responien un 404 buit, i la pantalla deia "Not Found" o "Error 404" sense
 * cap pista de què havia fallat.
 *
 * El cas habitual és haver-ho esborrat des d'una altra pestanya, i per això el
 * missatge suggereix recarregar.
 */
public class NotFoundException extends RuntimeException {

    /**
     * @param what amb article: "el moviment", "el compte", "l'objectiu"
     */
    public NotFoundException(String what, Object id) {
        super("No existeix " + what + " " + id + ". Potser s'ha esborrat des d'una altra pestanya: "
                + "recarrega la pàgina.");
    }
}
