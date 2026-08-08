package com.budgetai.backend.integration;

import com.budgetai.backend.model.*;
import com.budgetai.backend.repository.*;
import com.budgetai.backend.service.BudgetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cost de vida contra caixa, amb la base de dades pel mig.
 *
 * L'escenari és el del plantejament: un grup "Cotxe" amb una assegurança de
 * 600 € l'any (fixa) i el combustible (variable). El març cau el càrrec de
 * l'assegurança.
 *
 *   COST DE VIDA del març = 50 (prorrateig) + 45 (combustible real) =  95
 *   CAIXA        del març = 600 (càrrec real) + 45                  = 645
 */
class MonthlySummaryIntegrationTest extends AbstractIntegrationTest {

    @Autowired private BudgetService budgetService;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private RecurringTransactionRepository recurringRepository;
    @Autowired private BudgetRepository budgetRepository;

    private Long groupId;
    private Long fixedLeafId;
    private Long variableLeafId;

    @BeforeEach
    void setUp() {
        transactionRepository.deleteAll();
        recurringRepository.deleteAll();
        budgetRepository.deleteAll();
        categoryRepository.deleteAll();

        Category group = saveCategory("Cotxe", null, null);
        groupId = group.getId();
        fixedLeafId = saveCategory("Assegurança", groupId, Category.FIXED).getId();
        variableLeafId = saveCategory("Combustible", groupId, Category.VARIABLE).getId();

        // 600 € l'any: 50 € al mes de cost de vida.
        saveRecurring("Assegurança cotxe", "600.00", "ANUAL", fixedLeafId);

        // El càrrec real de l'assegurança cau al març.
        saveTransaction("600.00", LocalDate.of(2026, 3, 15), fixedLeafId, "h-assegurança");
        // I aquest mes s'han gastat 45 € de combustible.
        saveTransaction("45.00", LocalDate.of(2026, 3, 20), variableLeafId, "h-combustible");
    }

    private Category saveCategory(String name, Long parentId, String costType) {
        Category category = new Category(name);
        category.setParentId(parentId);
        category.setCostType(costType);
        return categoryRepository.save(category);
    }

    private void saveRecurring(String name, String amount, String frequency, Long categoryId) {
        RecurringTransaction rt = new RecurringTransaction();
        rt.setName(name);
        rt.setAmount(new BigDecimal(amount));
        rt.setFrequency(frequency);
        rt.setType("EXPENSE");
        rt.setNextDate(LocalDate.of(2026, 3, 15));
        rt.setCategory(categoryRepository.findById(categoryId).orElseThrow());
        rt.setActive(true);
        recurringRepository.save(rt);
    }

    private void saveTransaction(String amount, LocalDate date, Long categoryId, String hash) {
        Transaction t = new Transaction();
        t.setAmount(new BigDecimal(amount));
        t.setDate(date);
        t.setType("EXPENSE");
        t.setCategory(categoryRepository.findById(categoryId).orElseThrow());
        t.setVerificationHash(hash);
        transactionRepository.save(t);
    }

    private Map<String, Object> groupNode() {
        return budgetService.getMonthlySummary(2026, 3).stream()
                .filter(n -> "Cotxe".equals(((Category) n.get("categoria")).getName()))
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> leafNode(String name) {
        return ((List<Map<String, Object>>) groupNode().get("subcategories")).stream()
                .filter(n -> name.equals(((Category) n.get("categoria")).getName()))
                .findFirst()
                .orElseThrow();
    }

    @Test
    @DisplayName("El cost de vida del grup reparteix el fix i suma el variable real")
    void groupCostOfLiving() {
        assertThat((BigDecimal) groupNode().get("cost_vida_real"))
                .isEqualByComparingTo("95.00");
    }

    @Test
    @DisplayName("La caixa del grup és el que ha sortit del compte, amb el càrrec sencer")
    void groupCash() {
        assertThat((BigDecimal) groupNode().get("caixa_real"))
                .isEqualByComparingTo("645.00");
    }

    @Test
    @DisplayName("S'avisa que aquest mes ha caigut un càrrec fix puntual")
    void oneOffChargeIsFlagged() {
        // Sense aquesta marca, un pic de caixa de 600 € sembla un error.
        assertThat(groupNode().get("carrec_puntual_aquest_mes")).isEqualTo(true);
        assertThat(leafNode("Assegurança").get("carrec_puntual_aquest_mes")).isEqualTo(true);
        assertThat(leafNode("Combustible").get("carrec_puntual_aquest_mes")).isEqualTo(false);
    }

    @Test
    @DisplayName("Una fulla fixa val el prorrateig al cost de vida i el càrrec real a la caixa")
    void fixedLeafSplitsTheTwoViews() {
        Map<String, Object> leaf = leafNode("Assegurança");

        assertThat((BigDecimal) leaf.get("prorrateig_mensual")).isEqualByComparingTo("50.00");
        assertThat((BigDecimal) leaf.get("cost_vida_real")).isEqualByComparingTo("50.00");
        assertThat((BigDecimal) leaf.get("caixa_real")).isEqualByComparingTo("600.00");
    }

    @Test
    @DisplayName("Una fulla variable val el mateix a les dues vistes")
    void variableLeafIsTheSameInBothViews() {
        Map<String, Object> leaf = leafNode("Combustible");

        assertThat((BigDecimal) leaf.get("cost_vida_real")).isEqualByComparingTo("45.00");
        assertThat((BigDecimal) leaf.get("caixa_real")).isEqualByComparingTo("45.00");
    }

    @Test
    @DisplayName("Un mes sense el càrrec manté el cost de vida i baixa la caixa")
    void quietMonthKeepsTheProratedCost() {
        // L'abril no cau l'assegurança i no hi ha combustible.
        Map<String, Object> april = budgetService.getMonthlySummary(2026, 4).stream()
                .filter(n -> "Cotxe".equals(((Category) n.get("categoria")).getName()))
                .findFirst().orElseThrow();

        // El cost de viure no canvia perquè el rebut caigui un altre mes.
        assertThat((BigDecimal) april.get("cost_vida_real")).isEqualByComparingTo("50.00");
        assertThat((BigDecimal) april.get("caixa_real")).isEqualByComparingTo("0");
        assertThat(april.get("carrec_puntual_aquest_mes")).isEqualTo(false);
    }

    @Test
    @DisplayName("El sostre d'una variable entra al pla; el d'un fix és el prorrateig")
    void planCombinesCeilingsAndProration() {
        Budget budget = new Budget();
        budget.setCategory(categoryRepository.findById(variableLeafId).orElseThrow());
        budget.setLimitAmount(new BigDecimal("80.00"));
        budget.setPeriodStart(LocalDate.of(2026, 3, 1));
        budget.setPeriodEnd(LocalDate.of(2026, 3, 31));
        budget.setActive(true);
        budgetRepository.save(budget);

        // 50 del fix prorratejat + 80 de sostre del variable.
        assertThat((BigDecimal) groupNode().get("cost_vida_pla"))
                .isEqualByComparingTo("130.00");
    }

    @Test
    @DisplayName("El gasto d'un pressupost de grup agrega totes les seves fulles")
    void groupBudgetAggregatesLeaves() {
        Budget budget = new Budget();
        budget.setCategory(categoryRepository.findById(groupId).orElseThrow());
        budget.setLimitAmount(new BigDecimal("700.00"));
        budget.setPeriodStart(LocalDate.of(2026, 3, 1));
        budget.setPeriodEnd(LocalDate.of(2026, 3, 31));
        budget.setActive(true);
        budgetRepository.save(budget);

        Budget stored = budgetService.getAllBudgets().stream()
                .filter(b -> b.getCategory().getId().equals(groupId))
                .findFirst().orElseThrow();

        // 600 + 45: el grup no té moviments propis, els hereta de les fulles.
        assertThat(stored.getCurrentSpent()).isEqualByComparingTo("645.00");
    }
}
