package com.budgetai.backend.service;

import com.budgetai.backend.model.Transaction;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Identitat d'un moviment importat, per no desar-lo dues vegades.
 *
 * Es calcula a partir dels camps ja normalitzats de l'entitat —data, concepte
 * original, import i saldo— i no de les cadenes crues del CSV, com es feia
 * abans. El motiu és que el hash s'ha de poder tornar a calcular en confirmar
 * la importació, i allà les cadenes crues ja no existeixen: només hi ha la
 * llista de moviments que torna el navegador.
 *
 * Aquell era justament el problema. El hash porta @JsonIgnore, així que no
 * viatja al client ni torna, i a confirm-upload arribava sempre a null: la
 * comprovació de duplicats no descartava res i confirmar dues vegades el mateix
 * lot desava els moviments repetits. La columna és única, però PostgreSQL
 * permet tants nulls com vulguis, així que tampoc hi havia xarxa de seguretat.
 *
 * NO hi entra el tipus. És l'únic camp d'aquests que l'usuari pot corregir a
 * la pantalla de revisió —un abonament que el banc porta en negatiu—, i si hi
 * entrés, corregir-lo canviaria la identitat del moviment i tornar a importar
 * el mateix fitxer el duplicaria.
 *
 * Amb el mateix concepte, el mateix dia i el mateix import, un càrrec i el seu
 * abonament col·lideixen si l'extracte no porta saldo. És prou rar i el que en
 * surt és que un dels dos es descarti avisant, no una dada incorrecta.
 */
@Service
public class TransactionHasher {

    public String hash(Transaction transaction) {
        String raw = String.join("|",
                text(transaction.getDate()),
                text(transaction.getOriginalConcept()),
                amount(transaction.getAmount()),
                amount(transaction.getBalance()));

        return sha256(raw);
    }

    private String text(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    /**
     * L'escala ha de ser sempre la mateixa: 45.3 i 45.30 són el mateix import
     * però donen hashos diferents, i el moviment es desaria dos cops.
     */
    private String amount(BigDecimal value) {
        return value == null ? "" : value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Base64.getEncoder().encodeToString(
                    digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 sempre hi és; si mai faltés, val més una identitat
            // llegible que no pas cap.
            return input;
        }
    }
}
