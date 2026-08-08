package com.budgetai.backend.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prorrateig d'una despesa fixa a import mensual equivalent.
 *
 * És la xifra que fa servir la vista de cost de vida: què costa aquesta
 * despesa cada mes, independentment de quan caigui el càrrec.
 */
class RecurringProrationTest {

    private RecurringTransaction recurring(String amount, String frequency) {
        RecurringTransaction rt = new RecurringTransaction();
        rt.setAmount(amount == null ? null : new BigDecimal(amount));
        rt.setFrequency(frequency);
        rt.setType("EXPENSE");
        return rt;
    }

    @Test
    @DisplayName("Una despesa anual es reparteix entre dotze mesos")
    void yearly() {
        // El cas del plantejament: assegurança de 600 € l'any són 50 € al mes.
        assertThat(recurring("600.00", "ANUAL").getMonthlyAmount())
                .isEqualByComparingTo("50.00");
    }

    @Test
    @DisplayName("Una despesa mensual val el seu propi import")
    void monthly() {
        assertThat(recurring("49.90", "MENSUAL").getMonthlyAmount())
                .isEqualByComparingTo("49.90");
    }

    @Test
    @DisplayName("Una despesa trimestral es divideix entre tres")
    void quarterly() {
        assertThat(recurring("120.00", "TRIMESTRAL").getMonthlyAmount())
                .isEqualByComparingTo("40.00");
    }

    @Test
    @DisplayName("Les freqüències curtes fan servir l'any real, no mesos de 30 dies")
    void weeklyAndDailyUseTheRealYear() {
        // 120 × 52 / 12. Amb "4 setmanes per mes" sortirien 480 i es perdrien
        // quatre setmanes l'any.
        assertThat(recurring("120.00", "SETMANAL").getMonthlyAmount())
                .isEqualByComparingTo("520.00");

        // 10 × 365 / 12. Amb mesos de 30 dies sortirien 300.
        assertThat(recurring("10.00", "DIARIA").getMonthlyAmount())
                .isEqualByComparingTo("304.17");
    }

    @Test
    @DisplayName("El resultat s'arrodoneix a dos decimals")
    void roundsToTwoDecimals() {
        // 100 / 12 = 8,333...
        BigDecimal monthly = recurring("100.00", "ANUAL").getMonthlyAmount();

        assertThat(monthly).isEqualByComparingTo("8.33");
        assertThat(monthly.scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("La freqüència no distingeix majúscules")
    void frequencyIsCaseInsensitive() {
        assertThat(recurring("600.00", "anual").getMonthlyAmount())
                .isEqualByComparingTo("50.00");
    }

    @Test
    @DisplayName("Sense import, sense freqüència o amb una de desconeguda, el prorrateig és zero")
    void missingDataYieldsZero() {
        // Comptar una freqüència desconeguda com a mensual inventaria una
        // xifra i falsejaria el cost de vida.
        assertThat(recurring("600.00", "CADA_LLUNA_PLENA").getMonthlyAmount())
                .isEqualByComparingTo("0");
        assertThat(recurring(null, "ANUAL").getMonthlyAmount()).isEqualByComparingTo("0");
        assertThat(recurring("600.00", null).getMonthlyAmount()).isEqualByComparingTo("0");
    }
}
