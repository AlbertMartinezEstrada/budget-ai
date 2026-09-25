package com.budgetai.backend.integration;

import com.budgetai.backend.model.Budget;
import com.budgetai.backend.model.Category;
import com.budgetai.backend.model.RecurringTransaction;
import com.budgetai.backend.repository.*;
import com.budgetai.backend.service.BudgetService;
import com.budgetai.backend.service.FixedCostService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Els costos fixos del menú de Pressupostos, i com arriben a cada mes.
 *
 * L'escenari: un bloc "Llar" amb el lloguer (fix) i la llum (variable). El
 * lloguer es dona d'alta al març a 800 € i puja a 850 € al maig.
 */
class FixedCostIntegrationTest extends AbstractIntegrationTest {

    private static final YearMonth MARCH = YearMonth.of(2026, 3);
    private static final YearMonth APRIL = YearMonth.of(2026, 4);
    private static final YearMonth MAY = YearMonth.of(2026, 5);
    private static final YearMonth JUNE = YearMonth.of(2026, 6);

    @Autowired private FixedCostService fixedCostService;
    @Autowired private BudgetService budgetService;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private RecurringTransactionRepository recurringRepository;
    @Autowired private BudgetRepository budgetRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private MonthlyIncomeRepository monthlyIncomeRepository;

    private Category rent;
    private Category electricity;

    @BeforeEach
    void setUp() {
        transactionRepository.deleteAll();
        recurringRepository.deleteAll();
        budgetRepository.deleteAll();
        monthlyIncomeRepository.deleteAll();
        categoryRepository.deleteAll();

        Category home = saveCategory("Llar", null, Category.FIXED);
        rent = saveCategory("Lloguer", home.getId(), Category.FIXED);
        electricity = saveCategory("Llum", home.getId(), Category.VARIABLE);
    }

    private Category saveCategory(String name, Long parentId, String costType) {
        Category category = new Category(name);
        category.setParentId(parentId);
        category.setCostType(costType);
        return categoryRepository.save(category);
    }

    private RecurringTransaction request(Category category, String name, String amount) {
        RecurringTransaction request = new RecurringTransaction();
        request.setCategory(category);
        request.setName(name);
        request.setAmount(new BigDecimal(amount));
        request.setFrequency("MENSUAL");
        return request;
    }

    private RecurringTransaction amountOnly(String amount) {
        RecurringTransaction request = new RecurringTransaction();
        request.setAmount(new BigDecimal(amount));
        return request;
    }

    @SuppressWarnings("unchecked")
    private BigDecimal planOf(Category leaf, YearMonth month) {
        List<Map<String, Object>> sections =
                (List<Map<String, Object>>) budgetService.getMonthlySummary(month.getYear(), month.getMonthValue())
                        .get("seccions");
        for (Map<String, Object> section : sections) {
            for (Map<String, Object> group : (List<Map<String, Object>>) section.get("grups")) {
                for (Map<String, Object> node : (List<Map<String, Object>>) group.get("subcategories")) {
                    if (leaf.getId().equals(((Category) node.get("categoria")).getId())) {
                        return (BigDecimal) node.get("cost_vida_pla");
                    }
                }
            }
        }
        throw new AssertionError("No s'ha trobat " + leaf.getName());
    }

    private void saveMonthlyBudget(Category category, YearMonth month, String amount) {
        Budget budget = new Budget();
        budget.setCategory(category);
        budget.setLimitAmount(new BigDecimal(amount));
        budget.setPeriodStart(month.atDay(1));
        budget.setPeriodEnd(month.atEndOfMonth());
        budget.setActive(true);
        budgetRepository.save(budget);
    }

    @Test
    @DisplayName("Un cost fix nou arriba als mesos des del seu, no als d'abans")
    void aNewFixedCostAppliesFromItsMonth() {
        fixedCostService.create(MARCH, request(rent, "Lloguer pis", "800.00"));

        assertThat(planOf(rent, YearMonth.of(2026, 2))).isEqualByComparingTo("0");
        assertThat(planOf(rent, MARCH)).isEqualByComparingTo("800.00");
        assertThat(planOf(rent, JUNE)).isEqualByComparingTo("800.00");
        assertThat(fixedCostService.listFor(APRIL)).hasSize(1);
    }

    @Test
    @DisplayName("Canviar un cost fix val des d'aquell mes: els anteriors no canvien")
    void changingAFixedCostKeepsThePast() {
        RecurringTransaction created = fixedCostService.create(MARCH, request(rent, "Lloguer pis", "800.00"));

        fixedCostService.update(created.getId(), MAY, amountOnly("850.00"));

        assertThat(planOf(rent, APRIL)).isEqualByComparingTo("800.00");
        assertThat(planOf(rent, MAY)).isEqualByComparingTo("850.00");
        // Cada mes veu una sola versió: amb les dues, el lloguer sumaria 1650.
        assertThat(fixedCostService.listFor(APRIL)).singleElement()
                .extracting(RecurringTransaction::getAmount)
                .satisfies(amount -> assertThat(amount).isEqualByComparingTo("800.00"));
        assertThat(fixedCostService.listFor(MAY)).singleElement()
                .extracting(RecurringTransaction::getAmount)
                .satisfies(amount -> assertThat(amount).isEqualByComparingTo("850.00"));
    }

    @Test
    @DisplayName("Un cost fix que comença aquest mes es canvia sense crear-ne una versió")
    void changingInItsFirstMonthEditsInPlace() {
        RecurringTransaction created = fixedCostService.create(MARCH, request(rent, "Lloguer pis", "800.00"));

        fixedCostService.update(created.getId(), MARCH, amountOnly("820.00"));

        assertThat(recurringRepository.findAll()).singleElement()
                .extracting(RecurringTransaction::getAmount)
                .satisfies(amount -> assertThat(amount).isEqualByComparingTo("820.00"));
    }

    @Test
    @DisplayName("Treure un cost fix el tanca: els mesos anteriors el conserven")
    void removingAFixedCostKeepsThePast() {
        RecurringTransaction created = fixedCostService.create(MARCH, request(rent, "Lloguer pis", "800.00"));

        fixedCostService.remove(created.getId(), JUNE);

        assertThat(planOf(rent, MAY)).isEqualByComparingTo("800.00");
        assertThat(planOf(rent, JUNE)).isEqualByComparingTo("0");
        assertThat(fixedCostService.listFor(JUNE)).isEmpty();
    }

    @Test
    @DisplayName("Un import posat en un mes només val per a aquell mes")
    void aMonthlyChangeOnlyAffectsThatMonth() {
        fixedCostService.create(MARCH, request(rent, "Lloguer pis", "800.00"));

        saveMonthlyBudget(rent, APRIL, "900.00");

        assertThat(planOf(rent, APRIL)).isEqualByComparingTo("900.00");
        assertThat(planOf(rent, MAY)).isEqualByComparingTo("800.00");
    }

    @Test
    @DisplayName("Copiar el mes anterior no arrossega un canvi puntual d'un cost fix")
    void copyingDoesNotCarryAOneOffChange() {
        fixedCostService.create(MARCH, request(rent, "Lloguer pis", "800.00"));
        saveMonthlyBudget(rent, APRIL, "900.00");
        // La llum no té cost fix: el que se li hagi assignat sí que viatja.
        saveMonthlyBudget(electricity, APRIL, "60.00");

        budgetService.copyFromPreviousMonth(MAY.getYear(), MAY.getMonthValue());

        assertThat(planOf(rent, MAY)).isEqualByComparingTo("800.00");
        assertThat(budgetRepository.findAll())
                .filteredOn(budget -> budget.getPeriodStart().equals(MAY.atDay(1)))
                .extracting(budget -> budget.getCategory().getId())
                .containsExactly(electricity.getId());
    }

    @Test
    @DisplayName("Un cost fix no pot anar a una fulla variable ni a un grup")
    void onlyFixedLeavesTakeFixedCosts() {
        assertThatThrownBy(() -> fixedCostService.create(MARCH, request(electricity, "Llum", "50.00")))
                .isInstanceOf(IllegalArgumentException.class);
        Category home = categoryRepository.findById(rent.getParentId()).orElseThrow();
        assertThatThrownBy(() -> fixedCostService.create(MARCH, request(home, "Llar", "50.00")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("La versió tancada no genera càrrecs de després del tancament")
    void aClosedVersionStopsCharging() {
        RecurringTransaction created = fixedCostService.create(MARCH, request(rent, "Lloguer pis", "800.00"));
        created.setNextDate(LocalDate.of(2026, 3, 5));
        recurringRepository.save(created);

        RecurringTransaction raised = fixedCostService.update(created.getId(), MAY, amountOnly("850.00"));

        // La versió nova comença el calendari dins la seva vigència, i la
        // vella només pot cobrar fins a l'abril.
        assertThat(raised.getNextDate()).isEqualTo(LocalDate.of(2026, 5, 5));
        assertThat(recurringRepository.findDueRecurringTransactions(LocalDate.of(2026, 6, 30)))
                .extracting(RecurringTransaction::getAmount)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactlyInAnyOrder(new BigDecimal("800.00"), new BigDecimal("850.00"));
    }
}
