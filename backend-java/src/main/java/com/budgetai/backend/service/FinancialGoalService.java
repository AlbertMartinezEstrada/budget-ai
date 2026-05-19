package com.budgetai.backend.service;

import com.budgetai.backend.model.FinancialGoal;
import com.budgetai.backend.repository.FinancialGoalRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
                .map(goal -> {
                    goal.setName(updatedGoal.getName());
                    goal.setDescription(updatedGoal.getDescription());
                    goal.setTargetAmount(updatedGoal.getTargetAmount());
                    goal.setCurrentAmount(updatedGoal.getCurrentAmount());
                    goal.setTargetDate(updatedGoal.getTargetDate());
                    goal.setCompleted(updatedGoal.getCompleted());
                    goal.setAccount(updatedGoal.getAccount());
                    return financialGoalRepository.save(goal);
                })
                .orElseThrow(() -> new RuntimeException("Financial goal not found with id: " + id));
    }

    @Transactional
    public FinancialGoal addToGoal(Long id, Double amount) {
        return financialGoalRepository.findById(id)
                .map(goal -> {
                    goal.setCurrentAmount(goal.getCurrentAmount() + amount);

                    // Marcar como completado si se alcanzó el objetivo
                    if (goal.getCurrentAmount() >= goal.getTargetAmount()) {
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
