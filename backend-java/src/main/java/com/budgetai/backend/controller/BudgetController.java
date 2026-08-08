package com.budgetai.backend.controller;

import com.budgetai.backend.model.Budget;
import com.budgetai.backend.model.MonthlyIncome;
import com.budgetai.backend.service.BudgetService;
import com.budgetai.backend.service.IncomeBaseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/budgets")
public class BudgetController {

    @Autowired
    private BudgetService budgetService;

    @Autowired
    private IncomeBaseService incomeBaseService;

    @GetMapping
    public List<Budget> getAllBudgets(@RequestParam(required = false, defaultValue = "false") boolean activeOnly) {
        if (activeOnly) {
            return budgetService.getActiveBudgets();
        }
        return budgetService.getAllBudgets();
    }

    @GetMapping("/current")
    public List<Budget> getCurrentBudgets(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate targetDate = date != null ? date : LocalDate.now();
        return budgetService.getActiveBudgetsForDate(targetDate);
    }

    /**
     * Resum del mes per grups i subcategories, amb cost de vida i caixa.
     *
     * Va abans de /{id} a propòsit: si estigués després, Spring intentaria
     * interpretar "monthly-summary" com un identificador.
     */
    @GetMapping("/monthly-summary")
    public Map<String, Object> getMonthlySummary(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {

        LocalDate now = LocalDate.now();
        return budgetService.getMonthlySummary(
                year != null ? year : now.getYear(),
                month != null ? month : now.getMonthValue());
    }

    /**
     * Sou d'un mes concret, quan no és el de sempre.
     * El sou per defecte viu a la configuració (`expectedMonthlyIncome`).
     */
    @GetMapping("/monthly-income")
    public List<MonthlyIncome> getMonthlyIncomes() {
        return incomeBaseService.listOverrides();
    }

    @PutMapping("/monthly-income/{period}")
    public ResponseEntity<?> setMonthlyIncome(@PathVariable String period,
                                              @RequestBody MonthlyIncome body) {
        if (!period.matches("\\d{4}-\\d{2}")) {
            return ResponseEntity.badRequest().body(Map.of("error", "El període ha de ser YYYY-MM"));
        }
        if (body.getAmount() == null || body.getAmount().signum() < 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "L'import ha de ser positiu"));
        }
        return ResponseEntity.ok(
                incomeBaseService.saveOverride(period, body.getAmount(), body.getNotes()));
    }

    @DeleteMapping("/monthly-income/{period}")
    public ResponseEntity<?> deleteMonthlyIncome(@PathVariable String period) {
        incomeBaseService.deleteOverride(period);
        return ResponseEntity.ok(Map.of("message", "Sou del mes esborrat; s'aplicarà el per defecte"));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Budget> getBudgetById(@PathVariable Long id) {
        return budgetService.getBudgetById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<Budget> createBudget(@RequestBody Budget budget) {
        Budget created = budgetService.createBudget(budget);
        return ResponseEntity.ok(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Budget> updateBudget(@PathVariable Long id, @RequestBody Budget budget) {
        try {
            Budget updated = budgetService.updateBudget(id, budget);
            return ResponseEntity.ok(updated);
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteBudget(@PathVariable Long id) {
        budgetService.deleteBudget(id);
        return ResponseEntity.ok(Map.of("message", "Budget deleted successfully"));
    }
}
