package com.budgetai.backend.controller;

import com.budgetai.backend.model.ImportRule;
import com.budgetai.backend.repository.ImportRuleRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Regles que s'apliquen soles en importar un extracte. */
@RestController
@RequestMapping("/import-rules")
public class ImportRuleController {

    private final ImportRuleRepository ruleRepository;

    public ImportRuleController(ImportRuleRepository ruleRepository) {
        this.ruleRepository = ruleRepository;
    }

    @GetMapping
    public List<ImportRule> list() {
        return ruleRepository.findAll();
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody ImportRule rule) {
        if (rule.getPattern() == null || rule.getPattern().isBlank()) {
            return ResponseEntity.badRequest().body("El patró no pot estar buit.");
        }
        return ResponseEntity.ok(ruleRepository.save(rule));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        if (!ruleRepository.existsById(id)) return ResponseEntity.notFound().build();

        ruleRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
