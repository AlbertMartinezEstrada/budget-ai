package com.budgetai.backend.service;

import com.budgetai.backend.i18n.Message;
import com.budgetai.backend.i18n.Translatable;

/**
 * L'estat de les dades no ho permet: esborrar un compte amb moviments, una
 * categoria amb subcategories. El gestor d'errors en fa un 409 amb el
 * missatge en l'idioma de la petició.
 */
public class ConflictException extends IllegalStateException implements Translatable {

    private final Message message;

    /** @param namesAndValues nom, valor, nom, valor… dels {noms} del text */
    public ConflictException(String key, Object... namesAndValues) {
        super(Message.of(key, namesAndValues).toString());
        this.message = Message.of(key, namesAndValues);
    }

    @Override
    public Message message() {
        return message;
    }
}
