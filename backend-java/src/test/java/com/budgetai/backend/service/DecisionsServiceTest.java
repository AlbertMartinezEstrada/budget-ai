package com.budgetai.backend.service;

import com.budgetai.backend.model.Category;
import com.budgetai.backend.model.FinancialGoal;
import com.budgetai.backend.model.RecurringTransaction;
import com.budgetai.backend.model.Transaction;
import com.budgetai.backend.repository.FinancialGoalRepository;
import com.budgetai.backend.repository.RecurringTransactionRepository;
import com.budgetai.backend.repository.TransactionPartRepository;
import com.budgetai.backend.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * La pestanya Decisions, amb resums del pressupost i moviments fets a mà.
 *
 *   Gast mensual (VARIABLE)  └─ Delivery, Restaurants, Supermercat, Cafè
 *   Subscripcions (FIXED)    └─ Netflix
 *   Estalvis (SAVINGS)
 *   Ingressos (INCOME)       └─ Nòmina
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DecisionsServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 14);

    @Mock private BudgetService budgetService;
    @Mock private CategoryHierarchyService hierarchyService;
    @Mock private TransactionRepository transactionRepository;
    @Mock private TransactionPartRepository partRepository;
    @Mock private RecurringTransactionRepository recurringRepository;
    @Mock private FinancialGoalRepository goalRepository;
    @InjectMocks private DecisionsService service;

    private final Category monthly = category(1L, "Gast mensual", null, Category.VARIABLE);
    private final Category delivery = category(2L, "Delivery", 1L, Category.VARIABLE);
    private final Category restaurants = category(3L, "Restaurants", 1L, Category.VARIABLE);
    private final Category groceries = category(4L, "Supermercat", 1L, Category.VARIABLE);
    private final Category coffee = category(5L, "Cafè", 1L, Category.VARIABLE);
    private final Category subscriptions = category(6L, "Subscripcions", null, Category.FIXED);
    private final Category netflix = category(7L, "Netflix", 6L, Category.FIXED);
    private final Category savings = category(8L, "Estalvis", null, Category.SAVINGS);
    private final Category income = category(9L, "Ingressos", null, "INCOME");
    private final Category payroll = category(10L, "Nòmina", 9L, null);

    /** El real de cada fulla variable per mes, i el pla d'aquest mes. */
    private final Map<YearMonth, Map<Category, String>> reals = new HashMap<>();
    private final Map<Category, String> plans = new HashMap<>();
    private final List<Transaction> transactions = new ArrayList<>();
    private final List<RecurringTransaction> recurrings = new ArrayList<>();
    private final List<FinancialGoal> goals = new ArrayList<>();

    @BeforeEach
    void givenRepositories() {
        when(hierarchyService.loadTree()).thenReturn(CategoryHierarchyService.treeOf(List.of(
                monthly, delivery, restaurants, groceries, coffee, subscriptions, netflix, savings, income, payroll)));
        when(transactionRepository.findAll()).thenAnswer(invocation -> transactions);
        when(partRepository.findAll()).thenReturn(List.of());
        when(recurringRepository.findByActiveTrue()).thenAnswer(invocation -> recurrings);
        when(goalRepository.findAllByOrderByTargetDateAsc()).thenAnswer(invocation -> goals);
        when(budgetService.getMonthlySummary(anyInt(), anyInt())).thenAnswer(invocation ->
                summary(YearMonth.of((int) invocation.getArgument(0), (int) invocation.getArgument(1))));
    }

    private static Category category(long id, String name, Long parentId, String costType) {
        Category category = new Category(name);
        category.setId(id);
        category.setParentId(parentId);
        category.setCostType(costType);
        return category;
    }

    /** El resum del pressupost d'un mes, només amb el que fa servir el servei. */
    private Map<String, Object> summary(YearMonth month) {
        Map<Category, String> monthReals = reals.getOrDefault(month, Map.of());
        List<Map<String, Object>> leaves = new ArrayList<>();
        for (Category leaf : List.of(delivery, restaurants, groceries, coffee)) {
            Map<String, Object> node = node(leaf, month.equals(YearMonth.from(TODAY)) ? plans.get(leaf) : null,
                    monthReals.getOrDefault(leaf, "0"));
            leaves.add(node);
        }
        Map<String, Object> block = node(monthly, null, "0");
        block.put("subcategories", leaves);

        Map<String, Object> savingsBlock = node(savings, null, "0");
        savingsBlock.put("cost_vida_pla", new BigDecimal("300"));

        return Map.of("seccions", List.of(
                Map.of("tipus", "VARIABLE", "grups", List.of(block)),
                Map.of("tipus", "SAVINGS", "grups", List.of(savingsBlock))));
    }

    private static Map<String, Object> node(Category category, String plan, String real) {
        Map<String, Object> node = new HashMap<>();
        node.put("categoria", category);
        node.put("cost_vida_pla", plan != null ? new BigDecimal(plan) : BigDecimal.ZERO);
        node.put("caixa_real", new BigDecimal(real));
        if (plan != null) node.put("quantitat_limit", new BigDecimal(plan));
        node.put("subcategories", List.of());
        return node;
    }

    /** Sis mesos tancats, de maig a setembre… i abril. */
    private void givenSixMonths(Category category, String... values) {
        for (int index = 0; index < values.length; index++) {
            YearMonth month = YearMonth.of(2026, 4 + index);
            reals.computeIfAbsent(month, missing -> new HashMap<>()).put(category, values[index]);
        }
    }

    private void addTransaction(String type, String amount, String date, Category category) {
        Transaction transaction = new Transaction();
        transaction.setId((long) transactions.size() + 1);
        transaction.setType(type);
        transaction.setAmount(new BigDecimal(amount));
        transaction.setDate(LocalDate.parse(date));
        transaction.setCategory(category);
        transactions.add(transaction);
    }

    private RecurringTransaction recurring(String name, String type, String amount, String frequency, Category category) {
        RecurringTransaction recurring = new RecurringTransaction();
        recurring.setId((long) recurrings.size() + 1);
        recurring.setName(name);
        recurring.setType(type);
        recurring.setAmount(new BigDecimal(amount));
        recurring.setFrequency(frequency);
        recurring.setCategory(category);
        recurring.setActive(true);
        recurrings.add(recurring);
        return recurring;
    }

    @SuppressWarnings("unchecked")
    private static <T> T get(Map<String, Object> map, String key) {
        return (T) map.get(key);
    }

    // ============ ELS RECURRENTS ============

    @Test
    @DisplayName("Els recurrents de despesa vigents, passats a cost anual, del que més costa al que menys")
    void recurringPerYear() {
        recurring("Netflix", "EXPENSE", "12.99", "MENSUAL", netflix);
        recurring("Assegurança", "EXPENSE", "600.00", "ANUAL", null);
        recurring("Gimnàs", "EXPENSE", "60.00", "TRIMESTRAL", null);
        recurring("Nòmina", "INCOME", "2000.00", "MENSUAL", payroll);
        RecurringTransaction finished = recurring("Revista", "EXPENSE", "5.00", "MENSUAL", null);
        finished.setValidUntil(LocalDate.of(2026, 9, 30));

        Map<String, Object> recurringView = get(service.decisions(TODAY), "recurrents");
        List<Map<String, Object>> items = get(recurringView, "partides");

        assertThat(items).extracting(item -> item.get("nom")).containsExactly("Assegurança", "Gimnàs", "Netflix");
        assertThat((BigDecimal) items.get(2).get("anual")).isEqualByComparingTo("155.88");
        assertThat(items.get(2).get("bloc")).isEqualTo("Subscripcions");
        assertThat((BigDecimal) items.get(1).get("anual")).isEqualByComparingTo("240.00");
        assertThat((BigDecimal) recurringView.get("total_anual")).isEqualByComparingTo("995.88");
        assertThat((BigDecimal) recurringView.get("total_mensual")).isEqualByComparingTo("82.99");
    }

    // ============ EL QUE PUJA ============

    @Test
    @DisplayName("Una subcategoria que puja tres mesos seguits i ja és un 20 % i 10 € per sobre és una tendència; la resta, no")
    void trends() {
        givenSixMonths(delivery, "40", "45", "42", "50", "70", "95");
        // Puja tres mesos, però només un 10 %.
        givenSixMonths(restaurants, "100", "100", "100", "101", "105", "110");
        // Puja molt en %, però són 3 €.
        givenSixMonths(coffee, "2", "2", "2", "3", "4", "5");
        // Molt per sobre, però no puja cada mes.
        givenSixMonths(groceries, "200", "200", "200", "300", "290", "320");

        List<Map<String, Object>> trends = get(service.decisions(TODAY), "tendencies");

        assertThat(trends).extracting(trend -> trend.get("nom")).containsExactly("Delivery");
        Map<String, Object> trend = trends.get(0);
        assertThat(trend.get("bloc")).isEqualTo("Gast mensual");
        assertThat((BigDecimal) trend.get("abans")).isEqualByComparingTo("42.33");
        assertThat((BigDecimal) trend.get("ultim")).isEqualByComparingTo("95");
        assertThat((BigDecimal) trend.get("increment")).isEqualByComparingTo("52.67");
        assertThat((BigDecimal) trend.get("increment_percent")).isEqualByComparingTo("124");
        List<Map<String, Object>> months = get(trend, "mesos");
        assertThat(months).extracting(month -> month.get("periode"))
                .containsExactly("2026-04", "2026-05", "2026-06", "2026-07", "2026-08", "2026-09");
    }

    @Test
    @DisplayName("Amb menys de quatre mesos amb moviments no es pot dir que res pugi")
    void noTrendsWithoutHistory() {
        reals.put(YearMonth.of(2026, 7), Map.of(delivery, "50"));
        reals.put(YearMonth.of(2026, 8), Map.of(delivery, "70"));
        reals.put(YearMonth.of(2026, 9), Map.of(delivery, "95"));

        assertThat((List<?>) service.decisions(TODAY).get("tendencies")).isEmpty();
    }

    // ============ EL SIMULADOR I LES METES ============

    @Test
    @DisplayName("El simulador té les partides variables amb pla propi, el que s'hi gasta de mitjana, l'estalvi i els ingressos mitjans")
    void simulator() {
        plans.put(delivery, "60");
        plans.put(restaurants, "100");
        givenSixMonths(delivery, "40", "45", "42", "50", "70", "90");
        givenSixMonths(restaurants, "100", "100", "100", "120", "130", "140");
        for (String month : List.of("2026-07", "2026-08", "2026-09")) {
            addTransaction("INCOME", "2000.00", month + "-01", payroll);
            addTransaction("EXPENSE", "250.00", month + "-03", savings);
        }

        Map<String, Object> decisions = service.decisions(TODAY);
        Map<String, Object> simulator = get(decisions, "simulador");
        List<Map<String, Object>> items = get(simulator, "partides");

        assertThat(items).extracting(item -> item.get("nom")).containsExactly("Delivery", "Restaurants");
        assertThat((BigDecimal) items.get(0).get("pla")).isEqualByComparingTo("60");
        // Juliol, agost i setembre: (50 + 70 + 90) / 3.
        assertThat((BigDecimal) items.get(0).get("mitjana_real")).isEqualByComparingTo("70");
        assertThat((BigDecimal) simulator.get("estalvi_mensual")).isEqualByComparingTo("250");
        assertThat((BigDecimal) simulator.get("ingressos_mensuals")).isEqualByComparingTo("2000");
        assertThat((BigDecimal) simulator.get("estalvi_pla")).isEqualByComparingTo("300");
        assertThat(decisions.get("mesos_mostra")).isEqualTo(List.of("2026-09", "2026-08", "2026-07"));
        assertThat(decisions.get("mes_desti")).isEqualTo("2026-11");
    }

    @Test
    @DisplayName("Sense cap pla propi, el simulador ofereix les fulles variables on s'ha gastat alguna cosa")
    void simulatorWithoutPlans() {
        givenSixMonths(groceries, "300", "310", "290", "305", "295", "300");

        Map<String, Object> simulator = get(service.decisions(TODAY), "simulador");
        List<Map<String, Object>> items = get(simulator, "partides");

        assertThat(items).extracting(item -> item.get("nom")).containsExactly("Supermercat");
        assertThat(items.get(0).get("te_pla_propi")).isEqualTo(false);
    }

    @Test
    @DisplayName("Les metes pendents, per data, amb el que hi falta; les complertes no hi surten")
    void goals() {
        FinancialGoal trip = goal("Viatge al Japó", "3000", "1200", "2027-06-01", false);
        goal("Fons d'emergència", "5000", "5000", "2026-01-01", true);
        goals.add(trip);

        List<Map<String, Object>> pending = get(service.decisions(TODAY), "metes");

        assertThat(pending).extracting(goal -> goal.get("nom")).containsExactly("Viatge al Japó");
        assertThat((BigDecimal) pending.get(0).get("falta")).isEqualByComparingTo("1800");
        assertThat(pending.get(0).get("data_objectiu")).isEqualTo(LocalDate.of(2027, 6, 1));
    }

    private FinancialGoal goal(String name, String target, String saved, String date, boolean completed) {
        FinancialGoal goal = new FinancialGoal();
        goal.setId((long) goals.size() + 1);
        goal.setName(name);
        goal.setTargetAmount(new BigDecimal(target));
        goal.setCurrentAmount(new BigDecimal(saved));
        goal.setTargetDate(LocalDate.parse(date));
        goal.setCompleted(completed);
        if (completed) goals.add(goal);
        return goal;
    }
}
