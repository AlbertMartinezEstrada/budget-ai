package com.budgetai.backend.controller;

import com.budgetai.backend.model.Account;
import com.budgetai.backend.service.AccountService;
import com.budgetai.backend.service.NotFoundException;
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
                .orElseThrow(() -> new NotFoundException("el compte", id));
    }

    @PostMapping
    public ResponseEntity<Account> createAccount(@RequestBody Account account) {
        Account created = accountService.createAccount(account);
        return ResponseEntity.ok(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Account> updateAccount(@PathVariable Long id, @RequestBody Account account) {
        // Sense try/catch: abans qualsevol error, fos el que fos, es tornava
        // com un 404 buit i la pantalla deia "Not Found". El gestor global
        // dona a cada error el seu codi i el seu missatge.
        return ResponseEntity.ok(accountService.updateAccount(id, account));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteAccount(@PathVariable Long id) {
        try {
            accountService.deleteAccount(id);
            return ResponseEntity.ok(Map.of("message", "Account deleted successfully"));
        } catch (IllegalStateException exception) {
            // El compte té moviments o transferències: abans això petava com a
            // violació de clau forana i arribava com un 500 sense explicació.
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", exception.getMessage()));
        }
    }

    @PostMapping("/{id}/adjust-balance")
    public ResponseEntity<Account> adjustBalance(@PathVariable Long id,
                                                   @RequestBody Map<String, Object> payload) {
        Object rawAmount = payload.get("amount");
        if (!(rawAmount instanceof Number)) {
            throw new IllegalArgumentException("L'import ha de ser un número.");
        }
        // new BigDecimal(double) arrossega el soroll del binari; via String no.
        BigDecimal amount = new BigDecimal(rawAmount.toString());
        String operation = String.valueOf(payload.getOrDefault("operation", "ADD")); // ADD or SUBTRACT

        // Sense try/catch: abans qualsevol error tornava un 400 buit. El
        // servei és @Transactional, així que si falla ja ha fet el rollback.
        accountService.updateAccountBalance(id, amount, operation);

        return ResponseEntity.ok(accountService.getAccountById(id)
                .orElseThrow(() -> new NotFoundException("el compte", id)));
    }
}
