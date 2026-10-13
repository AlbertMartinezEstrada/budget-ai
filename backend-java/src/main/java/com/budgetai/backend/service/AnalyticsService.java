package com.budgetai.backend.service;

import com.budgetai.backend.repository.TransactionPartRepository;
import com.budgetai.backend.repository.TransactionRepository;
import com.budgetai.backend.service.TransactionLines.Line;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Ingressos i despeses de debò, per a la pantalla d'anàlisi.
 *
 * **Només compta el que és un ingrés o una despesa teus** (counts): ni els
 * traspassos entre comptes propis, que només mouen diners de lloc, ni el que
 * està marcat com a «no comptar». Abans es comptava tot el que s'havia mogut:
 * 1.276 € passats de Principal a Trade Republic sortien com a despesa i, si
 * se n'havia importat l'altre extracte, també com a ingrés.
 *
 * Es compta per línies (TransactionLines): d'un moviment dividit, cada part
 * va a la seva categoria i una part marcada com a «no comptar» no suma.
 *
 * **L'estalvi va a part** (total_savings): el que ha passat a un compte
 * d'estalvi o d'inversió, menys el que n'ha tornat. No és despesa —els diners
 * segueixen sent teus— i no surt a total_expense. Es compta per comptes i no
 * per categories: tot el que entra a Trade Republic s'ha apartat, sigui per a
 * Estalvis, per a un regal o per a l'assegurança. El que es paga després amb
 * aquests diners sí que és despesa.
 */
@Service
public class AnalyticsService {

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private TransactionPartRepository transactionPartRepository;

    /** Si una línia és un ingrés o una despesa de debò. */
    static boolean counts(Line line) {
        return !line.transaction().isTransfer() && !line.excludedFromBudget();
    }

    /** Totes les línies d'un període, comptin o no: l'estalvi surt dels traspassos. */
    private List<Line> linesBetween(LocalDate startDate, LocalDate endDate) {
        return TransactionLines.expand(transactionRepository.findAll(), transactionPartRepository.findAll())
                .stream()
                .filter(line -> line.isBetween(startDate, endDate))
                .toList();
    }

    private List<Line> countedLinesBetween(LocalDate startDate, LocalDate endDate) {
        return linesBetween(startDate, endDate).stream()
                .filter(AnalyticsService::counts)
                .toList();
    }

    /**
     * El que s'ha apartat: el que ha entrat als comptes d'estalvi i d'inversió
     * des dels del dia a dia, menys el que n'ha tornat. Sense el que està
     * marcat com a «no comptar».
     */
    static BigDecimal savedIn(List<Line> lines) {
        return lines.stream()
                .filter(line -> !line.excludedFromBudget())
                .map(line -> line.amount().multiply(
                        BigDecimal.valueOf(InternalTransferService.savingsDirection(line.transaction()))))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public Map<String, Object> getMonthlySummary(int year, int month) {
        LocalDate startDate = LocalDate.of(year, month, 1);
        LocalDate endDate = startDate.withDayOfMonth(startDate.lengthOfMonth());
        return summaryOf(linesBetween(startDate, endDate), YearMonth.of(year, month));
    }

    /** @param allLines les del període, comptin o no */
    private static Map<String, Object> summaryOf(List<Line> allLines, YearMonth period) {
        List<Line> lines = allLines.stream().filter(AnalyticsService::counts).toList();
        BigDecimal totalIncome = sumByType(lines, "INCOME");
        BigDecimal totalExpense = sumByType(lines, "EXPENSE");
        BigDecimal totalSavings = savedIn(allLines);

        // Moviments, no línies: un moviment dividit en quatre parts és un.
        // Per identitat, perquè un moviment encara sense desar no té id.
        Set<Object> transactions = Collections.newSetFromMap(new IdentityHashMap<>());
        lines.forEach(line -> transactions.add(line.transaction()));

        Map<String, Object> summary = new HashMap<>();
        summary.put("period", period.toString());
        summary.put("total_income", totalIncome);
        summary.put("total_expense", totalExpense);
        summary.put("total_savings", totalSavings);
        summary.put("savings_rate", rateOf(totalSavings, totalIncome));
        summary.put("balance", totalIncome.subtract(totalExpense));
        summary.put("transaction_count", transactions.size());
        return summary;
    }

    /** Quin percentatge dels ingressos s'ha apartat; null sense ingressos. */
    private static BigDecimal rateOf(BigDecimal saved, BigDecimal income) {
        if (income.signum() <= 0) return null;
        return saved.multiply(BigDecimal.valueOf(100)).divide(income, 2, RoundingMode.HALF_UP);
    }

    public List<Map<String, Object>> getCategoryBreakdown(int year, int month) {
        LocalDate startDate = LocalDate.of(year, month, 1);
        LocalDate endDate = startDate.withDayOfMonth(startDate.lengthOfMonth());

        List<Line> expenses = countedLinesBetween(startDate, endDate).stream()
                .filter(line -> "EXPENSE".equals(line.type()))
                .toList();

        Map<String, BigDecimal> categoryTotals = expenses.stream()
                .collect(Collectors.groupingBy(
                        line -> line.category() != null ? line.category().getName() : "Sin categoría",
                        Collectors.reducing(BigDecimal.ZERO, Line::amount, BigDecimal::add)
                ));

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
        List<Line> allLines = linesBetween(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31));
        List<Line> lines = allLines.stream().filter(AnalyticsService::counts).toList();

        BigDecimal totalIncome = sumByType(lines, "INCOME");
        BigDecimal totalExpense = sumByType(lines, "EXPENSE");
        BigDecimal totalSavings = savedIn(allLines);
        BigDecimal twelve = BigDecimal.valueOf(12);

        Map<String, Object> summary = new HashMap<>();
        summary.put("year", year);
        summary.put("total_income", totalIncome);
        summary.put("total_expense", totalExpense);
        summary.put("total_savings", totalSavings);
        summary.put("savings_rate", rateOf(totalSavings, totalIncome));
        summary.put("balance", totalIncome.subtract(totalExpense));
        summary.put("average_monthly_expense", totalExpense.divide(twelve, 2, RoundingMode.HALF_UP));
        summary.put("average_monthly_income", totalIncome.divide(twelve, 2, RoundingMode.HALF_UP));

        return summary;
    }

    private static BigDecimal sumByType(List<Line> lines, String type) {
        return lines.stream()
                .filter(line -> type.equals(line.type()))
                .map(line -> line.amount() != null ? line.amount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Els dotze mesos de l'any, llegint els moviments una sola vegada. */
    public List<Map<String, Object>> getMonthlyTrend(int year) {
        List<Line> lines = linesBetween(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31));

        List<Map<String, Object>> trend = new ArrayList<>();
        for (int month = 1; month <= 12; month++) {
            YearMonth period = YearMonth.of(year, month);
            trend.add(summaryOf(lines.stream()
                    .filter(line -> line.isBetween(period.atDay(1), period.atEndOfMonth()))
                    .toList(), period));
        }
        return trend;
    }
}
