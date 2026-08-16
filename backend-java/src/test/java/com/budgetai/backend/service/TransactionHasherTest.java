package com.budgetai.backend.service;

import com.budgetai.backend.model.Transaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La identitat d'un moviment importat.
 *
 * El que ha de complir és que es pugui recalcular: el hash porta @JsonIgnore i
 * no viatja al client, així que en confirmar la importació s'ha de tornar a
 * treure dels camps del moviment i donar el mateix. Mentre sortia de les
 * cadenes crues del CSV això era impossible, arribava sempre a null i confirmar
 * dues vegades el mateix lot el desava repetit.
 */
class TransactionHasherTest {

    private final TransactionHasher hasher = new TransactionHasher();

    private Transaction movement(String concept, String amount) {
        Transaction t = new Transaction();
        t.setDate(LocalDate.of(2026, 2, 15));
        t.setOriginalConcept(concept);
        t.setAmount(new BigDecimal(amount));
        t.setType("EXPENSE");
        return t;
    }

    @Test
    @DisplayName("El mateix moviment dona sempre el mateix hash")
    void sameMovementSameHash() {
        assertThat(hasher.hash(movement("MERCADONA", "45.30")))
                .isEqualTo(hasher.hash(movement("MERCADONA", "45.30")));
    }

    @Test
    @DisplayName("Un import diferent dona un hash diferent")
    void differentAmountDiffers() {
        assertThat(hasher.hash(movement("MERCADONA", "45.30")))
                .isNotEqualTo(hasher.hash(movement("MERCADONA", "45.31")));
    }

    @Test
    @DisplayName("45.3 i 45.30 són el mateix import i el mateix moviment")
    void scaleDoesNotChangeIdentity() {
        // Sense normalitzar l'escala, el mateix moviment llegit del CSV i el
        // mateix tornant del navegador donaven hashos diferents i es desava
        // dues vegades.
        assertThat(hasher.hash(movement("MERCADONA", "45.3")))
                .isEqualTo(hasher.hash(movement("MERCADONA", "45.30")));
    }

    @Test
    @DisplayName("Corregir el tipus no canvia la identitat del moviment")
    void correctingTheTypeKeepsTheIdentity() {
        // El tipus és l'únic d'aquests camps que l'usuari pot corregir a la
        // pantalla de revisió: un abonament que el banc porta en negatiu. Si
        // entrés al hash, corregir-lo faria que tornar a importar el mateix
        // fitxer el dupliqués.
        Transaction corrected = movement("ABONAMENT AMAZON", "19.99");
        corrected.setType("INCOME");

        assertThat(hasher.hash(corrected))
                .isEqualTo(hasher.hash(movement("ABONAMENT AMAZON", "19.99")));
    }

    @Test
    @DisplayName("Un moviment sense concepte ni saldo tampoc peta")
    void missingFieldsAreTolerated() {
        Transaction bare = new Transaction();
        bare.setDate(LocalDate.of(2026, 2, 15));
        bare.setAmount(new BigDecimal("10.00"));

        assertThat(hasher.hash(bare)).isNotBlank();
    }
}
