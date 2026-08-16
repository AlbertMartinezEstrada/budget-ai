package com.budgetai.backend.integration;

import com.budgetai.backend.controller.TransactionController;
import com.budgetai.backend.model.Account;
import com.budgetai.backend.model.Category;
import com.budgetai.backend.model.Settings;
import com.budgetai.backend.model.Transaction;
import com.budgetai.backend.repository.*;
import com.budgetai.backend.service.BudgetService;
import com.budgetai.backend.service.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Diners que es mouen entre comptes propis.
 *
 * L'escenari és el real: 100 € surten del compte principal cap a Revolut i
 * allà es compren accions. Són 100 € de despesa, no 200, i l'entrada a Revolut
 * no són diners nous.
 *
 * La regla: un cop surten del principal ja estan comptats. El que facin
 * després mou saldos però no torna a comptar al pressupost.
 */
class TraspassosIntegrationTest extends AbstractIntegrationTest {

    @Autowired private TransactionController transactionController;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private BudgetRepository budgetRepository;
    @Autowired private RecurringTransactionRepository recurringRepository;
    @Autowired private MonthlyIncomeRepository monthlyIncomeRepository;
    @Autowired private BudgetService budgetService;
    @Autowired private SettingsService settingsService;

    private Account principal;
    private Account revolut;

    @BeforeEach
    void setUp() {
        // L'ordre importa: les recurrents apunten a categories i a comptes, i
        // esborrar-los abans que elles peta contra la clau forana.
        transactionRepository.deleteAll();
        recurringRepository.deleteAll();
        budgetRepository.deleteAll();
        monthlyIncomeRepository.deleteAll();
        accountRepository.deleteAll();
        categoryRepository.deleteAll();

        Settings settings = new Settings();
        settings.setExpectedMonthlyIncome(new BigDecimal("2000.00"));
        settingsService.updateSettings(settings);

        categoryRepository.save(new Category("Altres"));
        Category risc = new Category("Inversió de risc");
        risc.setCostType(Category.VARIABLE);
        categoryRepository.save(risc);

        principal = saveAccount("Compte Principal", "1000.00");
        revolut = saveAccount("Revolut", "0.00");
    }

    private Account saveAccount(String name, String balance) {
        Account account = new Account();
        account.setName(name);
        account.setType("CORRIENTE");
        account.setCurrentBalance(new BigDecimal(balance));
        return accountRepository.save(account);
    }

    private Transaction movement(String concept, String amount, String type,
                                 Account account, boolean excluded) {
        Transaction t = new Transaction();
        t.setOriginalConcept(concept);
        t.setCompanyName(concept);
        t.setCategoryName("Inversió de risc");
        t.setAmount(new BigDecimal(amount));
        t.setDate(LocalDate.of(2026, 3, 10));
        t.setType(type);
        t.setAccount(account);
        t.setExcludedFromBudget(excluded);
        return t;
    }

    private BigDecimal balanceOf(Account account) {
        return accountRepository.findById(account.getId()).orElseThrow().getCurrentBalance();
    }

    /** El que el resum mensual compta com a despesa a "Inversió de risc". */
    private BigDecimal riskSpending() {
        Map<String, Object> summary = budgetService.getMonthlySummary(2026, 3);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> groups = (List<Map<String, Object>>) summary.get("grups");

        return groups.stream()
                .filter(g -> "Inversió de risc".equals(((Category) g.get("categoria")).getName()))
                .map(g -> (BigDecimal) g.get("cost_vida_real"))
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("Els 100 € traspassats i invertits compten una vegada, no dues")
    void theSameMoneyIsCountedOnce() {
        transactionController.confirmUpload(List.of(
                // Surt del principal: aquí és on es compta.
                movement("Traspàs a Revolut", "100.00", "EXPENSE", principal, false),
                // Arriba a Revolut: no són diners nous.
                movement("Entrada des de Principal", "100.00", "INCOME", revolut, true),
                // I s'inverteixen: és el mateix diner del primer moviment.
                movement("Compra accions", "100.00", "EXPENSE", revolut, true)
        ));

        // Sense la marca sortien 200 € de despesa on només n'hi ha 100.
        assertThat(riskSpending()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("Una entrada per traspàs no eixampla el bot a repartir")
    void anIncomingTransferIsNotNewMoney() {
        transactionController.confirmUpload(List.of(
                movement("Entrada des de Principal", "100.00", "INCOME", revolut, true)
        ));

        Map<String, Object> summary = budgetService.getMonthlySummary(2026, 3);

        // Amb la capçalera sortint de la suma d'ingressos, comptar-la hauria
        // donat 2.100 € per repartir amb els mateixos diners que ja hi eren.
        assertThat((BigDecimal) summary.get("ingressos_reals")).isEqualByComparingTo("0");
        assertThat((BigDecimal) summary.get("total_disponible")).isEqualByComparingTo("2000.00");
    }

    @Test
    @DisplayName("Un moviment exclòs mou el saldo del seu compte igualment")
    void excludedMovementsStillMoveTheBalance() {
        transactionController.confirmUpload(List.of(
                movement("Traspàs a Revolut", "100.00", "EXPENSE", principal, false),
                movement("Entrada des de Principal", "100.00", "INCOME", revolut, true)
        ));

        // Cada extracte és la veritat del seu compte: els diners hi han passat
        // de debò encara que no comptin al pressupost.
        assertThat(balanceOf(principal)).isEqualByComparingTo("900.00");
        assertThat(balanceOf(revolut)).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("Les dues potes d'un traspàs no es prenen per un duplicat")
    void bothLegsOfATransferAreKept() {
        // Mateix import i mateix dia a dos comptes: mirant només el hash, la
        // segona es descartava en silenci i el compte destí es quedava sense.
        ResponseEntity<?> response = transactionController.confirmUpload(List.of(
                movement("Traspàs", "100.00", "EXPENSE", principal, false),
                movement("Traspàs", "100.00", "INCOME", revolut, true)
        ));

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body.get("saved")).isEqualTo(2);
        assertThat(transactionRepository.findAll()).hasSize(2);
    }

    @Test
    @DisplayName("Confirmar dues vegades el mateix extracte segueix sense duplicar")
    void thePerAccountGuardStillCatchesRealDuplicates() {
        List<Transaction> batch = List.of(
                movement("Traspàs", "100.00", "EXPENSE", principal, false));

        transactionController.confirmUpload(batch);
        transactionController.confirmUpload(List.of(
                movement("Traspàs", "100.00", "EXPENSE", principal, false)));

        // Ampliar la identitat amb el compte no ha d'obrir la porta als
        // duplicats de debò, que són del mateix compte.
        assertThat(transactionRepository.findAll()).hasSize(1);
        assertThat(balanceOf(principal)).isEqualByComparingTo("900.00");
    }

    @Test
    @DisplayName("Cada moviment es queda al compte que li toca")
    void movementsKeepTheirAccount() {
        transactionController.confirmUpload(List.of(
                movement("Traspàs a Revolut", "100.00", "EXPENSE", principal, false),
                movement("Compra accions", "40.00", "EXPENSE", revolut, true)
        ));

        assertThat(transactionRepository.findAll())
                .filteredOn(t -> "Revolut".equals(t.getAccount().getName()))
                .singleElement()
                .extracting(Transaction::getOriginalConcept)
                .isEqualTo("Compra accions");
    }
}
