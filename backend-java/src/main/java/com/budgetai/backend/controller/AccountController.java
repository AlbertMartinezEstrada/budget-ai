package com.budgetai.backend.controller;

import com.budgetai.backend.model.Account;
import com.budgetai.backend.service.AccountService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/accounts")
public class AccountController {

    @Autowired
    private AccountService accountService;

    @GetMapping
    public List<Account> getAllAccounts(@RequestParam(required = false, defaultValue = "false") boolean activeOnly) {
        if (activeOnly) {
            return accountService.getActiveAccounts();
        }
        return accountService.getAllAccounts();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Account> getAccountById(@PathVariable Long id) {
        return accountService.getAccountById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<Account> createAccount(@RequestBody Account account) {
        Account created = accountService.createAccount(account);
        return ResponseEntity.ok(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Account> updateAccount(@PathVariable Long id, @RequestBody Account account) {
        try {
            Account updated = accountService.updateAccount(id, account);
            return ResponseEntity.ok(updated);
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteAccount(@PathVariable Long id) {
        try {
            accountService.deleteAccount(id);
            return ResponseEntity.ok(Map.of("message", "Account deleted successfully"));
        } catch (IllegalStateException e) {
            // El compte té moviments o transferències: abans això petava com a
            // violació de clau forana i arribava com un 500 sense explicació.
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/adjust-balance")
    public ResponseEntity<Account> adjustBalance(@PathVariable Long id,
                                                   @RequestBody Map<String, Object> payload) {
        try {
            Object rawAmount = payload.get("amount");
            if (!(rawAmount instanceof Number)) {
                return ResponseEntity.badRequest().build();
            }
            // new BigDecimal(double) arrossega el soroll del binari; via String no.
            BigDecimal amount = new BigDecimal(rawAmount.toString());
            String operation = (String) payload.getOrDefault("operation", "ADD"); // ADD or SUBTRACT

            accountService.updateAccountBalance(id, amount, operation);

            Account updated = accountService.getAccountById(id)
                    .orElseThrow(() -> new RuntimeException("Account not found"));

            return ResponseEntity.ok(updated);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }
}
