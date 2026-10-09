package com.budgetai.backend.service;

import com.budgetai.backend.model.Debt;
import com.budgetai.backend.model.Debt.Installment;
import com.budgetai.backend.service.RepaymentSchedule.Payment;
import com.budgetai.backend.service.RepaymentSchedule.Plan;
import com.budgetai.backend.service.RepaymentSchedule.Removal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El calendari de retorn d'un deute i què paga cada pagament.
 *
 * Dos escenaris de base. Un deute de 1.000 € a 300 € al mes a partir del 31 de
 * gener, amb les dues trampes de les dates: l'última quota no és sencera, i el
 * dia 31 no existeix a tots els mesos. I la Steam Deck: 779 € a 97,38 € al mes
 * des del 8 de setembre, de la qual s'han pagat 97,35 € el 6 d'octubre.
 */
class RepaymentScheduleTest {

    private static final LocalDate FAR_AWAY = LocalDate.of(2020, 1, 1);
    private static final LocalDate SEPTEMBER_8 = LocalDate.of(2026, 9, 8);
    private static final LocalDate OCTOBER_8 = LocalDate.of(2026, 10, 8);

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

    private static Debt steamDeck() {
        return installments("779.00", "97.38", "MENSUAL", SEPTEMBER_8);
    }

    private static Payment paid(String amount, LocalDate date) {
        return new Payment(date, new BigDecimal(amount), null);
    }

    private static Payment paidFor(String amount, LocalDate date, LocalDate receipt) {
        return new Payment(date, new BigDecimal(amount), receipt);
    }

    private static Plan plan(Debt debt, List<Payment> payments, LocalDate today) {
        return RepaymentSchedule.plan(debt, List.of(), payments, today);
    }

    private static List<BigDecimal> amounts(List<Installment> receipts) {
        return receipts.stream().map(Installment::amount).toList();
    }

    private static List<LocalDate> dates(List<Installment> receipts) {
        return receipts.stream().map(Installment::date).toList();
    }

    private static List<String> statuses(List<Installment> receipts) {
        return receipts.stream().map(Installment::status).toList();
    }

    // ============ EL CALENDARI ============

    @Test
    @DisplayName("A quotes, l'última és el que falta i no una quota sencera")
    void lastInstallmentIsTheRemainder() {
        Plan plan = plan(installments("1000.00", "300.00", "MENSUAL", LocalDate.of(2026, 1, 31)), List.of(), FAR_AWAY);

        assertThat(amounts(plan.receipts())).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("300"), new BigDecimal("300"),
                        new BigDecimal("300"), new BigDecimal("100"));
    }

    @Test
    @DisplayName("Els mesos es compten des del primer pagament: el 31 torna després de febrer")
    void monthlyDatesDoNotDrift() {
        Plan plan = plan(installments("1000.00", "300.00", "MENSUAL", LocalDate.of(2026, 1, 31)), List.of(), FAR_AWAY);

        // Sumant un mes a l'anterior, del 28 de febrer se saltaria al 28 de
        // març i ja no tornaria mai al 31.
        assertThat(dates(plan.receipts())).containsExactly(
                LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 28),
                LocalDate.of(2026, 3, 31), LocalDate.of(2026, 4, 30));
    }

    @Test
    @DisplayName("Setmanal i trimestral avancen el que diuen")
    void weeklyAndQuarterly() {
        assertThat(dates(plan(installments("300.00", "100.00", "SETMANAL", LocalDate.of(2026, 3, 2)),
                List.of(), FAR_AWAY).receipts()))
                .containsExactly(LocalDate.of(2026, 3, 2), LocalDate.of(2026, 3, 9), LocalDate.of(2026, 3, 16));

        assertThat(dates(plan(installments("300.00", "100.00", "TRIMESTRAL", LocalDate.of(2026, 1, 15)),
                List.of(), FAR_AWAY).receipts()))
                .containsExactly(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 4, 15), LocalDate.of(2026, 7, 15));
    }

    @Test
    @DisplayName("Tot de cop és un sol rebut per l'import sencer")
    void singlePayment() {
        Debt debt = new Debt();
        debt.setDirection(Debt.I_OWE);
        debt.setAmount(new BigDecimal("450.00"));
        debt.setRepaymentPlan(Debt.PLAN_SINGLE);
        debt.setFirstPaymentDate(LocalDate.of(2026, 12, 1));

        Plan plan = plan(debt, List.of(), FAR_AWAY);

        assertThat(plan.receipts()).hasSize(1);
        assertThat(plan.receipts().get(0).date()).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(plan.receipts().get(0).amount()).isEqualByComparingTo("450.00");
        assertThat(plan.receipts().get(0).removable()).isFalse();
    }

    @Test
    @DisplayName("Sense calendari no hi ha rebuts")
    void freePlanHasNoSchedule() {
        Debt debt = new Debt();
        debt.setAmount(new BigDecimal("450.00"));
        debt.setRepaymentPlan(Debt.PLAN_FREE);

        Plan plan = plan(debt, List.of(paid("100.00", FAR_AWAY)), FAR_AWAY);

        assertThat(plan.receipts()).isEmpty();
        assertThat(plan.next()).isNull();
        assertThat(plan.receiptOfPayment()).containsExactly((LocalDate) null);
    }

    // ============ PER MESOS ============

    @Test
    @DisplayName("El rebut del mes en curs toca, no va endarrerit, fins que el mes s'acaba")
    void receiptIsOverdueOnlyWhenItsMonthEnds() {
        Plan lastDayOfOctober = plan(steamDeck(), List.of(), LocalDate.of(2026, 10, 31));
        Plan firstOfNovember = plan(steamDeck(), List.of(), LocalDate.of(2026, 11, 1));

        // El 31 d'octubre, el rebut del dia 8 encara és el d'aquest mes.
        assertThat(statuses(lastDayOfOctober.receipts()).subList(0, 3))
                .containsExactly("ENDARRERIT", "TOCA", "PENDENT");
        assertThat(lastDayOfOctober.overdue()).isEqualByComparingTo("97.38");
        // L'1 de novembre, ja no.
        assertThat(statuses(firstOfNovember.receipts()).subList(0, 3))
                .containsExactly("ENDARRERIT", "ENDARRERIT", "TOCA");
        assertThat(firstOfNovember.overdue()).isEqualByComparingTo("194.76");
    }

    @Test
    @DisplayName("Steam Deck: el pagament d'octubre paga el que toca, i uns cèntims de menys no el deixen a mitges")
    void steamDeckPaymentCoversTheReceiptDespiteTheCents() {
        Plan plan = plan(steamDeck(), List.of(paid("97.35", LocalDate.of(2026, 10, 6))), LocalDate.of(2026, 10, 9));

        // El que toca és el primer sense pagar: el de setembre. Els 0,03 € que
        // hi falten són arrodoniment i van a l'últim rebut, no deixen el de
        // setembre endarrerit.
        assertThat(statuses(plan.receipts())).containsExactly(
                "PAGAT", "TOCA", "PENDENT", "PENDENT", "PENDENT", "PENDENT", "PENDENT", "PENDENT");
        assertThat(plan.overdue()).isEqualByComparingTo("0");
        assertThat(plan.receipts().get(7).amount()).isEqualByComparingTo("97.37");
        assertThat(plan.receipts().get(7).date()).isEqualTo(LocalDate.of(2027, 4, 8));
        assertThat(plan.receiptOfPayment()).containsExactly(SEPTEMBER_8);
        assertThat(plan.chosen()).containsExactly(false);

        assertThat(plan.next().date()).isEqualTo(OCTOBER_8);
        assertThat(plan.next().amount()).isEqualByComparingTo("97.38");
        assertThat(plan.next().status()).isEqualTo("TOCA");
    }

    @Test
    @DisplayName("Un pagament pot triar el seu rebut, i el que es deixa enrere va endarrerit")
    void aPaymentCanChooseItsReceipt() {
        Plan plan = plan(steamDeck(),
                List.of(paidFor("97.35", LocalDate.of(2026, 10, 6), OCTOBER_8)), LocalDate.of(2026, 10, 9));

        assertThat(statuses(plan.receipts()).subList(0, 3)).containsExactly("ENDARRERIT", "PAGAT", "PENDENT");
        assertThat(plan.overdue()).isEqualByComparingTo("97.38");
        assertThat(plan.receiptOfPayment()).containsExactly(OCTOBER_8);
        assertThat(plan.chosen()).containsExactly(true);
        // El rebut triat ja no es pot treure: el pagament no tindria on anar.
        assertThat(plan.receipts().get(0).removable()).isTrue();
        assertThat(plan.receipts().get(1).removable()).isFalse();
    }

    @Test
    @DisplayName("El rebut triat es troba pel mes, encara que el dia no sigui exacte")
    void chosenReceiptMatchesByPeriod() {
        Plan plan = plan(steamDeck(),
                List.of(paidFor("97.38", LocalDate.of(2026, 10, 6), LocalDate.of(2026, 10, 31))),
                LocalDate.of(2026, 10, 9));

        assertThat(plan.receipts().get(1).status()).isEqualTo("PAGAT");
        assertThat(plan.receiptOfPayment()).containsExactly(OCTOBER_8);
    }

    @Test
    @DisplayName("Els pagaments sense rebut van al primer que queda lliure després dels triats")
    void unassignedPaymentsFillTheFirstFreeReceipt() {
        // El d'octubre l'ha triat un pagament posterior; el del 2 d'octubre,
        // sense rebut, no se l'ha de quedar: va al de setembre.
        Plan plan = plan(steamDeck(), List.of(
                paid("97.38", LocalDate.of(2026, 10, 2)),
                paidFor("97.38", LocalDate.of(2026, 10, 20), OCTOBER_8)), LocalDate.of(2026, 10, 21));

        assertThat(statuses(plan.receipts()).subList(0, 3)).containsExactly("PAGAT", "PAGAT", "PENDENT");
        assertThat(plan.receiptOfPayment()).containsExactly(SEPTEMBER_8, OCTOBER_8);
    }

    // ============ EL QUE NO QUADRA ============

    @Test
    @DisplayName("El que sobra d'un rebut acorta el final, no avança els mesos següents")
    void extraMoneyShortensTheEnd() {
        Debt debt = installments("1000.00", "100.00", "MENSUAL", LocalDate.of(2026, 1, 10));

        // Al febrer arriben 300 €: el febrer queda pagat i els 200 que sobren
        // treuen els dos últims rebuts. El març continua tocant.
        Plan plan = plan(debt, List.of(
                paid("100.00", LocalDate.of(2026, 1, 10)),
                paid("300.00", LocalDate.of(2026, 2, 10))), LocalDate.of(2026, 3, 5));

        assertThat(plan.active()).hasSize(8);
        assertThat(statuses(plan.receipts()).subList(0, 3)).containsExactly("PAGAT", "PAGAT", "TOCA");
        assertThat(plan.receipts().get(1).paid()).isEqualByComparingTo("300.00");
        assertThat(plan.receipts().get(7).date()).isEqualTo(LocalDate.of(2026, 8, 10));
        assertThat(plan.next().date()).isEqualTo(LocalDate.of(2026, 3, 10));
        assertThat(plan.next().amount()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("Si el que sobra no fa un rebut sencer, l'últim es fa més petit")
    void extraMoneyShrinksTheLastReceipt() {
        Debt debt = installments("500.00", "100.00", "MENSUAL", LocalDate.of(2026, 1, 10));

        Plan plan = plan(debt, List.of(paid("150.00", LocalDate.of(2026, 1, 10))), LocalDate.of(2026, 1, 15));

        assertThat(amounts(plan.receipts())).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("100"),
                        new BigDecimal("100"), new BigDecimal("50"));
        assertThat(plan.receipts().get(0).paid()).isEqualByComparingTo("150.00");
    }

    @Test
    @DisplayName("Dos pagaments petits sense rebut s'ajunten en el mateix rebut")
    void smallPaymentsAddUp() {
        Debt debt = installments("300.00", "100.00", "MENSUAL", LocalDate.of(2026, 1, 10));

        Plan plan = plan(debt, List.of(
                paid("50.00", LocalDate.of(2026, 1, 5)),
                paid("50.00", LocalDate.of(2026, 1, 20))), LocalDate.of(2026, 1, 25));

        assertThat(statuses(plan.receipts())).containsExactly("PAGAT", "PENDENT", "PENDENT");
        assertThat(plan.receiptOfPayment()).containsExactly(LocalDate.of(2026, 1, 10), LocalDate.of(2026, 1, 10));
    }

    @Test
    @DisplayName("Un rebut a mitges és parcial fins que s'acaba el mes, i llavors el que falta va endarrerit")
    void partialReceipt() {
        Debt debt = installments("300.00", "100.00", "MENSUAL", LocalDate.of(2026, 1, 10));
        List<Payment> payments = List.of(paid("40.00", LocalDate.of(2026, 1, 12)));

        Plan january = plan(debt, payments, LocalDate.of(2026, 1, 20));
        Plan february = plan(debt, payments, LocalDate.of(2026, 2, 1));

        assertThat(january.receipts().get(0).status()).isEqualTo("PARCIAL");
        assertThat(january.overdue()).isEqualByComparingTo("0");
        assertThat(january.next().amount()).isEqualByComparingTo("60.00");
        assertThat(february.receipts().get(0).status()).isEqualTo("ENDARRERIT");
        assertThat(february.overdue()).isEqualByComparingTo("60.00");
        // A mitges no es menja res del final: el pla segueix sent de tres.
        assertThat(february.active()).hasSize(3);
    }

    @Test
    @DisplayName("Els cèntims que falten dels rebuts pagats van a l'últim, sense un rebut de cèntims al darrere")
    void missingCentsGoToTheLastReceipt() {
        Debt debt = installments("300.00", "100.00", "MENSUAL", LocalDate.of(2026, 1, 10));

        Plan plan = plan(debt, List.of(
                paid("99.50", LocalDate.of(2026, 1, 10)),
                paid("99.50", LocalDate.of(2026, 2, 10))), LocalDate.of(2026, 2, 15));

        assertThat(statuses(plan.receipts())).containsExactly("PAGAT", "PAGAT", "PENDENT");
        assertThat(plan.receipts().get(2).amount()).isEqualByComparingTo("101.00");
    }

    @Test
    @DisplayName("Una diferència de més d'un 1 % no és arrodoniment: el rebut queda a mitges")
    void aRealShortfallIsNotRounding() {
        Debt debt = installments("300.00", "100.00", "MENSUAL", LocalDate.of(2026, 1, 10));

        Plan plan = plan(debt, List.of(paid("98.00", LocalDate.of(2026, 1, 10))), LocalDate.of(2026, 1, 15));

        assertThat(plan.receipts().get(0).status()).isEqualTo("PARCIAL");
    }

    // ============ REBUTS TRETS ============

    @Test
    @DisplayName("Saltar un rebut: aquell mes no toca, el pagament passa al següent i el pla s'allarga un mes")
    void skippingAReceiptMovesItToTheEnd() {
        Plan plan = RepaymentSchedule.plan(steamDeck(), List.of(new Removal(SEPTEMBER_8, BigDecimal.ZERO)),
                List.of(paid("97.35", LocalDate.of(2026, 10, 6))), LocalDate.of(2026, 10, 9));

        assertThat(plan.receipts().get(0).status()).isEqualTo("SALTAT");
        assertThat(plan.receipts().get(0).removable()).isFalse();
        assertThat(statuses(plan.active()).subList(0, 2)).containsExactly("PAGAT", "PENDENT");
        assertThat(plan.active()).hasSize(8);
        assertThat(plan.active().get(0).date()).isEqualTo(OCTOBER_8);
        assertThat(plan.active().get(7).date()).isEqualTo(LocalDate.of(2027, 5, 8));
        assertThat(plan.discounted()).isEqualByComparingTo("0");
        assertThat(plan.receiptOfPayment()).containsExactly(OCTOBER_8);
    }

    @Test
    @DisplayName("Descomptar un rebut: desapareix i el seu import es resta del deute")
    void discountingAReceiptReducesTheDebt() {
        Plan plan = RepaymentSchedule.plan(steamDeck(), List.of(new Removal(SEPTEMBER_8, new BigDecimal("97.38"))),
                List.of(paid("97.35", LocalDate.of(2026, 10, 6))), LocalDate.of(2026, 10, 9));

        assertThat(plan.receipts().get(0).status()).isEqualTo("DESCOMPTAT");
        assertThat(plan.receipts().get(0).amount()).isEqualByComparingTo("97.38");
        assertThat(plan.discounted()).isEqualByComparingTo("97.38");
        assertThat(plan.active()).hasSize(7);
        assertThat(plan.active().get(0).status()).isEqualTo("PAGAT");
        assertThat(plan.active().get(6).date()).isEqualTo(LocalDate.of(2027, 4, 8));
    }

    @Test
    @DisplayName("Un pagament que havia triat un rebut tret va al que toca")
    void choosingARemovedReceiptFallsBackToTheNextOne() {
        Plan plan = RepaymentSchedule.plan(steamDeck(), List.of(new Removal(SEPTEMBER_8, BigDecimal.ZERO)),
                List.of(paidFor("97.38", LocalDate.of(2026, 10, 6), SEPTEMBER_8)), LocalDate.of(2026, 10, 9));

        assertThat(plan.receiptOfPayment()).containsExactly(OCTOBER_8);
        assertThat(plan.chosen()).containsExactly(false);
    }

    // ============ PERÍODES ============

    @Test
    @DisplayName("Setmanal: el període és de dilluns a diumenge")
    void weeklyPeriod() {
        // Dimecres 4 de març de 2026.
        Debt debt = installments("200.00", "100.00", "SETMANAL", LocalDate.of(2026, 3, 4));

        Plan sunday = plan(debt, List.of(), LocalDate.of(2026, 3, 8));
        Plan monday = plan(debt, List.of(), LocalDate.of(2026, 3, 9));

        assertThat(sunday.receipts().get(0).status()).isEqualTo("TOCA");
        assertThat(monday.receipts().get(0).status()).isEqualTo("ENDARRERIT");
        assertThat(monday.receipts().get(1).status()).isEqualTo("TOCA");
    }

    @Test
    @DisplayName("Trimestral: el període són els tres mesos que comencen el del rebut")
    void quarterlyPeriod() {
        Debt debt = installments("200.00", "100.00", "TRIMESTRAL", LocalDate.of(2026, 2, 15));

        assertThat(plan(debt, List.of(), LocalDate.of(2026, 4, 30)).receipts().get(0).status()).isEqualTo("TOCA");
        assertThat(plan(debt, List.of(), LocalDate.of(2026, 5, 1)).receipts().get(0).status())
                .isEqualTo("ENDARRERIT");
    }

    @Test
    @DisplayName("Tot de cop també va per mes, i només està pagat si està pagat del tot")
    void singlePaymentByMonth() {
        Debt debt = new Debt();
        debt.setDirection(Debt.I_OWE);
        debt.setAmount(new BigDecimal("450.00"));
        debt.setRepaymentPlan(Debt.PLAN_SINGLE);
        debt.setFirstPaymentDate(LocalDate.of(2026, 10, 1));
        List<Payment> payments = List.of(paid("449.50", LocalDate.of(2026, 10, 20)));

        Plan october = plan(debt, payments, LocalDate.of(2026, 10, 25));
        Plan november = plan(debt, payments, LocalDate.of(2026, 11, 1));

        assertThat(october.receipts().get(0).status()).isEqualTo("PARCIAL");
        assertThat(november.receipts().get(0).status()).isEqualTo("ENDARRERIT");
        assertThat(november.overdue()).isEqualByComparingTo("0.50");
    }

    @Test
    @DisplayName("El que valen els rebuts d'un mes se suma amb els dos extrems inclosos")
    void dueBetweenIncludesBothEnds() {
        Plan plan = plan(installments("400.00", "100.00", "SETMANAL", LocalDate.of(2026, 3, 1)), List.of(), FAR_AWAY);

        // 1, 8, 15 i 22 de març: tots quatre cauen dins del mes.
        assertThat(plan.dueBetween(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31)))
                .isEqualByComparingTo("400.00");
        assertThat(plan.dueBetween(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30)))
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("Un mes saltat no suma res; el rebut passa al final")
    void dueBetweenSkipsRemovedReceipts() {
        Plan plan = RepaymentSchedule.plan(steamDeck(), List.of(new Removal(SEPTEMBER_8, BigDecimal.ZERO)),
                List.of(), LocalDate.of(2026, 9, 1));

        assertThat(plan.dueBetween(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30))).isEqualByComparingTo("0");
        assertThat(plan.dueBetween(LocalDate.of(2027, 5, 1), LocalDate.of(2027, 5, 31)))
                .isEqualByComparingTo("97.34");
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
