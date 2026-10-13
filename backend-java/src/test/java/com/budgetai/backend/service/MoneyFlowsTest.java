package com.budgetai.backend.service;

import com.budgetai.backend.model.Account;
import com.budgetai.backend.model.Category;
import com.budgetai.backend.model.Transaction;
import com.budgetai.backend.model.TransactionPart;
import com.budgetai.backend.service.MoneyFlows.Flow;
import com.budgetai.backend.service.MoneyFlows.Kind;
import com.budgetai.backend.service.TransactionLines.Line;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Què és cada línia per al pressupost, l'anàlisi i el tauler. Mana la
 * categoria: el que va a Estalvi i inversió és estalvi, estigui marcat com a
 * traspàs o no.
 */
class MoneyFlowsTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 6);

    private final Account principal = account(1L, "Compte Principal", "CORRIENTE");
    private final Account revolut = account(2L, "Revolut", "CORRIENTE");
    private final Account tradeRepublic = account(3L, "Trade Republic", "INVERSIONES");

    //  Ingressos (INCOME)          └─ Nòmina
    //  Gast mensual                └─ Supermercat
    //  Habitatge (FIXED)           └─ Assegurances
    //  Estalvis (SAVINGS)          ← fulla de primer nivell
    //  Inversió (SAVINGS)          └─ Fons d'inversió
    private final Category income = category(1L, "Ingressos", null, "INCOME");
    private final Category payroll = category(2L, "Nòmina", 1L, null);
    private final Category monthly = category(3L, "Gast mensual", null, null);
    private final Category groceries = category(4L, "Supermercat", 3L, "VARIABLE");
    private final Category housing = category(5L, "Habitatge", null, "FIXED");
    private final Category insurance = category(6L, "Assegurances", 5L, "FIXED");
    private final Category savings = category(7L, "Estalvis", null, "SAVINGS");
    private final Category investment = category(8L, "Inversió", null, "SAVINGS");
    private final Category funds = category(9L, "Fons d'inversió", 8L, null);

    private final MoneyFlows flows = new MoneyFlows(CategoryHierarchyService.treeOf(List.of(
            income, payroll, monthly, groceries, housing, insurance, savings, investment, funds)));

    private static Account account(long id, String name, String type) {
        Account account = new Account();
        account.setId(id);
        account.setName(name);
        account.setType(type);
        return account;
    }

    private static Category category(long id, String name, Long parentId, String costType) {
        Category category = new Category(name);
        category.setId(id);
        category.setParentId(parentId);
        category.setCostType(costType);
        return category;
    }

    private static Transaction movement(Account account, String type, String amount, Category category) {
        Transaction transaction = new Transaction();
        transaction.setAccount(account);
        transaction.setType(type);
        transaction.setAmount(new BigDecimal(amount));
        transaction.setDate(DAY);
        transaction.setCategory(category);
        return transaction;
    }

    private static Transaction transfer(Account account, Account counterpart, String type, String amount, Category category) {
        Transaction transaction = movement(account, type, amount, category);
        transaction.setCounterpartAccount(counterpart);
        return transaction;
    }

    private static Line line(Transaction transaction) {
        return TransactionLines.expand(List.of(transaction), List.of()).get(0);
    }

    private Flow flowOf(Transaction transaction) {
        return flows.of(line(transaction));
    }

    private static void assertFlow(Flow flow, Kind kind, String amount) {
        assertThat(flow.kind()).isEqualTo(kind);
        assertThat(flow.amount()).isEqualByComparingTo(amount);
    }

    // ============ ESTALVI I INVERSIÓ ============

    @Test
    @DisplayName("300 € a Estalvis són estalvi, estiguin marcats com a traspàs o no")
    void savingsCategoryIsSavingWithOrWithoutTransfer() {
        assertFlow(flowOf(transfer(principal, tradeRepublic, "EXPENSE", "300.00", savings)), Kind.SAVING, "300.00");
        // El que es queixava l'usuari: sense marcar com a traspàs, al
        // pressupost era estalvi i a l'anàlisi i al tauler, despesa.
        assertFlow(flowOf(movement(principal, "EXPENSE", "300.00", savings)), Kind.SAVING, "300.00");
    }

    @Test
    @DisplayName("Una subcategoria d'un bloc d'estalvi també és estalvi")
    void savingsSectionIsInherited() {
        assertFlow(flowOf(movement(principal, "EXPENSE", "150.00", funds)), Kind.SAVING, "150.00");
    }

    @Test
    @DisplayName("L'entrada a Trade Republic és la mateixa apartada que la sortida de Principal")
    void incomingLegIntoSavingsIsSaving() {
        assertFlow(flowOf(transfer(tradeRepublic, principal, "INCOME", "300.00", savings)), Kind.SAVING, "300.00");
        assertFlow(flowOf(movement(tradeRepublic, "INCOME", "300.00", savings)), Kind.SAVING, "300.00");
    }

    @Test
    @DisplayName("El que torna de l'estalvi al dia a dia en resta")
    void withdrawalSubtracts() {
        assertFlow(flowOf(transfer(principal, tradeRepublic, "INCOME", "100.00", savings)), Kind.SAVING, "-100.00");
        assertFlow(flowOf(transfer(tradeRepublic, principal, "EXPENSE", "100.00", savings)), Kind.SAVING, "-100.00");
        assertFlow(flowOf(movement(principal, "INCOME", "100.00", savings)), Kind.SAVING, "-100.00");
    }

    @Test
    @DisplayName("Un regal pagat amb la targeta de Trade Republic és despesa: gastat del fons")
    void paidFromSavingsAccountIsFundSpending() {
        Transaction gift = movement(tradeRepublic, "EXPENSE", "60.00", savings);

        assertFlow(flowOf(gift), Kind.EXPENSE, "60.00");
        assertThat(MoneyFlows.isSpentFromFund(line(gift))).isTrue();
    }

    @Test
    @DisplayName("Un regal pagat des de Principal amb una categoria d'estalvi compta com a estalvi: va en una de despesa")
    void giftFromDayToDayWithSavingsCategoryIsSaving() {
        assertFlow(flowOf(movement(principal, "EXPENSE", "60.00", savings)), Kind.SAVING, "60.00");
    }

    @Test
    @DisplayName("Entre dos comptes d'estalvi no s'aparta res")
    void betweenSavingsAccountsIsNothing() {
        Account otherFund = account(4L, "MyInvestor", "AHORRO");
        assertFlow(flowOf(transfer(tradeRepublic, otherFund, "EXPENSE", "500.00", funds)), Kind.NONE, "0");
    }

    // ============ LA RESTA DE CATEGORIES ============

    @Test
    @DisplayName("Fora d'Estalvi i inversió, un ingrés és ingrés i una despesa és despesa")
    void otherCategoriesKeepTheirType() {
        assertFlow(flowOf(movement(principal, "INCOME", "2000.00", payroll)), Kind.INCOME, "2000.00");
        assertFlow(flowOf(movement(principal, "EXPENSE", "80.00", groceries)), Kind.EXPENSE, "80.00");
        assertFlow(flowOf(movement(principal, "EXPENSE", "80.00", null)), Kind.EXPENSE, "80.00");
    }

    @Test
    @DisplayName("La part de l'assegurança que s'aparta a Trade Republic compta a Assegurances, des de qualsevol pota")
    void provisionForAnExpenseIsExpense() {
        assertFlow(flowOf(transfer(principal, tradeRepublic, "EXPENSE", "160.00", insurance)), Kind.EXPENSE, "160.00");
        // Abans l'entrada a Trade Republic es prenia per diners que tornaven i
        // en restava.
        assertFlow(flowOf(transfer(tradeRepublic, principal, "INCOME", "160.00", insurance)), Kind.EXPENSE, "160.00");
        // El que en torna desfà el que s'hi havia comptat.
        assertFlow(flowOf(transfer(principal, tradeRepublic, "INCOME", "160.00", insurance)), Kind.EXPENSE, "-160.00");
    }

    @Test
    @DisplayName("Un traspàs entre comptes del dia a dia, marcat com a «no comptar», no és res")
    void excludedIsNothing() {
        Transaction toRevolut = transfer(principal, revolut, "EXPENSE", "100.00", null);
        toRevolut.setExcludedFromBudget(true);
        assertFlow(flowOf(toRevolut), Kind.NONE, "0");

        Transaction excludedSaving = movement(principal, "EXPENSE", "300.00", savings);
        excludedSaving.setExcludedFromBudget(true);
        assertFlow(flowOf(excludedSaving), Kind.NONE, "0");
    }

    @Test
    @DisplayName("D'un moviment dividit, cada part va pel que és la seva categoria")
    void partsGoByTheirOwnCategory() {
        Transaction monthlyTransfer = transfer(principal, tradeRepublic, "EXPENSE", "460.00", savings);
        monthlyTransfer.setId(10L);
        TransactionPart toSavings = part(1L, monthlyTransfer, "300.00", savings);
        TransactionPart toInsurance = part(2L, monthlyTransfer, "160.00", insurance);
        List<Line> lines = TransactionLines.expand(List.of(monthlyTransfer), List.of(toSavings, toInsurance));

        assertThat(flows.sum(lines, Kind.SAVING, DAY, DAY)).isEqualByComparingTo("300.00");
        assertThat(flows.sum(lines, Kind.EXPENSE, DAY, DAY)).isEqualByComparingTo("160.00");
        assertThat(flows.sum(lines, Set.of(insurance.getId()), Kind.EXPENSE, DAY, DAY)).isEqualByComparingTo("160.00");
        assertThat(flows.sum(lines, Set.of(groceries.getId()), Kind.EXPENSE, DAY, DAY)).isEqualByComparingTo("0");
    }

    private static TransactionPart part(long id, Transaction transaction, String amount, Category category) {
        TransactionPart part = new TransactionPart();
        part.setId(id);
        part.setTransaction(transaction);
        part.setAmount(new BigDecimal(amount));
        part.setCategory(category);
        part.setExcludedFromBudget(false);
        return part;
    }

    // ============ EL QUE NO S'HA DESTINAT A RES ============

    @Test
    @DisplayName("Sense destinar: ingressos menys despeses i estalvi; el que es paga del fons no hi resta")
    void unassignedIgnoresWhatIsPaidFromTheFund() {
        List<Line> lines = TransactionLines.expand(List.of(
                movement(principal, "INCOME", "2000.00", payroll),
                movement(principal, "EXPENSE", "1500.00", groceries),
                movement(principal, "EXPENSE", "300.00", savings),
                movement(tradeRepublic, "EXPENSE", "100.00", savings)), List.of());

        assertThat(flows.sum(lines, Kind.EXPENSE, DAY, DAY)).isEqualByComparingTo("1600.00");
        assertThat(flows.spentFromFunds(lines, DAY, DAY)).isEqualByComparingTo("100.00");
        // Al compte del dia a dia hi queden 200 €: el regal ha sortit del que
        // ja s'havia apartat.
        assertThat(flows.unassigned(lines, DAY, DAY)).isEqualByComparingTo("200.00");
    }
}
