package com.budgetai.backend.service;

import com.budgetai.backend.model.Transaction;
import com.budgetai.backend.repository.TransactionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

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

        Double totalIncome = transactions.stream()
                .filter(t -> "INCOME".equals(t.getType()))
                .mapToDouble(Transaction::getAmount)
                .sum();

        Double totalExpense = transactions.stream()
                .filter(t -> "EXPENSE".equals(t.getType()))
                .mapToDouble(Transaction::getAmount)
                .sum();

        Double balance = totalIncome - totalExpense;

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

        Map<String, Double> categoryTotals = expenses.stream()
                .collect(Collectors.groupingBy(
                        t -> t.getCategory() != null ? t.getCategory().getName() : "Sin categoría",
                        Collectors.summingDouble(Transaction::getAmount)
                ));

        Double totalExpense = categoryTotals.values().stream().mapToDouble(Double::doubleValue).sum();

        return categoryTotals.entrySet().stream()
                .map(entry -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("category", entry.getKey());
                    item.put("total", entry.getValue());
                    item.put("percentage", totalExpense > 0 ? (entry.getValue() / totalExpense) * 100 : 0);
                    return item;
                })
                .sorted((a, b) -> Double.compare((Double) b.get("total"), (Double) a.get("total")))
                .toList();
    }

    public Map<String, Object> getYearlySummary(int year) {
        LocalDate startDate = LocalDate.of(year, 1, 1);
        LocalDate endDate = LocalDate.of(year, 12, 31);

        List<Transaction> transactions = transactionRepository.findAll().stream()
                .filter(t -> !t.getDate().isBefore(startDate) && !t.getDate().isAfter(endDate))
                .toList();

        Double totalIncome = transactions.stream()
                .filter(t -> "INCOME".equals(t.getType()))
                .mapToDouble(Transaction::getAmount)
                .sum();

        Double totalExpense = transactions.stream()
                .filter(t -> "EXPENSE".equals(t.getType()))
                .mapToDouble(Transaction::getAmount)
                .sum();

        Map<String, Object> summary = new HashMap<>();
        summary.put("year", year);
        summary.put("total_income", totalIncome);
        summary.put("total_expense", totalExpense);
        summary.put("balance", totalIncome - totalExpense);
        summary.put("average_monthly_expense", totalExpense / 12);
        summary.put("average_monthly_income", totalIncome / 12);

        return summary;
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
