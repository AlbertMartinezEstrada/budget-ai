package com.budgetai.backend.integration;

import com.budgetai.backend.controller.DebtController;
import com.budgetai.backend.controller.TransactionController;
import com.budgetai.backend.model.Account;
import com.budgetai.backend.model.Category;
import com.budgetai.backend.model.Debt;
import com.budgetai.backend.model.Transaction;
import com.budgetai.backend.model.TransactionPart;
import com.budgetai.backend.repository.*;
import com.budgetai.backend.service.AnalyticsService;
import com.budgetai.backend.service.BudgetService;
import com.budgetai.backend.service.CategoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Un moviment dividit en parts, amb la base de dades pel mig.
 *
 * L'escenari és el que va fer falta: la transferència de 500 € a Trade
 * Republic és alhora
 *
 *   300 € d'estalvi                        Trade Republic
 *   100 € que tornen un autopréstec        Pagament de deutes, vinculats al deute
 *    60 € guardats per a l'assegurança     Assegurances
 *    40 € que no han de comptar            Trade Republic, exclosos
 */
class TransactionPartsIntegrationTest extends AbstractIntegrationTest {

    private static final LocalDate TRANSFER_DATE = LocalDate.of(2026, 10, 1);

    @Autowired private TransactionController transactionController;
    @Autowired private DebtController debtController;
    @Autowired private BudgetService budgetService;
    @Autowired private AnalyticsService analyticsService;
    @Autowired private CategoryService categoryService;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private TransactionPartRepository partRepository;
    @Autowired private DebtRepository debtRepository;
    @Autowired private RecurringTransactionRepository recurringRepository;
    @Autowired private BudgetRepository budgetRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private CategoryRepository categoryRepository;

    private Long accountId;
    private Long savingsId;
    private Long repaymentsId;
    private Long insuranceId;
    private Long otherId;
    private Long selfLoanId;
    private Long transferId;

    @BeforeEach
    void setUp() {
        transactionRepository.deleteAll();
        debtRepository.deleteAll();
        recurringRepository.deleteAll();
        budgetRepository.deleteAll();
        accountRepository.deleteAll();
        categoryRepository.deleteAll();

        otherId = categoryRepository.save(new Category("Altres")).getId();
        Category savings = new Category("Trade Republic");
        savings.setCostType(Category.VARIABLE);
        savingsId = categoryRepository.save(savings).getId();
        repaymentsId = leafIn(block("Deutes i préstecs"), "Pagament de deutes");
        insuranceId = leafIn(block("Assegurances i salut"), "Assegurances");

        Account account = new Account();
        account.setName("Compte Principal");
        account.setType("CORRIENTE");
        account.setCurrentBalance(new BigDecimal("1000.00"));
        accountId = accountRepository.save(account).getId();

        Debt selfLoan = new Debt();
        selfLoan.setName("Autopréstec Trade Republic");
        selfLoan.setDirection(Debt.I_OWE);
        selfLoan.setAmount(new BigDecimal("600.00"));
        selfLoan.setDate(LocalDate.of(2026, 9, 1));
        selfLoanId = ((Debt) debtController.create(selfLoan).getBody()).getId();

        Transaction transfer = new Transaction();
        transfer.setType("EXPENSE");
        transfer.setAmount(new BigDecimal("500.00"));
        transfer.setDate(TRANSFER_DATE);
        transfer.setCategoryName("Trade Republic");
        transfer.setCompanyName("Trade Republic");
        transferId = (Long) ((Map<?, ?>) transactionController.createTransaction(transfer).getBody()).get("id");
    }

    private Long block(String name) {
        Category block = new Category(name);
        block.setCostType(Category.FIXED);
        return categoryRepository.save(block).getId();
    }

    private Long leafIn(Long blockId, String name) {
        Category leaf = new Category(name);
        leaf.setParentId(blockId);
        leaf.setCostType(Category.FIXED);
        return categoryRepository.save(leaf).getId();
    }

    private static TransactionPart part(String amount, Long categoryId, boolean excluded, Long debtId) {
        TransactionPart part = new TransactionPart();
        part.setAmount(new BigDecimal(amount));
        Category reference = new Category();
        reference.setId(categoryId);
        part.setCategory(reference);
        part.setExcludedFromBudget(excluded);
        if (debtId != null) part.setDebtId(debtId);
        return part;
    }

    private ResponseEntity<?> splitAsInTheExample() {
        return transactionController.replaceParts(transferId, List.of(
                part("300.00", savingsId, false, null),
                part("100.00", repaymentsId, false, selfLoanId),
                part("60.00", insuranceId, false, null),
                part("40.00", savingsId, true, null)));
    }

    /** El que s'ha gastat de debò a una fulla aquell mes, segons el resum del pressupost. */
    @SuppressWarnings("unchecked")
    private BigDecimal spentIn(String categoryName) {
        List<Map<String, Object>> pending = new ArrayList<>(
                (List<Map<String, Object>>) budgetService.getMonthlySummary(2026, 10).get("grups"));
        while (!pending.isEmpty()) {
            Map<String, Object> node = pending.remove(0);
            if (categoryName.equals(((Category) node.get("categoria")).getName())) {
                return (BigDecimal) node.get("caixa_real");
            }
            pending.addAll((List<Map<String, Object>>) node.get("subcategories"));
        }
        throw new AssertionError("No hi ha cap node " + categoryName);
    }

    private BigDecimal balance() {
        return accountRepository.findById(accountId).orElseThrow().getCurrentBalance();
    }

    @Test
    @DisplayName("Cada part compta a la seva categoria, la exclosa no compta enlloc i el saldo no es torna a moure")
    void eachPartCountsWhereItBelongs() {
        assertThat(spentIn("Trade Republic")).isEqualByComparingTo("500.00");

        ResponseEntity<?> response = splitAsInTheExample();

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(((Transaction) response.getBody()).getParts()).hasSize(4);
        // 300 d'estalvi; els 40 exclosos no hi sumen.
        assertThat(spentIn("Trade Republic")).isEqualByComparingTo("300.00");
        assertThat(spentIn("Pagament de deutes")).isEqualByComparingTo("100.00");
        assertThat(spentIn("Assegurances")).isEqualByComparingTo("60.00");
        // Dividir no és cap moviment nou: el saldo ja es va moure pel total.
        assertThat(balance()).isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("La part vinculada al deute en descompta només el seu import")
    void linkedPartRepaysTheDebt() {
        splitAsInTheExample();

        Debt selfLoan = (Debt) debtController.get(selfLoanId).getBody();

        assertThat(selfLoan.getRepaid()).isEqualByComparingTo("100.00");
        assertThat(selfLoan.getPending()).isEqualByComparingTo("500.00");
        assertThat(selfLoan.getMovements()).hasSize(1);
        assertThat(selfLoan.getMovements().get(0).part()).isTrue();
    }

    @Test
    @DisplayName("Unes parts que no sumen el total es rebutgen i no canvien res")
    void partsThatDoNotAddUpAreRejected() {
        ResponseEntity<?> response = transactionController.replaceParts(transferId, List.of(
                part("300.00", savingsId, false, null),
                part("150.00", insuranceId, false, null)));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().toString()).contains("en falten 50.00 €");
        assertThat(partRepository.findAll()).isEmpty();
        assertThat(spentIn("Trade Republic")).isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("Un moviment dividit no canvia d'import sense canviar les parts, i el saldo no es toca")
    void splitTransactionKeepsItsAmount() {
        splitAsInTheExample();

        Transaction changes = new Transaction();
        changes.setAmount(new BigDecimal("600.00"));
        ResponseEntity<?> response = transactionController.updateTransaction(transferId, changes);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(balance()).isEqualByComparingTo("500.00");
        assertThat(transactionRepository.findById(transferId).orElseThrow().getAmount())
                .isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("Treure la divisió torna a comptar el moviment sencer a la seva categoria")
    void removingTheSplitRestoresTheTransaction() {
        splitAsInTheExample();

        transactionController.replaceParts(transferId, List.of());

        assertThat(partRepository.findAll()).isEmpty();
        assertThat(spentIn("Trade Republic")).isEqualByComparingTo("500.00");
        assertThat(spentIn("Pagament de deutes")).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("Esborrar el moviment s'emporta les parts i desfà el saldo")
    void deletingTheTransactionDeletesItsParts() {
        splitAsInTheExample();

        transactionController.deleteTransaction(transferId);

        assertThat(partRepository.findAll()).isEmpty();
        assertThat(balance()).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("El filtre per categoria troba un moviment dividit per les seves parts, no per la categoria d'abans")
    void categoryFilterLooksAtParts() {
        Transaction groceries = new Transaction();
        groceries.setType("EXPENSE");
        groceries.setAmount(new BigDecimal("20.00"));
        groceries.setDate(TRANSFER_DATE);
        groceries.setCategoryName("Altres");
        transactionController.createTransaction(groceries);
        splitAsInTheExample();

        List<Transaction> insurance = transactionController.getTransactions(insuranceId, null, null, null, null, null);
        List<Transaction> other = transactionController.getTransactions(otherId, null, null, null, null, null);

        assertThat(insurance).extracting(Transaction::getId).containsExactly(transferId);
        assertThat(insurance.get(0).getParts()).hasSize(4);
        assertThat(other).hasSize(1);
        assertThat(other.get(0).getParts()).isEmpty();
    }

    @Test
    @DisplayName("L'anàlisi reparteix la despesa per parts")
    void analyticsBreakdownUsesParts() {
        splitAsInTheExample();

        Map<String, BigDecimal> byCategory = new java.util.HashMap<>();
        for (Map<String, Object> item : analyticsService.getCategoryBreakdown(2026, 10)) {
            byCategory.put((String) item.get("category"), (BigDecimal) item.get("total"));
        }

        // L'anàlisi ensenya tot el que s'ha mogut, exclòs inclòs, com fins ara.
        assertThat(byCategory.get("Trade Republic")).isEqualByComparingTo("340.00");
        assertThat(byCategory.get("Pagament de deutes")).isEqualByComparingTo("100.00");
        assertThat(byCategory.get("Assegurances")).isEqualByComparingTo("60.00");
    }

    @Test
    @DisplayName("No es pot esborrar una categoria que només fan servir les parts")
    void categoryUsedByPartsCannotBeDeleted() {
        splitAsInTheExample();

        assertThatThrownBy(() -> categoryService.delete(insuranceId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("moviments associats");
    }
}
