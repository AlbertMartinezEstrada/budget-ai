package com.budgetai.backend.service;

import com.budgetai.backend.model.Debt;
import com.budgetai.backend.model.Debt.Installment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * El calendari de retorn d'un deute: els rebuts, quin paga cada pagament i com
 * es va.
 *
 * **Els rebuts van per períodes, no per dies.** El rebut d'octubre es paga a
 * l'octubre: no va endarrerit fins que l'octubre s'acaba, i mentre dura és el
 * que toca. Amb el dia exacte, el rebut del dia 8 pagat el 6 i el del dia 8
 * pagat el 10 eren coses diferents, i el segon sortia endarrerit dos dies.
 *
 * **Cada pagament paga un rebut**: el que s'hagi triat en vincular-lo o, si no,
 * el que toca, que és el primer que encara no està pagat. Un rebut queda pagat
 * quan el que hi ha arribat el cobreix, i una diferència de cèntims també el
 * dona per pagat: és arrodoniment, i deixar-lo a mitges per 0,03 € faria que el
 * pagament del mes següent anés a tapar-lo, i el mes següent quedés per pagar.
 *
 * **El que no quadra va al final.** El que sobra d'un rebut treu rebuts del
 * final: 200 € en un rebut de 100 deixen el mes pagat i el pla s'acaba un mes
 * abans. Els cèntims que falten d'un rebut pagat s'afegeixen a l'últim.
 *
 * Un rebut es pot treure del calendari: **saltat**, aquell període no toca i el
 * pla s'allarga un rebut pel final; o **descomptat**, el rebut desapareix i el
 * seu import es resta del deute.
 */
public final class RepaymentSchedule {

    public static final String PAID = "PAGAT";
    public static final String PARTIAL = "PARCIAL";
    /** El del període en curs, encara sense pagar. */
    public static final String CURRENT = "TOCA";
    public static final String PENDING = "PENDENT";
    public static final String OVERDUE = "ENDARRERIT";
    public static final String SKIPPED = "SALTAT";
    public static final String DISCOUNTED = "DESCOMPTAT";

    private static final Set<String> REMOVED = Set.of(SKIPPED, DISCOUNTED);

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

    /** La diferència que es considera arrodoniment: l'1 % del rebut, com a molt 1 €. */
    private static final BigDecimal ROUNDING_SHARE = new BigDecimal("0.01");
    private static final BigDecimal MAX_ROUNDING = BigDecimal.ONE;

    private RepaymentSchedule() {
    }

    /**
     * Uns diners que tornen el deute.
     *
     * @param receipt el dia del rebut que s'ha triat que pagui; null, el que toca
     */
    public record Payment(LocalDate date, BigDecimal amount, LocalDate receipt) {
    }

    /**
     * Un rebut tret del calendari.
     *
     * @param discount el que es resta del deute; zero si només s'ha saltat
     */
    public record Removal(LocalDate date, BigDecimal discount) {
    }

    /**
     * El calendari comparat amb el que s'ha pagat.
     *
     * @param receipts         els rebuts per ordre, amb els trets al seu lloc
     * @param overdue          el que falta dels rebuts que ja han vençut
     * @param next             el primer rebut sense pagar, pel que en falta; null si no n'hi ha
     * @param discounted       el que s'ha rebaixat descomptant rebuts
     * @param receiptOfPayment el rebut on compta cada pagament, en el mateix
     *                         ordre que han arribat; null si no en paga cap
     * @param chosen           si aquell pagament va al rebut que es va triar
     */
    public record Plan(List<Installment> receipts, BigDecimal overdue, Installment next,
                       BigDecimal discounted, List<LocalDate> receiptOfPayment, List<Boolean> chosen) {

        /** Els rebuts del calendari, sense els trets. */
        public List<Installment> active() {
            return receipts.stream().filter(RepaymentSchedule::isActive).toList();
        }

        /** El que valen els rebuts que cauen entre dues dates, totes dues incloses. */
        public BigDecimal dueBetween(LocalDate from, LocalDate to) {
            return active().stream()
                    .filter(receipt -> !receipt.date().isBefore(from) && !receipt.date().isAfter(to))
                    .map(Installment::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    /** Si el rebut és al calendari: ni saltat ni descomptat. */
    public static boolean isActive(Installment receipt) {
        return !REMOVED.contains(receipt.status());
    }

    /**
     * El calendari d'un deute, amb el que ha pagat cada pagament.
     *
     * @param removals els rebuts trets; només compten a quotes, però el que
     *                 s'ha descomptat es resta del deute sigui com sigui
     * @param payments els que van en sentit de retorn, en qualsevol ordre
     * @param today    per saber quins períodes s'han acabat
     */
    public static Plan plan(Debt debt, List<Removal> removals, List<Payment> payments, LocalDate today) {
        BigDecimal discounted = removals.stream()
                .map(Removal::discount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal owed = debt.getAmount() != null
                ? debt.getAmount().subtract(discounted).max(BigDecimal.ZERO)
                : BigDecimal.ZERO;

        if (Debt.PLAN_SINGLE.equals(debt.getRepaymentPlan()) && debt.getFirstPaymentDate() != null) {
            return single(debt.getFirstPaymentDate(), owed, discounted, payments, today);
        }
        BigDecimal installment = debt.getInstallment();
        if (!Debt.PLAN_INSTALLMENTS.equals(debt.getRepaymentPlan())
                || installment == null || installment.signum() <= 0 || debt.getFirstPaymentDate() == null) {
            return new Plan(List.of(), BigDecimal.ZERO, null, discounted,
                    Arrays.asList(new LocalDate[payments.size()]), falses(payments.size()));
        }
        return installments(debt, owed, discounted, removals, payments, today);
    }

    /** Tot de cop: un sol rebut pel que es deu, i tots els pagaments hi van. */
    private static Plan single(LocalDate date, BigDecimal owed, BigDecimal discounted,
                               List<Payment> payments, LocalDate today) {
        BigDecimal paid = payments.stream().map(Payment::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        // Sense rebuts al darrere on enviar els cèntims, un sol rebut només està
        // pagat si està pagat del tot.
        boolean covered = paid.compareTo(owed) >= 0;
        String status = statusOf(date, null, paid, covered, today);
        Installment receipt = new Installment(date, owed, paid, status, false);

        List<LocalDate> receiptOfPayment = new ArrayList<>();
        payments.forEach(payment -> receiptOfPayment.add(date));
        return new Plan(List.of(receipt),
                OVERDUE.equals(status) ? owed.subtract(paid) : BigDecimal.ZERO,
                covered ? null : new Installment(date, owed.subtract(paid), paid, status, false),
                discounted, receiptOfPayment, falses(payments.size()));
    }

    private static Plan installments(Debt debt, BigDecimal owed, BigDecimal discounted, List<Removal> removals,
                                     List<Payment> payments, LocalDate today) {
        String frequency = debt.getFrequency();
        BigDecimal installment = debt.getInstallment();
        Dates dates = new Dates(debt.getFirstPaymentDate(), frequency, removals);

        LocalDate[] receiptOfPayment = new LocalDate[payments.size()];
        Boolean[] chosen = new Boolean[payments.size()];
        Arrays.fill(chosen, Boolean.FALSE);

        // Primer els que han triat rebut, perquè el que toca és el primer que
        // queda lliure després d'ells. Si s'agafés per ordre de data, un
        // pagament sense rebut fet abans podria ocupar el rebut que un altre
        // havia triat.
        Map<Integer, BigDecimal> paidByChoice = new HashMap<>();
        Deque<Integer> unassigned = new ArrayDeque<>();
        for (int index : byDate(payments)) {
            Payment payment = payments.get(index);
            int ordinal = payment.receipt() != null ? dates.ordinalOf(payment.receipt()) : -1;
            if (ordinal < 0) {
                // Sense rebut, o amb un que ja no és al calendari (s'ha tret,
                // o s'ha canviat el dia de la primera quota): va al que toca.
                unassigned.add(index);
                continue;
            }
            paidByChoice.merge(ordinal, payment.amount(), BigDecimal::add);
            receiptOfPayment[index] = dates.at(ordinal);
            chosen[index] = Boolean.TRUE;
        }
        int lastChosen = paidByChoice.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1);

        List<Installment> receipts = new ArrayList<>();
        BigDecimal remaining = owed;
        for (int ordinal = 0; remaining.signum() > 0 || ordinal <= lastChosen; ordinal++) {
            LocalDate date = dates.at(ordinal);
            if (date == null) break;

            BigDecimal due = dueOf(remaining.max(BigDecimal.ZERO), installment);
            BigDecimal paid = paidByChoice.getOrDefault(ordinal, BigDecimal.ZERO);
            while (!covers(paid, due) && !unassigned.isEmpty()) {
                int index = unassigned.poll();
                paid = paid.add(payments.get(index).amount());
                receiptOfPayment[index] = date;
            }
            // Passat el final del pla només queden els rebuts que algú ha
            // triat: els buits d'entremig no són rebuts.
            if (due.signum() == 0 && paid.signum() == 0) continue;

            boolean covered = covers(paid, due);
            // Un rebut pagat es menja tot el que hi ha arribat: el que sobra
            // treu rebuts del final i els cèntims que falten hi van a parar.
            // Un de no pagat es queda el que val, i el que falta és seu.
            remaining = remaining.subtract(covered ? paid : due);
            receipts.add(new Installment(date, due, paid,
                    statusOf(date, frequency, paid, covered, today),
                    !paidByChoice.containsKey(ordinal)));
        }

        BigDecimal overdue = receipts.stream()
                .filter(receipt -> OVERDUE.equals(receipt.status()))
                .map(receipt -> receipt.amount().subtract(receipt.paid()).max(BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Installment next = receipts.stream()
                .filter(receipt -> !PAID.equals(receipt.status()))
                .findFirst()
                .map(receipt -> new Installment(receipt.date(), receipt.amount().subtract(receipt.paid()),
                        receipt.paid(), receipt.status(), receipt.removable()))
                .orElse(null);

        for (Removal removal : removals) {
            boolean isDiscount = removal.discount().signum() > 0;
            receipts.add(new Installment(removal.date(), removal.discount(), BigDecimal.ZERO,
                    isDiscount ? DISCOUNTED : SKIPPED, false));
        }
        receipts.sort(Comparator.comparing(Installment::date));

        return new Plan(receipts, overdue, next, discounted,
                Arrays.asList(receiptOfPayment), Arrays.asList(chosen));
    }

    /**
     * El que val el rebut, amb el que encara queda per cobrar.
     *
     * Una quota sencera, o el que quedi si és menys. Si després d'aquest només
     * en quedarien uns cèntims, aquest se'ls queda: un últim rebut de 0,20 €
     * no el paga ningú.
     */
    static BigDecimal dueOf(BigDecimal remaining, BigDecimal installment) {
        if (remaining.compareTo(installment) <= 0) return remaining;
        if (remaining.subtract(installment).compareTo(rounding(installment)) <= 0) return remaining;
        return installment;
    }

    /** Si el que ha arribat paga el rebut, comptant els cèntims d'arrodoniment. */
    static boolean covers(BigDecimal paid, BigDecimal due) {
        return paid.compareTo(due.subtract(rounding(due))) >= 0;
    }

    private static BigDecimal rounding(BigDecimal amount) {
        return amount.multiply(ROUNDING_SHARE).min(MAX_ROUNDING).setScale(2, RoundingMode.DOWN);
    }

    private static String statusOf(LocalDate date, String frequency, BigDecimal paid,
                                   boolean covered, LocalDate today) {
        if (covered) return PAID;
        // A mitges però amb el període acabat: el que compta és que falten diners.
        if (periodEnd(date, frequency).isBefore(today)) return OVERDUE;
        if (paid.signum() > 0) return PARTIAL;
        if (!periodStart(date, frequency).isAfter(today)) return CURRENT;
        return PENDING;
    }

    /**
     * El primer dia del període d'un rebut: el dilluns de la seva setmana o
     * l'1 del seu mes. Un trimestre comença el mes del rebut i en dura tres.
     */
    public static LocalDate periodStart(LocalDate date, String frequency) {
        if (WEEKLY.equals(frequency)) return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return date.withDayOfMonth(1);
    }

    /** L'últim dia del període d'un rebut. Sense freqüència (tot de cop), el mes. */
    public static LocalDate periodEnd(LocalDate date, String frequency) {
        if (WEEKLY.equals(frequency)) return periodStart(date, frequency).plusDays(6);
        if (QUARTERLY.equals(frequency)) return date.withDayOfMonth(1).plusMonths(3).minusDays(1);
        return date.withDayOfMonth(date.lengthOfMonth());
    }

    /** Si una data cau dins del període del rebut. */
    public static boolean samePeriod(LocalDate receipt, LocalDate other, String frequency) {
        return !other.isBefore(periodStart(receipt, frequency)) && !other.isAfter(periodEnd(receipt, frequency));
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
     * El dia del rebut número {@code index}, comptant des del primer.
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

    /** Els índexs dels pagaments per ordre de data; els de la mateixa data, com han arribat. */
    private static List<Integer> byDate(List<Payment> payments) {
        return IntStream.range(0, payments.size()).boxed()
                .sorted(Comparator.comparing(index -> payments.get(index).date(),
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    private static List<Boolean> falses(int size) {
        Boolean[] values = new Boolean[size];
        Arrays.fill(values, Boolean.FALSE);
        return Arrays.asList(values);
    }

    /**
     * Els dies dels rebuts del calendari, sense els trets, generats a mesura
     * que calen.
     *
     * El límit compta també els trets: cada rebut saltat n'afegeix un al final.
     */
    private static final class Dates {
        private final LocalDate first;
        private final String frequency;
        private final List<Removal> removals;
        private final List<LocalDate> active = new ArrayList<>();
        private int nextIndex;

        Dates(LocalDate first, String frequency, List<Removal> removals) {
            this.first = first;
            this.frequency = frequency;
            this.removals = removals;
        }

        /** El dia del rebut número {@code ordinal}; null passat el límit. */
        LocalDate at(int ordinal) {
            while (active.size() <= ordinal) {
                if (nextIndex >= MAX_INSTALLMENTS + removals.size()) return null;
                LocalDate candidate = dateOf(first, frequency, nextIndex++);
                boolean removed = removals.stream()
                        .anyMatch(removal -> samePeriod(candidate, removal.date(), frequency));
                if (!removed) active.add(candidate);
            }
            return active.get(ordinal);
        }

        /** Quin rebut és el del període d'aquella data; -1 si no n'hi ha cap. */
        int ordinalOf(LocalDate date) {
            for (int ordinal = 0; ; ordinal++) {
                LocalDate receipt = at(ordinal);
                if (receipt == null || periodStart(receipt, frequency).isAfter(date)) return -1;
                if (samePeriod(receipt, date, frequency)) return ordinal;
            }
        }
    }
}
