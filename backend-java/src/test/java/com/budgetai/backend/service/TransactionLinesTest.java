package com.budgetai.backend.service;

import com.budgetai.backend.model.Category;
import com.budgetai.backend.model.Debt;
import com.budgetai.backend.model.Transaction;
import com.budgetai.backend.model.TransactionPart;
import com.budgetai.backend.service.TransactionLines.Line;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Com es converteixen els moviments en els diners que compten.
 *
 * L'escenari és el de la transferència a Trade Republic: 500 € que són estalvi,
 * la devolució d'un préstec, el que es guarda per a l'assegurança i uns diners
 * que no han de comptar.
 */
class TransactionLinesTest {

    private static final LocalDate TRANSFER_DATE = LocalDate.of(2026, 10, 1);

    private static Category category(Long id, String name) {
        Category category = new Category(name);
        category.setId(id);
        return category;
    }

    private static Transaction transfer(Long id, Category category, Debt debt) {
        Transaction transaction = new Transaction();
        transaction.setId(id);
        transaction.setType("EXPENSE");
        transaction.setDate(TRANSFER_DATE);
        transaction.setAmount(new BigDecimal("500.00"));
        transaction.setCategory(category);
        transaction.setDebt(debt);
        transaction.setExcludedFromBudget(false);
        return transaction;
    }

    private static TransactionPart part(Long id, Transaction transaction, String amount, Category category,
                                        boolean excluded, Debt debt) {
        TransactionPart part = new TransactionPart();
        part.setId(id);
        part.setTransaction(transaction);
        part.setAmount(new BigDecimal(amount));
        part.setCategory(category);
        part.setExcludedFromBudget(excluded);
        part.setDebt(debt);
        return part;
    }

    @Test
    @DisplayName("Un moviment sense parts és una sola línia amb el que diu el moviment")
    void unsplitTransactionIsOneLine() {
        Category savings = category(1L, "Trade Republic");
        Transaction transaction = transfer(10L, savings, null);

        List<Line> lines = TransactionLines.expand(List.of(transaction), List.of());

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).amount()).isEqualByComparingTo("500.00");
        assertThat(lines.get(0).category()).isEqualTo(savings);
        assertThat(lines.get(0).isPart()).isFalse();
    }

    @Test
    @DisplayName("Un moviment dividit dona una línia per part, i el que diu el moviment deixa de comptar")
    void splitTransactionGivesOneLinePerPart() {
        Category savings = category(1L, "Trade Republic");
        Category repayments = category(2L, "Pagament de deutes");
        Category insurance = category(3L, "Assegurances");
        Debt selfLoan = new Debt();
        selfLoan.setId(7L);
        // El moviment encara porta la categoria i el deute d'abans de dividir-lo.
        Transaction transaction = transfer(10L, savings, selfLoan);

        List<Line> lines = TransactionLines.expand(List.of(transaction), List.of(
                part(4L, transaction, "40.00", savings, true, null),
                part(1L, transaction, "300.00", savings, false, null),
                part(2L, transaction, "100.00", repayments, false, selfLoan),
                part(3L, transaction, "60.00", insurance, false, null)));

        assertThat(lines).extracting(Line::amount).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("300"), new BigDecimal("100"),
                        new BigDecimal("60"), new BigDecimal("40"));
        assertThat(lines).extracting(Line::category).containsExactly(savings, repayments, insurance, savings);
        assertThat(lines).extracting(Line::excludedFromBudget).containsExactly(false, false, false, true);
        // El deute només el té la part que torna el préstec, no totes.
        assertThat(lines.stream().filter(line -> line.belongsTo(selfLoan))).hasSize(1);
        // La data i el sentit són sempre els del moviment.
        assertThat(lines).allMatch(line -> line.date().equals(TRANSFER_DATE) && "EXPENSE".equals(line.type()));
    }

    @Test
    @DisplayName("Cada part va amb el seu moviment, encara que arribin barrejades")
    void partsAreMatchedToTheirOwnTransaction() {
        Category savings = category(1L, "Trade Republic");
        Category groceries = category(2L, "Menjar i supermercat");
        Transaction split = transfer(10L, savings, null);
        Transaction plain = transfer(11L, groceries, null);

        List<Line> lines = TransactionLines.expand(List.of(plain, split), List.of(
                part(1L, split, "250.00", savings, false, null),
                part(2L, split, "250.00", savings, true, null)));

        assertThat(lines).hasSize(3);
        assertThat(lines.get(0).transaction()).isSameAs(plain);
        assertThat(lines.get(0).amount()).isEqualByComparingTo("500.00");
        assertThat(lines.subList(1, 3)).allMatch(line -> line.transaction() == split && line.isPart());
    }
}
