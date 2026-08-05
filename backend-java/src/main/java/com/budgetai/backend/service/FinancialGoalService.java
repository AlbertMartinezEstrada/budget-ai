package com.budgetai.backend.service;

import com.budgetai.backend.model.FinancialGoal;
import com.budgetai.backend.repository.FinancialGoalRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Service
public class FinancialGoalService {

    @Autowired
    private FinancialGoalRepository financialGoalRepository;

    public List<FinancialGoal> getAllGoals() {
        return financialGoalRepository.findAllByOrderByTargetDateAsc();
    }

    public List<FinancialGoal> getActiveGoals() {
        return financialGoalRepository.findByCompletedFalse();
    }

    public List<FinancialGoal> getCompletedGoals() {
        return financialGoalRepository.findByCompletedTrue();
    }

    public Optional<FinancialGoal> getGoalById(Long id) {
        return financialGoalRepository.findById(id);
    }

    @Transactional
    public FinancialGoal createGoal(FinancialGoal goal) {
        return financialGoalRepository.save(goal);
    }

    @Transactional
    public FinancialGoal updateGoal(Long id, FinancialGoal updatedGoal) {
        return financialGoalRepository.findById(id)
                // Actualització parcial: el formulari no envia "quantitat_actual",
                // i copiar-ho a cegues esborrava els diners ja estalviats.
                .map(goal -> {
                    if (updatedGoal.getName() != null) goal.setName(updatedGoal.getName());
                    if (updatedGoal.getDescription() != null) goal.setDescription(updatedGoal.getDescription());
                    if (updatedGoal.getTargetAmount() != null) goal.setTargetAmount(updatedGoal.getTargetAmount());
                    if (updatedGoal.getCurrentAmount() != null) goal.setCurrentAmount(updatedGoal.getCurrentAmount());
                    if (updatedGoal.getTargetDate() != null) goal.setTargetDate(updatedGoal.getTargetDate());
                    if (updatedGoal.getCompleted() != null) goal.setCompleted(updatedGoal.getCompleted());
                    if (updatedGoal.getAccount() != null) goal.setAccount(updatedGoal.getAccount());
                    return financialGoalRepository.save(goal);
                })
                .orElseThrow(() -> new RuntimeException("Financial goal not found with id: " + id));
    }

    @Transactional
    public FinancialGoal addToGoal(Long id, BigDecimal amount) {
        return financialGoalRepository.findById(id)
                .map(goal -> {
                    BigDecimal current = goal.getCurrentAmount() != null
                            ? goal.getCurrentAmount()
                            : BigDecimal.ZERO;
                    goal.setCurrentAmount(current.add(amount));

                    // Marcar como completado si se alcanzó el objetivo
                    if (goal.getTargetAmount() != null
                            && goal.getCurrentAmount().compareTo(goal.getTargetAmount()) >= 0) {
                        goal.setCompleted(true);
                    }

                    return financialGoalRepository.save(goal);
                })
                .orElseThrow(() -> new RuntimeException("Financial goal not found with id: " + id));
    }

    @Transactional
    public void deleteGoal(Long id) {
        financialGoalRepository.deleteById(id);
    }
}
