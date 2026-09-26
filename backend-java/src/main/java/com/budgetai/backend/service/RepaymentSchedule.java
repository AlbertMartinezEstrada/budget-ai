package com.budgetai.backend.service;

import com.budgetai.backend.model.Debt;
import com.budgetai.backend.model.Debt.Installment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * El calendari de retorn d'un deute, tal com es va acordar.
 *
 * Surt de l'import original i no del que queda: és el pla pactat, i comparar-lo
 * amb el que s'ha retornat de debò és el que diu si es va al dia.
 *
 * Els pagaments es cobreixen per ordre. Si se n'han retornat 250 d'un deute de
 * 100 al mes, els dos primers estan pagats i el tercer, a mitges: no importa
 * quin dia va arribar cada euro, sinó quants n'han arribat.
 */
public final class RepaymentSchedule {

    public static final String PAID = "PAGAT";
    public static final String PARTIAL = "PARCIAL";
    public static final String PENDING = "PENDENT";
    public static final String OVERDUE = "ENDARRERIT";

    public static final String WEEKLY = "SETMANAL";
    public static final String MONTHLY = "MENSUAL";
    public static final String QUARTERLY = "TRIMESTRAL";
    public static final Set<String> FREQUENCIES = Set.of(WEEKLY, MONTHLY, QUARTERLY);

    /**
     * Més pagaments que això vol dir una quota escrita malament (1 € en lloc de
     * 100), no un pla de debò. Sense límit, una quota de cèntims generaria
     * centenars de milers de dates a cada consulta.
     */
    public static final int MAX_INSTALLMENTS = 600;

    private RepaymentSchedule() {
    }

    /** Els pagaments previstos, sense estat. Buit si no hi ha calendari. */
    public static List<Installment> of(Debt debt) {
        if (Debt.PLAN_SINGLE.equals(debt.getRepaymentPlan())) {
            if (debt.getFirstPaymentDate() == null || debt.getAmount() == null) return List.of();
            return List.of(new Installment(debt.getFirstPaymentDate(), debt.getAmount(), null));
        }
        if (!Debt.PLAN_INSTALLMENTS.equals(debt.getRepaymentPlan())) return List.of();

        BigDecimal installment = debt.getInstallment();
        if (installment == null || installment.signum() <= 0
                || debt.getAmount() == null || debt.getFirstPaymentDate() == null) {
            return List.of();
        }
        requireReasonableCount(debt.getAmount(), installment);

        List<Installment> payments = new ArrayList<>();
        BigDecimal remaining = debt.getAmount();
        int index = 0;
        while (remaining.signum() > 0) {
            // L'últim és el que falti: 1.000 a 300 fan 300, 300, 300 i 100.
            BigDecimal payment = remaining.min(installment);
            payments.add(new Installment(dateOf(debt.getFirstPaymentDate(), debt.getFrequency(), index), payment, null));
            remaining = remaining.subtract(payment);
            index++;
        }
        return payments;
    }

    /** Llança l'error amb el text per a l'usuari si la quota no té sentit. */
    public static void requireReasonableCount(BigDecimal amount, BigDecimal installment) {
        BigDecimal count = amount.divide(installment, 0, RoundingMode.CEILING);
        if (count.compareTo(BigDecimal.valueOf(MAX_INSTALLMENTS)) > 0) {
            throw new IllegalArgumentException("Amb aquesta quota serien " + count.toPlainString()
                    + " pagaments. Posa'n una de més gran (màxim " + MAX_INSTALLMENTS + " pagaments).");
        }
    }

    /**
     * El dia del pagament número {@code index}, comptant des del primer.
     *
     * Sempre des del primer i no des de l'anterior: sumant un mes al 28 de
     * febrer, un calendari que començava el 31 de gener es quedaria al 28 per
     * sempre.
     */
    private static LocalDate dateOf(LocalDate first, String frequency, int index) {
        if (WEEKLY.equals(frequency)) return first.plusWeeks(index);
        if (QUARTERLY.equals(frequency)) return first.plusMonths(3L * index);
        return first.plusMonths(index);
    }

    /** Els mateixos pagaments, amb l'estat que els dona el que ja s'ha retornat. */
    public static List<Installment> withStatus(List<Installment> payments, BigDecimal repaid, LocalDate today) {
        List<Installment> described = new ArrayList<>();
        BigDecimal coveredBefore = BigDecimal.ZERO;
        for (Installment payment : payments) {
            BigDecimal coveredAfter = coveredBefore.add(payment.amount());
            String status;
            if (repaid.compareTo(coveredAfter) >= 0) {
                status = PAID;
            } else if (payment.date().isBefore(today)) {
                // A mitges però ja vençut: el que compta és que falten diners.
                status = OVERDUE;
            } else if (repaid.compareTo(coveredBefore) > 0) {
                status = PARTIAL;
            } else {
                status = PENDING;
            }
            described.add(new Installment(payment.date(), payment.amount(), status));
            coveredBefore = coveredAfter;
        }
        return described;
    }

    /** El que ja havia de ser retornat abans d'avui i no ho està. */
    public static BigDecimal overdue(List<Installment> payments, BigDecimal repaid, LocalDate today) {
        BigDecimal due = payments.stream()
                .filter(payment -> payment.date().isBefore(today))
                .map(Installment::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return due.subtract(repaid).max(BigDecimal.ZERO);
    }

    /**
     * El primer pagament que encara no està cobert, amb el que en falta.
     *
     * Si el tercer de 100 està cobert a mitges amb 50, el pròxim és aquest
     * mateix per 50: dir 100 faria pagar de més.
     */
    public static Installment next(List<Installment> payments, BigDecimal repaid) {
        BigDecimal coveredBefore = BigDecimal.ZERO;
        for (Installment payment : payments) {
            BigDecimal coveredAfter = coveredBefore.add(payment.amount());
            if (repaid.compareTo(coveredAfter) < 0) {
                BigDecimal missing = coveredAfter.subtract(repaid.max(coveredBefore));
                return new Installment(payment.date(), missing, null);
            }
            coveredBefore = coveredAfter;
        }
        return null;
    }

    /** Suma dels pagaments previstos entre dues dates, totes dues incloses. */
    public static BigDecimal dueBetween(List<Installment> payments, LocalDate from, LocalDate to) {
        return payments.stream()
                .filter(payment -> !payment.date().isBefore(from) && !payment.date().isAfter(to))
                .map(Installment::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
