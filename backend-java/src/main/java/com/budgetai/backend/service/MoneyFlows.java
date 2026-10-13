package com.budgetai.backend.service;

import com.budgetai.backend.model.Account;
import com.budgetai.backend.model.Category;
import com.budgetai.backend.model.Transaction;
import com.budgetai.backend.service.TransactionLines.Line;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Què és cada línia per a tota l'aplicació: ingrés, despesa, estalvi o res.
 *
 * **És l'únic lloc on es decideix.** El pressupost, l'anàlisi i el tauler el
 * fan servir tots tres. Abans cadascú tenia el seu criteri: el pressupost
 * mirava la categoria, l'anàlisi mirava si era un traspàs i el tauler no sabia
 * què era l'estalvi, i 300 € a Estalvis sortien com a estalvi en un lloc i com
 * a despesa en els altres dos.
 *
 * Mana la categoria, per la secció del bloc d'on penja:
 *
 *   «NO COMPTAR»            res, sigui on sigui. També els traspassos entre
 *                           comptes del dia a dia, que es desen marcats així.
 *   ESTALVI I INVERSIÓ      estalvi, estigui marcat com a traspàs o no. Suma el
 *                           que surt d'un compte del dia a dia o entra a un
 *                           d'estalvi; resta el que en torna.
 *                           Excepció: el que es paga des d'un compte d'estalvi
 *                           a algú altre (un regal amb la targeta de Trade
 *                           Republic) és despesa, "gastat del fons".
 *   QUALSEVOL ALTRA         un ingrés és ingrés i una despesa és despesa, també
 *                           si és un traspàs cap a l'estalvi (la part de
 *                           l'assegurança que s'aparta a Trade Republic compta
 *                           a Assegurances). El que torna d'un compte d'estalvi
 *                           a una categoria de despesa en resta. D'un traspàs
 *                           mana el sentit dels diners i no el tipus del
 *                           moviment: l'entrada a Trade Republic és la mateixa
 *                           apartada que la sortida de Principal.
 *
 * Un regal pagat des de Principal amb una categoria d'estalvi compta com a
 * estalvi: no hi ha manera de distingir-lo dels diners que s'aparten. Va en
 * una categoria de despesa.
 */
public final class MoneyFlows {

    public enum Kind { INCOME, EXPENSE, SAVING, NONE }

    /**
     * @param amount amb signe: un estalvi que torna és negatiu, i també una
     *               despesa que es desfà perquè els diners han tornat
     */
    public record Flow(Kind kind, BigDecimal amount) {
        static final Flow NOTHING = new Flow(Kind.NONE, BigDecimal.ZERO);
    }

    private final CategoryHierarchyService.Tree tree;
    private final Map<Long, String> sectionByCategory = new HashMap<>();

    public MoneyFlows(CategoryHierarchyService.Tree tree) {
        this.tree = tree;
    }

    public Flow of(Line line) {
        if (line.excludedFromBudget()) return Flow.NOTHING;
        BigDecimal amount = line.amount() != null ? line.amount() : BigDecimal.ZERO;
        Transaction transaction = line.transaction();
        boolean income = "INCOME".equals(line.type());

        if (Category.SAVINGS.equals(sectionOf(line.category()))) {
            if (transaction.isTransfer()) {
                int direction = InternalTransferService.savingsDirection(transaction);
                return direction == 0
                        ? Flow.NOTHING
                        : new Flow(Kind.SAVING, amount.multiply(BigDecimal.valueOf(direction)));
            }
            if (isSpentFromFund(line)) {
                // El que surt d'un compte d'estalvi cap a algú altre és una
                // despesa pagada amb el que s'havia apartat.
                return new Flow(Kind.EXPENSE, amount);
            }
            if (isSavingsAccount(transaction.getAccount())) {
                // El que hi entra (un ingrés, uns interessos) s'hi queda.
                return new Flow(Kind.SAVING, amount);
            }
            // Des del dia a dia: el que surt s'aparta, el que hi torna en resta.
            return new Flow(Kind.SAVING, income ? amount.negate() : amount);
        }

        if (transaction.isTransfer()) {
            // Cap a l'estalvi, el que s'aparta per a aquesta categoria hi
            // compta; el que en torna desfà el que s'hi havia comptat. Entre
            // dos comptes del dia a dia (només si no està marcat com a «no
            // comptar»), el que surt compta i el que entra resta.
            int direction = InternalTransferService.savingsDirection(transaction);
            if (direction == 0) direction = income ? -1 : 1;
            return new Flow(Kind.EXPENSE, amount.multiply(BigDecimal.valueOf(direction)));
        }
        return new Flow(income ? Kind.INCOME : Kind.EXPENSE, amount);
    }

    /**
     * Si és una despesa pagada des d'un compte d'estalvi: el regal amb la
     * targeta de Trade Republic, l'assegurança que se'n paga directament. No
     * és diners del mes sinó del que ja s'havia apartat.
     */
    public static boolean isSpentFromFund(Line line) {
        Transaction transaction = line.transaction();
        return !transaction.isTransfer()
                && !"INCOME".equals(line.type())
                && isSavingsAccount(transaction.getAccount());
    }

    /**
     * El que no s'ha destinat a res: els ingressos menys les despeses i
     * l'estalvi. El que s'ha pagat des d'un compte d'estalvi no hi resta, perquè
     * surt del que ja s'havia apartat: un regal de 100 € pagat amb Trade
     * Republic no deixa menys diners al compte del dia a dia.
     */
    public BigDecimal unassigned(List<Line> lines, LocalDate from, LocalDate to) {
        BigDecimal total = BigDecimal.ZERO;
        for (Line line : lines) {
            if (!line.isBetween(from, to)) continue;
            Flow flow = of(line);
            switch (flow.kind()) {
                case INCOME -> total = total.add(flow.amount());
                case SAVING -> total = total.subtract(flow.amount());
                case EXPENSE -> {
                    if (!isSpentFromFund(line)) total = total.subtract(flow.amount());
                }
                case NONE -> { }
            }
        }
        return total;
    }

    /** El que s'ha gastat des dels comptes d'estalvi, ja inclòs a la despesa. */
    public BigDecimal spentFromFunds(List<Line> lines, LocalDate from, LocalDate to) {
        BigDecimal total = BigDecimal.ZERO;
        for (Line line : lines) {
            if (!line.isBetween(from, to) || !isSpentFromFund(line)) continue;
            Flow flow = of(line);
            if (flow.kind() == Kind.EXPENSE) total = total.add(flow.amount());
        }
        return total;
    }

    /** La suma d'un tipus, entre dues dates (incloses). Les línies poden ser de qualsevol categoria. */
    public BigDecimal sum(List<Line> lines, Kind kind, LocalDate from, LocalDate to) {
        BigDecimal total = BigDecimal.ZERO;
        for (Line line : lines) {
            if (!line.isBetween(from, to)) continue;
            Flow flow = of(line);
            if (flow.kind() == kind) total = total.add(flow.amount());
        }
        return total;
    }

    /** El mateix, només de les línies d'unes categories. */
    public BigDecimal sum(List<Line> lines, Set<Long> categoryIds, Kind kind, LocalDate from, LocalDate to) {
        if (categoryIds.isEmpty()) return BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;
        for (Line line : lines) {
            if (line.category() == null || !categoryIds.contains(line.category().getId())) continue;
            if (!line.isBetween(from, to)) continue;
            Flow flow = of(line);
            if (flow.kind() == kind) total = total.add(flow.amount());
        }
        return total;
    }

    private String sectionOf(Category category) {
        if (category == null || category.getId() == null) return Category.VARIABLE;
        return sectionByCategory.computeIfAbsent(category.getId(), tree::sectionOf);
    }

    private static boolean isSavingsAccount(Account account) {
        return account != null && account.isSavings();
    }
}
