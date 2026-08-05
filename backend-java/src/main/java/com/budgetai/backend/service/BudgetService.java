package com.budgetai.backend.service;

import com.budgetai.backend.model.Budget;
import com.budgetai.backend.model.Transaction;
import com.budgetai.backend.repository.BudgetRepository;
import com.budgetai.backend.repository.TransactionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
public class BudgetService {

    @Autowired
    private BudgetRepository budgetRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    // El gasto acumulat es calcula sempre. Abans només s'omplia a
    // getActiveBudgetsForDate, de manera que el llistat general enviava
    // "gasto_actual" a null i la barra de progrés sortia sempre al 0%.
    public List<Budget> getAllBudgets() {
        return withCurrentSpent(budgetRepository.findAll());
    }

    public List<Budget> getActiveBudgets() {
        return withCurrentSpent(budgetRepository.findByActiveTrue());
    }

    private List<Budget> withCurrentSpent(List<Budget> budgets) {
        for (Budget budget : budgets) {
            budget.setCurrentSpent(calculateCurrentSpent(budget));
        }
        return budgets;
    }

    public List<Budget> getActiveBudgetsForDate(LocalDate date) {
        List<Budget> budgets = budgetRepository.findActiveBudgetsForDate(date);

        // Calcular el gasto actual para cada presupuesto
        for (Budget budget : budgets) {
            budget.setCurrentSpent(calculateCurrentSpent(budget));
        }

        return budgets;
    }

    public Optional<Budget> getBudgetById(Long id) {
        return budgetRepository.findById(id);
    }

    @Transactional
    public Budget createBudget(Budget budget) {
        return budgetRepository.save(budget);
    }

    @Transactional
    public Budget updateBudget(Long id, Budget updatedBudget) {
        return budgetRepository.findById(id)
                // Actualització parcial: un camp absent no ha de esborrar el valor desat.
                .map(budget -> {
                    if (updatedBudget.getCategory() != null) budget.setCategory(updatedBudget.getCategory());
                    if (updatedBudget.getLimitAmount() != null) budget.setLimitAmount(updatedBudget.getLimitAmount());
                    if (updatedBudget.getPeriodStart() != null) budget.setPeriodStart(updatedBudget.getPeriodStart());
                    if (updatedBudget.getPeriodEnd() != null) budget.setPeriodEnd(updatedBudget.getPeriodEnd());
                    if (updatedBudget.getActive() != null) budget.setActive(updatedBudget.getActive());
                    return budgetRepository.save(budget);
                })
                .orElseThrow(() -> new RuntimeException("Budget not found with id: " + id));
    }

    @Transactional
    public void deleteBudget(Long id) {
        budgetRepository.deleteById(id);
    }

    private BigDecimal calculateCurrentSpent(Budget budget) {
        if (budget.getCategory() == null) return BigDecimal.ZERO;

        List<Transaction> transactions = transactionRepository.findAll();

        return transactions.stream()
                .filter(t -> "EXPENSE".equals(t.getType()))
                .filter(t -> t.getCategory() != null && t.getCategory().getId().equals(budget.getCategory().getId()))
                .filter(t -> !t.getDate().isBefore(budget.getPeriodStart()) && !t.getDate().isAfter(budget.getPeriodEnd()))
                .map(t -> t.getAmount() != null ? t.getAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public BigDecimal getBudgetUsagePercentage(Budget budget) {
        BigDecimal limit = budget.getLimitAmount();
        if (limit == null || limit.signum() == 0) return BigDecimal.ZERO;

        return calculateCurrentSpent(budget)
                .multiply(BigDecimal.valueOf(100))
                .divide(limit, 2, RoundingMode.HALF_UP);
    }
}
