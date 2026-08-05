package com.budgetai.backend.service;

import com.budgetai.backend.model.Transaction;
import com.budgetai.backend.repository.TransactionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class AnalyticsService {

    @Autowired
    private TransactionRepository transactionRepository;

    public Map<String, Object> getMonthlySummary(int year, int month) {
        LocalDate startDate = LocalDate.of(year, month, 1);
        LocalDate endDate = startDate.withDayOfMonth(startDate.lengthOfMonth());

        List<Transaction> transactions = transactionRepository.findAll().stream()
                .filter(t -> !t.getDate().isBefore(startDate) && !t.getDate().isAfter(endDate))
                .toList();

        BigDecimal totalIncome = sumByType(transactions, "INCOME");
        BigDecimal totalExpense = sumByType(transactions, "EXPENSE");
        BigDecimal balance = totalIncome.subtract(totalExpense);

        Map<String, Object> summary = new HashMap<>();
        summary.put("period", YearMonth.of(year, month).toString());
        summary.put("total_income", totalIncome);
        summary.put("total_expense", totalExpense);
        summary.put("balance", balance);
        summary.put("transaction_count", transactions.size());

        return summary;
    }

    public List<Map<String, Object>> getCategoryBreakdown(int year, int month) {
        LocalDate startDate = LocalDate.of(year, month, 1);
        LocalDate endDate = startDate.withDayOfMonth(startDate.lengthOfMonth());

        List<Transaction> expenses = transactionRepository.findAll().stream()
                .filter(t -> "EXPENSE".equals(t.getType()))
                .filter(t -> !t.getDate().isBefore(startDate) && !t.getDate().isAfter(endDate))
                .toList();

        Map<String, BigDecimal> categoryTotals = expenses.stream()
                .collect(Collectors.groupingBy(
                        t -> t.getCategory() != null ? t.getCategory().getName() : "Sin categoría",
                        Collectors.reducing(BigDecimal.ZERO, AnalyticsService::amountOf, BigDecimal::add)
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
                .sorted((a, b) -> ((BigDecimal) b.get("total")).compareTo((BigDecimal) a.get("total")))
                .toList();
    }

    public Map<String, Object> getYearlySummary(int year) {
        LocalDate startDate = LocalDate.of(year, 1, 1);
        LocalDate endDate = LocalDate.of(year, 12, 31);

        List<Transaction> transactions = transactionRepository.findAll().stream()
                .filter(t -> !t.getDate().isBefore(startDate) && !t.getDate().isAfter(endDate))
                .toList();

        BigDecimal totalIncome = sumByType(transactions, "INCOME");
        BigDecimal totalExpense = sumByType(transactions, "EXPENSE");
        BigDecimal twelve = BigDecimal.valueOf(12);

        Map<String, Object> summary = new HashMap<>();
        summary.put("year", year);
        summary.put("total_income", totalIncome);
        summary.put("total_expense", totalExpense);
        summary.put("balance", totalIncome.subtract(totalExpense));
        summary.put("average_monthly_expense", totalExpense.divide(twelve, 2, RoundingMode.HALF_UP));
        summary.put("average_monthly_income", totalIncome.divide(twelve, 2, RoundingMode.HALF_UP));

        return summary;
    }

    private static BigDecimal sumByType(List<Transaction> transactions, String type) {
        return transactions.stream()
                .filter(t -> type.equals(t.getType()))
                .map(AnalyticsService::amountOf)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal amountOf(Transaction t) {
        return t.getAmount() != null ? t.getAmount() : BigDecimal.ZERO;
    }

    public List<Map<String, Object>> getMonthlyTrend(int year) {
        List<Map<String, Object>> trend = new ArrayList<>();

        for (int month = 1; month <= 12; month++) {
            Map<String, Object> monthData = getMonthlySummary(year, month);
            trend.add(monthData);
        }

        return trend;
    }
}
