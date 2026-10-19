package com.budgetai.backend.service;

import com.budgetai.backend.i18n.Message;
import com.budgetai.backend.i18n.Translatable;

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
public class NotFoundException extends RuntimeException implements Translatable {

    private final Message message;

    /**
     * @param key una clau not-found.* per cosa: la frase sencera porta l'article
     *            («No existeix el compte 12», «No existe la cuenta 12»), que
     *            canvia d'un idioma a l'altre
     */
    public NotFoundException(String key, Object id) {
        super(Message.of(key, "id", id).toString());
        this.message = Message.of(key, "id", id);
    }

    @Override
    public Message message() {
        return message;
    }
}
