package com.budgetai.backend.service;

import com.budgetai.backend.i18n.Message;
import com.budgetai.backend.i18n.Translatable;

/**
 * Una dada que no es pot acceptar: un import negatiu, una data que falta, un
 * extracte que no es llegeix. El gestor d'errors en fa un 400 amb el missatge
 * en l'idioma de la petició.
 *
 * És una IllegalArgumentException perquè la resta del codi ja les tracta com
 * a errors de l'usuari. getMessage() torna el text en català: és el que va al
 * log.
 */
public class InvalidInputException extends IllegalArgumentException implements Translatable {

    private final Message message;

    /** @param namesAndValues nom, valor, nom, valor… dels {noms} del text */
    public InvalidInputException(String key, Object... namesAndValues) {
        this(Message.of(key, namesAndValues));
    }

    public InvalidInputException(Message message) {
        super(message.toString());
        this.message = message;
    }

    /** @param cause l'error tècnic (el del parser de dates), que va al log */
    public InvalidInputException(Message message, Throwable cause) {
        super(message.toString(), cause);
        this.message = message;
    }

    @Override
    public Message message() {
        return message;
    }
}
