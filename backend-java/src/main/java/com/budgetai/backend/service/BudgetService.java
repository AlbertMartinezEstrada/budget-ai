package com.budgetai.backend.service;

import com.budgetai.backend.model.Budget;
import com.budgetai.backend.model.Transaction;
import com.budgetai.backend.repository.BudgetRepository;
import com.budgetai.backend.repository.TransactionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
public class BudgetService {

    @Autowired
    private BudgetRepository budgetRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    public List<Budget> getAllBudgets() {
        return budgetRepository.findAll();
    }

    public List<Budget> getActiveBudgets() {
        return budgetRepository.findByActiveTrue();
    }

    public List<Budget> getActiveBudgetsForDate(LocalDate date) {
        List<Budget> budgets = budgetRepository.findActiveBudgetsForDate(date);

        // Calcular el gasto actual para cada presupuesto
        for (Budget budget : budgets) {
            Double currentSpent = calculateCurrentSpent(budget);
            budget.setCurrentSpent(currentSpent);
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
                .map(budget -> {
                    budget.setCategory(updatedBudget.getCategory());
                    budget.setLimitAmount(updatedBudget.getLimitAmount());
                    budget.setPeriodStart(updatedBudget.getPeriodStart());
                    budget.setPeriodEnd(updatedBudget.getPeriodEnd());
                    budget.setActive(updatedBudget.getActive());
                    return budgetRepository.save(budget);
                })
                .orElseThrow(() -> new RuntimeException("Budget not found with id: " + id));
    }

    @Transactional
    public void deleteBudget(Long id) {
        budgetRepository.deleteById(id);
    }

    private Double calculateCurrentSpent(Budget budget) {
        List<Transaction> transactions = transactionRepository.findAll();

        return transactions.stream()
                .filter(t -> "EXPENSE".equals(t.getType()))
                .filter(t -> t.getCategory() != null && t.getCategory().getId().equals(budget.getCategory().getId()))
                .filter(t -> !t.getDate().isBefore(budget.getPeriodStart()) && !t.getDate().isAfter(budget.getPeriodEnd()))
                .mapToDouble(Transaction::getAmount)
                .sum();
    }

    public Double getBudgetUsagePercentage(Budget budget) {
        Double currentSpent = calculateCurrentSpent(budget);
        if (budget.getLimitAmount() == 0) return 0.0;
        return (currentSpent / budget.getLimitAmount()) * 100;
    }
}
