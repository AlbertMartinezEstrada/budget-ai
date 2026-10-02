package com.budgetai.backend.service;

import com.budgetai.backend.model.Debt;
import com.budgetai.backend.model.Debt.Installment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El calendari de retorn d'un deute i com es compara amb el que s'ha retornat.
 *
 * L'escenari de base: 1.000 € a tornar a 300 € al mes a partir del 31 de
 * gener. Té les dues trampes del calendari: l'última quota no és sencera, i el
 * dia 31 no existeix a tots els mesos.
 */
class RepaymentScheduleTest {

    private static Debt installments(String amount, String installment, String frequency, LocalDate first) {
        Debt debt = new Debt();
        debt.setDirection(Debt.I_OWE);
        debt.setAmount(new BigDecimal(amount));
        debt.setDate(first.minusDays(10));
        debt.setRepaymentPlan(Debt.PLAN_INSTALLMENTS);
        debt.setInstallment(new BigDecimal(installment));
        debt.setFrequency(frequency);
        debt.setFirstPaymentDate(first);
        return debt;
    }

    private static List<BigDecimal> amounts(List<Installment> payments) {
        return payments.stream().map(Installment::amount).toList();
    }

    private static List<LocalDate> dates(List<Installment> payments) {
        return payments.stream().map(Installment::date).toList();
    }

    @Test
    @DisplayName("A quotes, l'última és el que falta i no una quota sencera")
    void lastInstallmentIsTheRemainder() {
        List<Installment> payments = RepaymentSchedule.of(
                installments("1000.00", "300.00", "MENSUAL", LocalDate.of(2026, 1, 31)));

        assertThat(amounts(payments)).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("300"), new BigDecimal("300"),
                        new BigDecimal("300"), new BigDecimal("100"));
    }

    @Test
    @DisplayName("Els mesos es compten des del primer pagament: el 31 torna després de febrer")
    void monthlyDatesDoNotDrift() {
        List<Installment> payments = RepaymentSchedule.of(
                installments("1000.00", "300.00", "MENSUAL", LocalDate.of(2026, 1, 31)));

        // Sumant un mes a l'anterior, del 28 de febrer se saltaria al 28 de
        // març i ja no tornaria mai al 31.
        assertThat(dates(payments)).containsExactly(
                LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 28),
                LocalDate.of(2026, 3, 31), LocalDate.of(2026, 4, 30));
    }

    @Test
    @DisplayName("Setmanal i trimestral avancen el que diuen")
    void weeklyAndQuarterly() {
        assertThat(dates(RepaymentSchedule.of(
                installments("300.00", "100.00", "SETMANAL", LocalDate.of(2026, 3, 2)))))
                .containsExactly(LocalDate.of(2026, 3, 2), LocalDate.of(2026, 3, 9), LocalDate.of(2026, 3, 16));

        assertThat(dates(RepaymentSchedule.of(
                installments("300.00", "100.00", "TRIMESTRAL", LocalDate.of(2026, 1, 15)))))
                .containsExactly(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 4, 15), LocalDate.of(2026, 7, 15));
    }

    @Test
    @DisplayName("Tot de cop és un sol pagament per l'import sencer")
    void singlePayment() {
        Debt debt = new Debt();
        debt.setAmount(new BigDecimal("450.00"));
        debt.setRepaymentPlan(Debt.PLAN_SINGLE);
        debt.setFirstPaymentDate(LocalDate.of(2026, 12, 1));

        assertThat(RepaymentSchedule.of(debt))
                .containsExactly(new Installment(LocalDate.of(2026, 12, 1), new BigDecimal("450.00"), null));
    }

    @Test
    @DisplayName("Sense calendari no hi ha pagaments previstos")
    void freePlanHasNoSchedule() {
        Debt debt = new Debt();
        debt.setAmount(new BigDecimal("450.00"));
        debt.setRepaymentPlan(Debt.PLAN_FREE);

        assertThat(RepaymentSchedule.of(debt)).isEmpty();
    }

    @Test
    @DisplayName("El retornat cobreix els pagaments per ordre; el que ja ha vençut sense cobrir va endarrerit")
    void statusFollowsWhatWasRepaid() {
        List<Installment> payments = RepaymentSchedule.of(
                installments("1000.00", "300.00", "MENSUAL", LocalDate.of(2026, 1, 31)));

        // 400 retornats a mitjans de març: gener pagat, febrer a mitges i ja
        // vençut, i març i abril encara per venir.
        List<Installment> described = RepaymentSchedule.withStatus(
                payments, new BigDecimal("400.00"), LocalDate.of(2026, 3, 15));

        assertThat(described).extracting(Installment::status)
                .containsExactly("PAGAT", "ENDARRERIT", "PENDENT", "PENDENT");
        assertThat(RepaymentSchedule.overdue(payments, new BigDecimal("400.00"), LocalDate.of(2026, 3, 15)))
                .isEqualByComparingTo("200.00");
    }

    @Test
    @DisplayName("Un pagament que encara no ha vençut i està a mitges és parcial")
    void partialBeforeDueDate() {
        List<Installment> payments = RepaymentSchedule.of(
                installments("1000.00", "300.00", "MENSUAL", LocalDate.of(2026, 1, 31)));

        List<Installment> described = RepaymentSchedule.withStatus(
                payments, new BigDecimal("450.00"), LocalDate.of(2026, 2, 10));

        assertThat(described).extracting(Installment::status)
                .containsExactly("PAGAT", "PARCIAL", "PENDENT", "PENDENT");
        assertThat(RepaymentSchedule.overdue(payments, new BigDecimal("450.00"), LocalDate.of(2026, 2, 10)))
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("El pròxim pagament diu el que en falta, no la quota sencera")
    void nextPaymentIsWhatIsMissing() {
        List<Installment> payments = RepaymentSchedule.of(
                installments("1000.00", "300.00", "MENSUAL", LocalDate.of(2026, 1, 31)));

        Installment next = RepaymentSchedule.next(payments, new BigDecimal("450.00"));

        assertThat(next.date()).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(next.amount()).isEqualByComparingTo("150.00");
        assertThat(RepaymentSchedule.next(payments, new BigDecimal("1000.00"))).isNull();
    }

    @Test
    @DisplayName("Els pagaments d'un mes se sumen amb els dos extrems inclosos")
    void dueBetweenIncludesBothEnds() {
        List<Installment> payments = RepaymentSchedule.of(
                installments("400.00", "100.00", "SETMANAL", LocalDate.of(2026, 3, 1)));

        // 1, 8, 15 i 22 de març: tots quatre cauen dins del mes.
        assertThat(RepaymentSchedule.dueBetween(payments, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31)))
                .isEqualByComparingTo("400.00");
        assertThat(RepaymentSchedule.dueBetween(payments, LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30)))
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("Una quota ridícula es rebutja en comptes de generar milers de dates")
    void absurdInstallmentIsRejected() {
        assertThatThrownBy(() -> RepaymentSchedule.requireReasonableCount(
                new BigDecimal("10000.00"), new BigDecimal("1.00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("10000 pagaments");
    }
}
