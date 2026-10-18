package com.budgetai.backend.i18n;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Un text per a l'usuari que encara no té idioma: la clau del fitxer
 * i18n/messages_xx.properties i els valors dels seus {noms}.
 *
 * Es tradueix quan ja se sap a qui es respon, amb l'idioma de la petició
 * (Messages.forUser). Un valor que és un altre Message es tradueix en el
 * mateix idioma: «Línia 4 de l'extracte (Revolut): falta la data» és una frase
 * amb una altra a dins.
 *
 * @param values en l'ordre en què s'han donat; un valor pot ser null
 */
public record Message(String key, Map<String, Object> values) {

    public Message {
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    /**
     * @param namesAndValues nom, valor, nom, valor…:
     *        Message.of("months.reversed", "from", from, "to", to)
     */
    public static Message of(String key, Object... namesAndValues) {
        if (namesAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("Cada valor de «" + key + "» ha de portar el seu nom");
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < namesAndValues.length; index += 2) {
            values.put(String.valueOf(namesAndValues[index]), namesAndValues[index + 1]);
        }
        return new Message(key, values);
    }

    /** El text en un dels idiomes (ca, es, pt, en). */
    public String in(String language) {
        return Messages.text(language, this);
    }

    /** En l'idioma de referència: és el que va al log i el que retorna getMessage(). */
    @Override
    public String toString() {
        return in(Messages.REFERENCE);
    }
}
