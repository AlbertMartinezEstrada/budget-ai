package com.budgetai.backend.i18n;

/**
 * Un error amb un missatge per a l'usuari que es pot dir en qualsevol idioma.
 * El gestor d'errors el tradueix a l'idioma de la petició.
 */
public interface Translatable {

    Message message();
}
