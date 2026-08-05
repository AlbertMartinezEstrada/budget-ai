package com.budgetai.backend.controller;

import com.budgetai.backend.model.RecurringTransaction;
import com.budgetai.backend.service.RecurringTransactionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/recurring")
public class RecurringTransactionController {

    @Autowired
    private RecurringTransactionService recurringTransactionService;

    @GetMapping
    public List<RecurringTransaction> getAllRecurringTransactions(
            @RequestParam(required = false, defaultValue = "false") boolean activeOnly) {
        if (activeOnly) {
            return recurringTransactionService.getActiveRecurringTransactions();
        }
        return recurringTransactionService.getAllRecurringTransactions();
    }

    @GetMapping("/{id}")
    public ResponseEntity<RecurringTransaction> getRecurringTransactionById(@PathVariable Long id) {
        return recurringTransactionService.getRecurringTransactionById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<RecurringTransaction> createRecurringTransaction(@RequestBody RecurringTransaction recurring) {
        RecurringTransaction created = recurringTransactionService.createRecurringTransaction(recurring);
        return ResponseEntity.ok(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<RecurringTransaction> updateRecurringTransaction(@PathVariable Long id,
                                                                             @RequestBody RecurringTransaction recurring) {
        try {
            RecurringTransaction updated = recurringTransactionService.updateRecurringTransaction(id, recurring);
            return ResponseEntity.ok(updated);
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteRecurringTransaction(@PathVariable Long id) {
        recurringTransactionService.deleteRecurringTransaction(id);
        return ResponseEntity.ok(Map.of("message", "Recurring transaction deleted successfully"));
    }

    @PostMapping("/process")
    public ResponseEntity<?> processDueRecurringTransactions() {
        try {
            recurringTransactionService.processDueRecurringTransactions();
            return ResponseEntity.ok(Map.of("message", "Recurring transactions processed successfully"));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
