package com.budgetai.backend.service;

import com.budgetai.backend.repository.TransactionPartRepository;
import com.budgetai.backend.repository.TransactionRepository;
import com.budgetai.backend.service.MoneyFlows.Kind;
import com.budgetai.backend.service.TransactionLines.Line;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/**
 * Ingressos, despeses i estalvi, per a l'anàlisi i el tauler.
 *
 * **Què és cada línia ho decideix MoneyFlows**, el mateix que fa servir el
 * pressupost: el que va a Estalvi i inversió és estalvi i no despesa, estigui
 * marcat com a traspàs o no; els traspassos entre comptes del dia a dia i el
 * que està marcat com a «no comptar» no són res. Abans l'anàlisi tenia el seu
 * criteri (comptava l'estalvi per comptes) i el tauler un altre, i els 300 €
 * apartats a Estalvis sortien com a despesa en un lloc i com a estalvi en
 * l'altre.
 *
 * Es compta per línies (TransactionLines): d'un moviment dividit, cada part
 * va a la seva categoria i una part marcada com a «no comptar» no suma.
 */
@Service
public class AnalyticsService {

    /** Les categories amb més despesa que ensenya el tauler. */
    static final int TOP_CATEGORIES = 5;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private TransactionPartRepository transactionPartRepository;

    @Autowired
    private CategoryHierarchyService hierarchyService;

    private List<Line> allLines() {
        return TransactionLines.expand(transactionRepository.findAll(), transactionPartRepository.findAll());
    }

    private MoneyFlows flows() {
        return new MoneyFlows(hierarchyService.loadTree());
    }

    public Map<String, Object> getMonthlySummary(int year, int month) {
        YearMonth period = YearMonth.of(year, month);
        Map<String, Object> summary = summaryOf(flows(), allLines(), period.atDay(1), period.atEndOfMonth());
        summary.put("period", period.toString());
        return summary;
    }

    /**
     * El resum de tot el que hi ha, per al tauler, amb les categories on més
     * s'ha gastat. Les mateixes xifres que l'anàlisi d'un mes, però sense dates.
     */
    public Map<String, Object> getOverallSummary() {
        MoneyFlows flows = flows();
        List<Line> lines = allLines();
        Map<String, Object> summary = summaryOf(flows, lines, LocalDate.MIN, LocalDate.MAX);
        summary.put("top_categories", breakdownOf(flows, lines, LocalDate.MIN, LocalDate.MAX).stream()
                .limit(TOP_CATEGORIES)
                .toList());
        return summary;
    }

    /**
     * total_income, total_expense, total_savings (el que s'ha apartat, menys el
     * que n'ha tornat), spent_from_savings (la part de la despesa pagada des
     * d'un compte d'estalvi), savings_rate, balance (ingressos menys despeses)
     * i unassigned (el que no s'ha destinat a res).
     *
     * El fa servir també la pestanya General (GeneralAnalysisService): el
     * resum d'un període ha de ser el mateix a tot arreu.
     */
    static Map<String, Object> summaryOf(MoneyFlows flows, List<Line> allLines,
                                                 LocalDate from, LocalDate to) {
        List<Line> lines = allLines.stream().filter(line -> line.isBetween(from, to)).toList();
        BigDecimal totalIncome = flows.sum(lines, Kind.INCOME, from, to);
        BigDecimal totalExpense = flows.sum(lines, Kind.EXPENSE, from, to);
        BigDecimal totalSavings = flows.sum(lines, Kind.SAVING, from, to);

        // Moviments, no línies: un moviment dividit en quatre parts és un.
        // Per identitat, perquè un moviment encara sense desar no té id.
        Set<Object> transactions = Collections.newSetFromMap(new IdentityHashMap<>());
        lines.stream()
                .filter(line -> flows.of(line).kind() != Kind.NONE)
                .forEach(line -> transactions.add(line.transaction()));

        Map<String, Object> summary = new HashMap<>();
        summary.put("total_income", totalIncome);
        summary.put("total_expense", totalExpense);
        summary.put("total_savings", totalSavings);
        summary.put("spent_from_savings", flows.spentFromFunds(lines, from, to));
        summary.put("savings_rate", rateOf(totalSavings, totalIncome));
        summary.put("balance", totalIncome.subtract(totalExpense));
        summary.put("unassigned", flows.unassigned(lines, from, to));
        summary.put("transaction_count", transactions.size());
        return summary;
    }

    /** Quin percentatge dels ingressos s'ha apartat; null sense ingressos. */
    private static BigDecimal rateOf(BigDecimal saved, BigDecimal income) {
        if (income.signum() <= 0) return null;
        return saved.multiply(BigDecimal.valueOf(100)).divide(income, 2, RoundingMode.HALF_UP);
    }

    public List<Map<String, Object>> getCategoryBreakdown(int year, int month) {
        YearMonth period = YearMonth.of(year, month);
        return breakdownOf(flows(), allLines(), period.atDay(1), period.atEndOfMonth());
    }

    /**
     * La despesa per categories, de més a menys. Una categoria on ha tornat
     * més del que s'hi ha gastat no hi surt: no és despesa.
     */
    private static List<Map<String, Object>> breakdownOf(MoneyFlows flows, List<Line> lines,
                                                         LocalDate from, LocalDate to) {
        Map<String, BigDecimal> categoryTotals = new HashMap<>();
        for (Line line : lines) {
            if (!line.isBetween(from, to)) continue;
            MoneyFlows.Flow flow = flows.of(line);
            if (flow.kind() != Kind.EXPENSE) continue;
            String name = line.category() != null ? line.category().getName() : "Sin categoría";
            categoryTotals.merge(name, flow.amount(), BigDecimal::add);
        }
        categoryTotals.values().removeIf(total -> total.signum() <= 0);

        BigDecimal totalExpense = categoryTotals.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return categoryTotals.entrySet().stream()
                .map(entry -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("category", entry.getKey());
                    item.put("total", entry.getValue());
                    item.put("percentage", totalExpense.signum() > 0
                            ? entry.getValue()
                                .multiply(BigDecimal.valueOf(100))
                                .divide(totalExpense, 2, RoundingMode.HALF_UP)
                            : BigDecimal.ZERO);
                    return item;
                })
                .sorted((firstCategory, secondCategory) -> ((BigDecimal) secondCategory.get("total")).compareTo((BigDecimal) firstCategory.get("total")))
                .toList();
    }

    public Map<String, Object> getYearlySummary(int year) {
        Map<String, Object> summary = summaryOf(flows(), allLines(),
                LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31));
        BigDecimal twelve = BigDecimal.valueOf(12);

        summary.put("year", year);
        summary.remove("transaction_count");
        summary.put("average_monthly_expense", ((BigDecimal) summary.get("total_expense")).divide(twelve, 2, RoundingMode.HALF_UP));
        summary.put("average_monthly_income", ((BigDecimal) summary.get("total_income")).divide(twelve, 2, RoundingMode.HALF_UP));
        return summary;
    }

    /** Els dotze mesos de l'any, llegint els moviments una sola vegada. */
    public List<Map<String, Object>> getMonthlyTrend(int year) {
        MoneyFlows flows = flows();
        List<Line> lines = allLines().stream()
                .filter(line -> line.isBetween(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31)))
                .toList();

        List<Map<String, Object>> trend = new ArrayList<>();
        for (int month = 1; month <= 12; month++) {
            YearMonth period = YearMonth.of(year, month);
            Map<String, Object> summary = summaryOf(flows, lines, period.atDay(1), period.atEndOfMonth());
            summary.put("period", period.toString());
            trend.add(summary);
        }
        return trend;
    }
}
