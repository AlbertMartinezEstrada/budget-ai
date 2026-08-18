package com.budgetai.backend.service;

import com.budgetai.backend.model.ImportRule;
import com.budgetai.backend.model.Transaction;
import com.budgetai.backend.repository.ImportRuleRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * Aplica les regles d'importació a un lot de moviments acabats de llegir.
 *
 * S'executa DESPRÉS de la classificació de la IA, a posta: una regla és una
 * decisió explícita de l'usuari i ha de manar sobre el que endevini el model.
 *
 * I s'executa abans de la pantalla de revisió, no en confirmar: així el que
 * la regla ha fet es veu, i es pot desfer fila a fila si en algun cas no toca.
 */
@Service
public class ImportRuleService {

    private final ImportRuleRepository ruleRepository;

    public ImportRuleService(ImportRuleRepository ruleRepository) {
        this.ruleRepository = ruleRepository;
    }

    public List<Transaction> apply(List<Transaction> transactions) {
        List<ImportRule> rules = ruleRepository.findByActiveTrue();
        if (rules.isEmpty()) return transactions;

        for (Transaction t : transactions) {
            for (ImportRule rule : rules) {
                if (!matches(rule, t)) continue;

                if (Boolean.TRUE.equals(rule.getMarksExcluded())) {
                    t.setExcludedFromBudget(true);
                }
                if (rule.getCategoryName() != null && !rule.getCategoryName().isBlank()) {
                    t.setCategoria(rule.getCategoryName());
                }
            }
        }
        return transactions;
    }

    /**
     * Es mira el concepte original i no l'empresa: l'empresa la pot haver
     * reescrit la IA, i el concepte és el que va posar el banc.
     */
    private boolean matches(ImportRule rule, Transaction transaction) {
        String pattern = rule.getPattern();
        String concept = transaction.getOriginalConcept();

        if (pattern == null || pattern.isBlank() || concept == null) return false;

        return concept.toLowerCase(Locale.ROOT).contains(pattern.toLowerCase(Locale.ROOT).trim());
    }
}
