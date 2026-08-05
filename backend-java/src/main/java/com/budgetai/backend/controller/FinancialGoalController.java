package com.budgetai.backend.controller;

import com.budgetai.backend.model.FinancialGoal;
import com.budgetai.backend.service.FinancialGoalService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/goals")
public class FinancialGoalController {

    @Autowired
    private FinancialGoalService financialGoalService;

    @GetMapping
    public List<FinancialGoal> getAllGoals(@RequestParam(required = false) Boolean completed) {
        if (completed != null) {
            return completed ? financialGoalService.getCompletedGoals() : financialGoalService.getActiveGoals();
        }
        return financialGoalService.getAllGoals();
    }

    @GetMapping("/{id}")
    public ResponseEntity<FinancialGoal> getGoalById(@PathVariable Long id) {
        return financialGoalService.getGoalById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<FinancialGoal> createGoal(@RequestBody FinancialGoal goal) {
        FinancialGoal created = financialGoalService.createGoal(goal);
        return ResponseEntity.ok(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<FinancialGoal> updateGoal(@PathVariable Long id, @RequestBody FinancialGoal goal) {
        try {
            FinancialGoal updated = financialGoalService.updateGoal(id, goal);
            return ResponseEntity.ok(updated);
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{id}/add-amount")
    public ResponseEntity<FinancialGoal> addToGoal(@PathVariable Long id, @RequestBody Map<String, BigDecimal> payload) {
        try {
            BigDecimal amount = payload.get("amount");
            if (amount == null || amount.signum() <= 0) {
                return ResponseEntity.badRequest().build();
            }

            FinancialGoal updated = financialGoalService.addToGoal(id, amount);
            return ResponseEntity.ok(updated);
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteGoal(@PathVariable Long id) {
        financialGoalService.deleteGoal(id);
        return ResponseEntity.ok(Map.of("message", "Goal deleted successfully"));
    }
}
