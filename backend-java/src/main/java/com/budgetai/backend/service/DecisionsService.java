package com.budgetai.backend.service;

import com.budgetai.backend.model.Category;
import com.budgetai.backend.model.FinancialGoal;
import com.budgetai.backend.model.RecurringTransaction;
import com.budgetai.backend.repository.FinancialGoalRepository;
import com.budgetai.backend.repository.RecurringTransactionRepository;
import com.budgetai.backend.repository.TransactionPartRepository;
import com.budgetai.backend.repository.TransactionRepository;
import com.budgetai.backend.service.BudgetAdviceService.MonthReals;
import com.budgetai.backend.service.TransactionLines.Line;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/**
 * La pestanya Decisions de l'anàlisi: què passa si canvio alguna cosa, què em
 * costen a l'any les coses fixes i què s'està disparant.
 *
 *   RECURRENTS   els recurrents de despesa vigents, passats a cost anual: 3 €
 *                al mes no semblen res, 36 € a l'any ja fan pensar.
 *   TENDÈNCIES   les subcategories variables que pugen tres mesos seguits i
 *                ja són clarament per sobre del que eren.
 *   SIMULADOR    les dades perquè el navegador simuli canvis al pla: les
 *                partides variables amb el seu pla i el que s'hi gasta, el que
 *                s'estalvia de mitjana i les metes. La simulació es fa al
 *                navegador, que respon a l'instant; aplicar-la fa servir
 *                POST /budgets/apply-changes, com les propostes.
 *
 * Com la resta de l'anàlisi, no té fórmula pròpia: el pla i el real surten del
 * resum mensual del pressupost, i l'estalvi i els ingressos, de MoneyFlows.
 * Els mesos sense cap moviment (els d'abans de fer servir l'aplicació) no
 * compten.
 */
@Service
public class DecisionsService {

    /** Quants mesos tancats es miren enrere. */
    static final int LOOKBACK_MONTHS = 6;
    /** Quants mesos amb moviments fan la mitjana del simulador. */
    static final int AVERAGE_MONTHS = 3;
    /** Una tendència necessita els tres mesos que pugen i almenys un d'abans per comparar. */
    static final int MIN_TREND_MONTHS = 4;
    /** L'últim mes ha de ser almenys un 20 % per sobre del d'abans… */
    static final BigDecimal TREND_SHARE = new BigDecimal("1.20");
    /** …i almenys 10 € més: passar de 2 a 3 € no és cap tendència. */
    static final BigDecimal TREND_MINIMUM = new BigDecimal("10.00");

    private static final String VARIABLE = "VARIABLE";
    private static final String SAVINGS = "SAVINGS";

    @Autowired private BudgetService budgetService;
    @Autowired private CategoryHierarchyService hierarchyService;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private TransactionPartRepository transactionPartRepository;
    @Autowired private RecurringTransactionRepository recurringRepository;
    @Autowired private FinancialGoalRepository goalRepository;

    public Map<String, Object> decisions() {
        return decisions(LocalDate.now());
    }

    Map<String, Object> decisions(LocalDate today) {
        YearMonth current = YearMonth.from(today);
        CategoryHierarchyService.Tree tree = hierarchyService.loadTree();
        MoneyFlows flows = new MoneyFlows(tree);
        List<Line> lines = TransactionLines.expand(transactionRepository.findAll(), transactionPartRepository.findAll());

        // Els mesos tancats amb moviments, del més nou al més vell.
        List<MonthReals> sample = new ArrayList<>();
        for (int back = 1; back <= LOOKBACK_MONTHS; back++) {
            YearMonth month = current.minusMonths(back);
            MonthReals reals = MonthReals.of(month, summaryOf(month));
            if (reals.hasMovements()) sample.add(reals);
        }
        List<MonthReals> recent = sample.subList(0, Math.min(AVERAGE_MONTHS, sample.size()));
        Map<String, Object> currentSummary = summaryOf(current);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("periode", current.toString());
        result.put("mes_desti", current.plusMonths(1).toString());
        result.put("mesos_mostra", recent.stream().map(month -> month.period().toString()).toList());
        result.put("recurrents", recurring(tree, today));
        result.put("tendencies", trends(currentSummary, sample));
        result.put("simulador", simulator(currentSummary, recent, flows, lines));
        result.put("metes", goals());
        return result;
    }

    private Map<String, Object> summaryOf(YearMonth month) {
        return budgetService.getMonthlySummary(month.getYear(), month.getMonthValue());
    }

    // ============ ELS RECURRENTS, A L'ANY ============

    /**
     * Els recurrents de despesa vigents avui, del que més costa a l'any al que
     * menys, amb el bloc de la categoria per agrupar-los (Subscripcions,
     * Llar…).
     */
    private Map<String, Object> recurring(CategoryHierarchyService.Tree tree, LocalDate today) {
        List<Map<String, Object>> items = new ArrayList<>();
        BigDecimal yearly = BigDecimal.ZERO;
        for (RecurringTransaction recurring : recurringRepository.findByActiveTrue()) {
            if (!"EXPENSE".equals(recurring.getType())) continue;
            if (!recurring.isValidDuring(today, today)) continue;
            BigDecimal monthly = recurring.getMonthlyAmount();
            BigDecimal perYear = monthly.multiply(BigDecimal.valueOf(12));
            Category category = recurring.getCategory();
            Category block = category != null ? tree.rootOf(category.getId()) : null;

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", recurring.getId());
            item.put("nom", recurring.getName());
            item.put("categoria", category != null ? category.getName() : null);
            item.put("bloc", block != null ? block.getName() : null);
            item.put("frequencia", recurring.getFrequency());
            item.put("import", recurring.getAmount());
            item.put("mensual", monthly);
            item.put("anual", perYear);
            item.put("proxima_data", recurring.getNextDate());
            items.add(item);
            yearly = yearly.add(perYear);
        }
        items.sort(Comparator.comparing((Map<String, Object> item) -> (BigDecimal) item.get("anual")).reversed());

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("total_anual", yearly);
        view.put("total_mensual", yearly.divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP));
        view.put("partides", items);
        return view;
    }

    // ============ EL QUE PUJA ============

    /**
     * Les subcategories variables que han pujat els tres últims mesos tancats
     * (cada mes més que l'anterior) i on l'últim és almenys un 20 % i 10 € per
     * sobre de la mitjana dels mesos d'abans. De la que més ha pujat a la que
     * menys.
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> trends(Map<String, Object> currentSummary, List<MonthReals> sample) {
        if (sample.size() < MIN_TREND_MONTHS) return List.of();
        List<Map<String, Object>> trends = new ArrayList<>();
        for (Map<String, Object> block : sectionNodes(currentSummary, VARIABLE)) {
            String blockName = ((Category) block.get("categoria")).getName();
            for (Map<String, Object> leaf : leavesOf(block)) {
                Map<String, Object> trend = trendOf((Category) leaf.get("categoria"), blockName, sample);
                if (trend != null) trends.add(trend);
            }
        }
        trends.sort(Comparator.comparing((Map<String, Object> trend) -> (BigDecimal) trend.get("increment")).reversed());
        return trends;
    }

    static Map<String, Object> trendOf(Category category, String blockName, List<MonthReals> sample) {
        // Del més vell al més nou.
        List<MonthReals> chronological = new ArrayList<>(sample);
        Collections.reverse(chronological);
        List<BigDecimal> reals = chronological.stream().map(month -> month.realOf(category.getId())).toList();
        int size = reals.size();
        BigDecimal first = reals.get(size - 3);
        BigDecimal second = reals.get(size - 2);
        BigDecimal last = reals.get(size - 1);
        if (first.compareTo(second) >= 0 || second.compareTo(last) >= 0) return null;

        List<BigDecimal> before = reals.subList(0, size - 3);
        BigDecimal baseline = before.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(before.size()), 2, RoundingMode.HALF_UP);
        BigDecimal increment = last.subtract(baseline);
        if (last.compareTo(baseline.multiply(TREND_SHARE)) < 0 || increment.compareTo(TREND_MINIMUM) < 0) return null;

        List<Map<String, Object>> months = new ArrayList<>();
        for (int index = 0; index < size; index++) {
            months.add(Map.of("periode", chronological.get(index).period().toString(), "real", reals.get(index)));
        }
        Map<String, Object> trend = new LinkedHashMap<>();
        trend.put("categoria_id", category.getId());
        trend.put("nom", category.getName());
        trend.put("bloc", blockName);
        trend.put("mesos", months);
        trend.put("abans", baseline);
        trend.put("ultim", last);
        trend.put("increment", increment);
        trend.put("increment_percent", baseline.signum() > 0
                ? increment.multiply(BigDecimal.valueOf(100)).divide(baseline, 0, RoundingMode.HALF_UP)
                : null);
        return trend;
    }

    // ============ EL SIMULADOR ============

    /**
     * Les partides que es poden moure (les variables amb un pla posat a mà,
     * com a les propostes; si no n'hi ha cap, les fulles variables on s'ha
     * gastat alguna cosa), el que s'estalvia i s'ingressa de mitjana, i el
     * pla d'estalvi del mes.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> simulator(Map<String, Object> currentSummary, List<MonthReals> recent,
                                                 MoneyFlows flows, List<Line> lines) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> block : sectionNodes(currentSummary, VARIABLE)) {
            collectPlanned(block, ((Category) block.get("categoria")).getName(), recent, items);
        }
        if (items.isEmpty()) {
            for (Map<String, Object> block : sectionNodes(currentSummary, VARIABLE)) {
                String blockName = ((Category) block.get("categoria")).getName();
                for (Map<String, Object> leaf : leavesOf(block)) {
                    Map<String, Object> item = itemOf(leaf, blockName, recent);
                    if (((BigDecimal) item.get("mitjana_real")).signum() > 0) items.add(item);
                }
            }
        }

        BigDecimal savings = BigDecimal.ZERO;
        BigDecimal income = BigDecimal.ZERO;
        for (MonthReals month : recent) {
            Map<String, Object> summary = AnalyticsService.summaryOf(flows, lines,
                    month.period().atDay(1), month.period().atEndOfMonth());
            savings = savings.add((BigDecimal) summary.get("total_savings"));
            income = income.add((BigDecimal) summary.get("total_income"));
        }
        BigDecimal count = BigDecimal.valueOf(Math.max(recent.size(), 1));

        BigDecimal savingsPlan = BigDecimal.ZERO;
        for (Map<String, Object> block : sectionNodes(currentSummary, SAVINGS)) {
            savingsPlan = savingsPlan.add(amount(block.get("cost_vida_pla")));
        }

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("estalvi_mensual", recent.isEmpty() ? null : savings.divide(count, 2, RoundingMode.HALF_UP));
        view.put("ingressos_mensuals", recent.isEmpty() ? null : income.divide(count, 2, RoundingMode.HALF_UP));
        view.put("estalvi_pla", savingsPlan);
        view.put("partides", items);
        return view;
    }

    @SuppressWarnings("unchecked")
    private static void collectPlanned(Map<String, Object> node, String blockName, List<MonthReals> recent,
                                       List<Map<String, Object>> items) {
        if (node.get("quantitat_limit") != null || node.get("percentatge") != null) {
            items.add(itemOf(node, blockName, recent));
        }
        for (Map<String, Object> child : (List<Map<String, Object>>) node.getOrDefault("subcategories", List.of())) {
            collectPlanned(child, blockName, recent, items);
        }
    }

    private static Map<String, Object> itemOf(Map<String, Object> node, String blockName, List<MonthReals> recent) {
        Category category = (Category) node.get("categoria");
        BigDecimal average = recent.isEmpty() ? BigDecimal.ZERO
                : recent.stream().map(month -> month.realOf(category.getId())).reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(recent.size()), 2, RoundingMode.HALF_UP);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("categoria_id", category.getId());
        item.put("nom", category.getName());
        item.put("bloc", blockName);
        item.put("pla", amount(node.get("cost_vida_pla")));
        item.put("mitjana_real", average);
        item.put("te_pla_propi", node.get("quantitat_limit") != null || node.get("percentatge") != null);
        // Per aplicar un canvi a un pla per percentatge sense convertir-lo en import.
        item.put("percentatge", node.get("percentatge"));
        item.put("base", node.get("base_assignacio"));
        return item;
    }

    // ============ LES METES ============

    /** Les metes que encara no s'han complert, per data objectiu. */
    private List<Map<String, Object>> goals() {
        List<Map<String, Object>> goals = new ArrayList<>();
        for (FinancialGoal goal : goalRepository.findAllByOrderByTargetDateAsc()) {
            if (Boolean.TRUE.equals(goal.getCompleted())) continue;
            BigDecimal target = amount(goal.getTargetAmount());
            BigDecimal saved = amount(goal.getCurrentAmount());
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", goal.getId());
            view.put("nom", goal.getName());
            view.put("objectiu", target);
            view.put("actual", saved);
            view.put("falta", target.subtract(saved).max(BigDecimal.ZERO));
            view.put("data_objectiu", goal.getTargetDate());
            goals.add(view);
        }
        return goals;
    }

    // ============ AJUDES ============

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> sectionNodes(Map<String, Object> summary, String type) {
        for (Map<String, Object> section : (List<Map<String, Object>>) summary.get("seccions")) {
            if (type.equals(section.get("tipus"))) return (List<Map<String, Object>>) section.get("grups");
        }
        return List.of();
    }

    /** Les fulles d'un node del resum; ell mateix si no en té. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> leavesOf(Map<String, Object> node) {
        List<Map<String, Object>> children = (List<Map<String, Object>>) node.getOrDefault("subcategories", List.of());
        if (children.isEmpty()) return List.of(node);
        List<Map<String, Object>> leaves = new ArrayList<>();
        for (Map<String, Object> child : children) leaves.addAll(leavesOf(child));
        return leaves;
    }

    private static BigDecimal amount(Object value) {
        return value instanceof BigDecimal decimal ? decimal : BigDecimal.ZERO;
    }
}
