package com.budgetai.backend.integration;

import com.budgetai.backend.controller.DebtController;
import com.budgetai.backend.controller.TransactionController;
import com.budgetai.backend.model.Account;
import com.budgetai.backend.model.Category;
import com.budgetai.backend.model.Debt;
import com.budgetai.backend.model.Transaction;
import com.budgetai.backend.repository.*;
import com.budgetai.backend.service.BudgetService;
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
 * Deutes amb la base de dades pel mig.
 *
 * L'escenari: el germà em deixa 1.000 € el setembre i li torno 100 € al mes a
 * partir del 5 d'octubre. L'entrada i les devolucions són moviments normals,
 * vinculats al deute; el que queda per tornar surt d'ells.
 */
class DebtIntegrationTest extends AbstractIntegrationTest {

    @Autowired private DebtController debtController;
    @Autowired private TransactionController transactionController;
    @Autowired private BudgetService budgetService;
    @Autowired private DebtRepository debtRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private RecurringTransactionRepository recurringRepository;
    @Autowired private BudgetRepository budgetRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private CategoryRepository categoryRepository;

    private Long accountId;
    private Long repaymentLeafId;

    @BeforeEach
    void setUp() {
        transactionRepository.deleteAll();
        debtRepository.deleteAll();
        recurringRepository.deleteAll();
        budgetRepository.deleteAll();
        accountRepository.deleteAll();
        categoryRepository.deleteAll();

        // L'alta manual cau a "Altres" quan no se li diu res.
        categoryRepository.save(new Category("Altres"));
        Category block = new Category("Deutes i préstecs");
        block.setCostType(Category.FIXED);
        block = categoryRepository.save(block);
        Category leaf = new Category("Pagament de deutes");
        leaf.setParentId(block.getId());
        leaf.setCostType(Category.FIXED);
        repaymentLeafId = categoryRepository.save(leaf).getId();

        Account account = new Account();
        account.setName("Compte Principal");
        account.setType("CORRIENTE");
        account.setCurrentBalance(new BigDecimal("500.00"));
        accountId = accountRepository.save(account).getId();
    }

    private Debt createDebt(boolean withInstallments) {
        Debt request = new Debt();
        request.setName("Germà, portàtil");
        request.setDirection(Debt.I_OWE);
        request.setAmount(new BigDecimal("1000.00"));
        request.setDate(LocalDate.of(2026, 9, 10));
        if (withInstallments) {
            request.setRepaymentPlan(Debt.PLAN_INSTALLMENTS);
            request.setInstallment(new BigDecimal("100.00"));
            request.setFrequency("MENSUAL");
            request.setFirstPaymentDate(LocalDate.of(2026, 10, 5));
            Category reference = new Category();
            reference.setId(repaymentLeafId);
            request.setCategory(reference);
        }
        ResponseEntity<?> response = debtController.create(request);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        return (Debt) response.getBody();
    }

    private ResponseEntity<?> recordMovement(String type, String amount, LocalDate date, Long debtId) {
        return recordMovement("Altres", type, amount, date, debtId);
    }

    private ResponseEntity<?> recordMovement(String category, String type, String amount, LocalDate date, Long debtId) {
        Transaction transaction = new Transaction();
        transaction.setType(type);
        transaction.setAmount(new BigDecimal(amount));
        transaction.setDate(date);
        transaction.setCategoryName(category);
        if (debtId != null) transaction.setDebtId(debtId);
        return transactionController.createTransaction(transaction);
    }

    private Long idOf(ResponseEntity<?> response) {
        return (Long) ((Map<?, ?>) response.getBody()).get("id");
    }

    private Debt reload(Long debtId) {
        return (Debt) debtController.get(debtId).getBody();
    }

    private BigDecimal balance() {
        return accountRepository.findById(accountId).orElseThrow().getCurrentBalance();
    }

    @Test
    @DisplayName("Les devolucions vinculades descompten del pendent; l'entrada del préstec no")
    void repaymentsReducePending() {
        Debt debt = createDebt(false);

        recordMovement("INCOME", "1000.00", LocalDate.of(2026, 9, 10), debt.getId());
        recordMovement("EXPENSE", "100.00", LocalDate.of(2026, 10, 5), debt.getId());
        recordMovement("EXPENSE", "150.00", LocalDate.of(2026, 11, 5), debt.getId());

        Debt described = reload(debt.getId());
        assertThat(described.getRepaid()).isEqualByComparingTo("250.00");
        assertThat(described.getPending()).isEqualByComparingTo("750.00");
        assertThat(described.getMovements()).hasSize(3);
        // Els saldos es mouen com qualsevol altre moviment.
        assertThat(balance()).isEqualByComparingTo("1250.00");
    }

    @Test
    @DisplayName("Editar un moviment el vincula, el conserva si no s'envia i el desvincula amb un negatiu")
    void editingLinksAndUnlinks() {
        Debt debt = createDebt(false);
        Long movementId = idOf(recordMovement("EXPENSE", "100.00", LocalDate.of(2026, 10, 5), null));

        Transaction link = new Transaction();
        link.setDebtId(debt.getId());
        transactionController.updateTransaction(movementId, link);
        assertThat(reload(debt.getId()).getRepaid()).isEqualByComparingTo("100.00");

        // Una edició que no parla del deute no el toca.
        Transaction rename = new Transaction();
        rename.setShortDescription("primera quota");
        transactionController.updateTransaction(movementId, rename);
        assertThat(reload(debt.getId()).getRepaid()).isEqualByComparingTo("100.00");

        Transaction unlink = new Transaction();
        unlink.setDebtId(-1L);
        transactionController.updateTransaction(movementId, unlink);
        assertThat(reload(debt.getId()).getRepaid()).isEqualByComparingTo("0");
        // I el saldo, ni desfet dues vegades ni aplicat dues vegades.
        assertThat(balance()).isEqualByComparingTo("400.00");
    }

    @Test
    @DisplayName("Un deute que no existeix es rebutja abans de moure cap saldo")
    void unknownDebtIsRejectedBeforeTouchingBalance() {
        ResponseEntity<?> response = recordMovement("EXPENSE", "100.00", LocalDate.of(2026, 10, 5), 999_999L);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(transactionRepository.findAll()).isEmpty();
        assertThat(balance()).isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("Esborrar un deute deixa els moviments i el saldo com estaven")
    void deletingDebtKeepsMovements() {
        Debt debt = createDebt(false);
        recordMovement("EXPENSE", "100.00", LocalDate.of(2026, 10, 5), debt.getId());

        debtController.delete(debt.getId());

        assertThat(debtRepository.findAll()).isEmpty();
        List<Transaction> movements = transactionRepository.findAll();
        assertThat(movements).hasSize(1);
        assertThat(movements.get(0).getDebt()).isNull();
        assertThat(balance()).isEqualByComparingTo("400.00");
    }

    @Test
    @DisplayName("Una forma de retorn incoherent arriba com un 400 amb el motiu")
    void invalidPlanIsABadRequest() {
        Debt request = new Debt();
        request.setName("Germà");
        request.setDirection(Debt.I_OWE);
        request.setAmount(new BigDecimal("1000.00"));
        request.setDate(LocalDate.of(2026, 9, 10));
        request.setRepaymentPlan(Debt.PLAN_INSTALLMENTS);
        request.setFirstPaymentDate(LocalDate.of(2026, 10, 5));

        ResponseEntity<?> response = debtController.create(request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().toString()).contains("quota");
        assertThat(debtRepository.findAll()).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> repaymentLeaf(int year, int month) {
        List<Map<String, Object>> groups =
                (List<Map<String, Object>>) budgetService.getMonthlySummary(year, month).get("grups");
        Map<String, Object> block = groups.stream()
                .filter(node -> "Deutes i préstecs".equals(((Category) node.get("categoria")).getName()))
                .findFirst()
                .orElseThrow();
        return ((List<Map<String, Object>>) block.get("subcategories")).get(0);
    }

    @Test
    @DisplayName("El pressupost reserva la quota pactada a la fulla, i el que s'ha tornat de debò és el gastat")
    void budgetReservesTheInstallment() {
        Debt debt = createDebt(true);
        recordMovement("Pagament de deutes", "EXPENSE", "100.00", LocalDate.of(2026, 10, 5), debt.getId());

        // El setembre encara no hi ha cap quota.
        assertThat((BigDecimal) repaymentLeaf(2026, 9).get("cost_vida_pla")).isEqualByComparingTo("0");

        Map<String, Object> october = repaymentLeaf(2026, 10);
        assertThat((BigDecimal) october.get("quotes_deutes")).isEqualByComparingTo("100.00");
        assertThat((BigDecimal) october.get("cost_vida_pla")).isEqualByComparingTo("100.00");
        assertThat((BigDecimal) october.get("caixa_real")).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("Quan el deute ja està saldat, les quotes que quedaven al calendari no es reserven")
    void settledDebtStopsReserving() {
        Debt debt = createDebt(true);
        recordMovement("EXPENSE", "1000.00", LocalDate.of(2026, 10, 5), debt.getId());

        assertThat((BigDecimal) repaymentLeaf(2026, 10).get("cost_vida_pla")).isEqualByComparingTo("100.00");
        assertThat((BigDecimal) repaymentLeaf(2026, 11).get("cost_vida_pla")).isEqualByComparingTo("0");
        assertThat(reload(debt.getId()).isSettled()).isTrue();
    }
}
