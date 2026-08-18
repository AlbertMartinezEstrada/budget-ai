package com.budgetai.backend.integration;

import com.budgetai.backend.model.ImportRule;
import com.budgetai.backend.model.Transaction;
import com.budgetai.backend.repository.ImportRuleRepository;
import com.budgetai.backend.service.ImportRuleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regles que marquen soles els moviments en importar.
 *
 * El cas real: totes les entrades de Revolut que diuen "*9469" venen del
 * compte principal, i els diners ja es van comptar en sortir d'allà.
 */
class ImportRulesIntegrationTest extends AbstractIntegrationTest {

    @Autowired private ImportRuleRepository ruleRepository;
    @Autowired private ImportRuleService ruleService;

    @BeforeEach
    void setUp() {
        ruleRepository.deleteAll();
    }

    private void rule(String pattern, String category) {
        ImportRule rule = new ImportRule();
        rule.setPattern(pattern);
        rule.setMarksExcluded(true);
        rule.setCategoryName(category);
        ruleRepository.save(rule);
    }

    private Transaction movement(String concept) {
        Transaction t = new Transaction();
        t.setOriginalConcept(concept);
        t.setAmount(new BigDecimal("10.00"));
        t.setDate(LocalDate.of(2026, 8, 5));
        t.setType("INCOME");
        return t;
    }

    @Test
    @DisplayName("El patró marca el moviment com a ja comptat")
    void aMatchingRuleMarksTheMovement() {
        rule("*9469", null);

        List<Transaction> result = ruleService.apply(List.of(
                movement("Carregamento com Apple Pay através de *9469"),
                movement("Carregamento de ALBERT MARTINEZ ESTRADA")));

        assertThat(result.get(0).isExcludedFromBudget()).isTrue();
        assertThat(result.get(1).isExcludedFromBudget()).isFalse();
    }

    @Test
    @DisplayName("El patró no distingeix majúscules")
    void matchingIgnoresCase() {
        rule("PARA A CONTA DE INVESTIMENTO", null);

        assertThat(ruleService.apply(List.of(movement("Para a conta de investimento")))
                .get(0).isExcludedFromBudget()).isTrue();
    }

    @Test
    @DisplayName("Una regla pot assignar també la categoria")
    void aRuleCanSetTheCategory() {
        rule("*9469", "Trade Republic");

        assertThat(ruleService.apply(List.of(movement("Carregamento ... *9469")))
                .get(0).getCategoria()).isEqualTo("Trade Republic");
    }

    @Test
    @DisplayName("Una regla desactivada no fa res")
    void inactiveRulesAreIgnored() {
        ImportRule rule = new ImportRule();
        rule.setPattern("*9469");
        rule.setMarksExcluded(true);
        rule.setActive(false);
        ruleRepository.save(rule);

        assertThat(ruleService.apply(List.of(movement("Carregamento ... *9469")))
                .get(0).isExcludedFromBudget()).isFalse();
    }

    @Test
    @DisplayName("Es mira el concepte del banc, no l'empresa que hagi posat la IA")
    void rulesLookAtTheBankConcept() {
        rule("*9469", null);

        Transaction t = movement("Carregamento com Apple Pay através de *9469");
        // La IA reescriu l'empresa a alguna cosa neta; el patró no la ha de mirar.
        t.setEmpresa("Apple Pay");

        assertThat(ruleService.apply(List.of(t)).get(0).isExcludedFromBudget()).isTrue();
    }
}
