package com.budgetai.backend.controller;

import com.budgetai.backend.model.ImportRule;
import com.budgetai.backend.repository.AccountRepository;
import com.budgetai.backend.repository.ImportRuleRepository;
import com.budgetai.backend.service.InvalidInputException;
import com.budgetai.backend.service.NotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Regles que s'apliquen soles en importar un extracte. */
@RestController
@RequestMapping("/import-rules")
public class ImportRuleController {

    private final ImportRuleRepository ruleRepository;
    private final AccountRepository accountRepository;

    public ImportRuleController(ImportRuleRepository ruleRepository, AccountRepository accountRepository) {
        this.ruleRepository = ruleRepository;
        this.accountRepository = accountRepository;
    }

    @GetMapping
    public List<ImportRule> list() {
        return ruleRepository.findAll();
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody ImportRule rule) {
        if (rule.getPattern() == null || rule.getPattern().isBlank()) {
            throw new InvalidInputException("rules.pattern-empty");
        }
        if (rule.getTransferAccount() != null) {
            Long accountId = rule.getTransferAccount().getId();
            rule.setTransferAccount(accountRepository.findById(accountId)
                    .orElseThrow(() -> new NotFoundException("not-found.account", accountId)));
            // Si un traspàs compta ho decideixen els comptes, no la regla.
            rule.setMarksExcluded(false);
        }
        return ResponseEntity.ok(ruleRepository.save(rule));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        if (!ruleRepository.existsById(id)) throw new NotFoundException("not-found.rule", id);

        ruleRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
