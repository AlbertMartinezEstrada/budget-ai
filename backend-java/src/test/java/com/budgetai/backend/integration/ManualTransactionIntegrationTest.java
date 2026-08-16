package com.budgetai.backend.integration;

import com.budgetai.backend.controller.TransactionController;
import com.budgetai.backend.model.Account;
import com.budgetai.backend.model.Category;
import com.budgetai.backend.model.Transaction;
import com.budgetai.backend.repository.AccountRepository;
import com.budgetai.backend.repository.CategoryRepository;
import com.budgetai.backend.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Alta manual d'un moviment: efectiu, un préstec, el que no surt de l'extracte.
 *
 * Passa pel mateix codi que la confirmació d'una importació —lligar categoria,
 * empresa i compte, i moure el saldo—, però amb dues diferències que aquests
 * tests fixen: no porta hash de verificació i no inventa saldo resultant.
 */
class ManualTransactionIntegrationTest extends AbstractIntegrationTest {

    @Autowired private TransactionController transactionController;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private CategoryRepository categoryRepository;

    private Long accountId;

    @BeforeEach
    void setUp() {
        transactionRepository.deleteAll();
        accountRepository.deleteAll();

        if (categoryRepository.findByName("Altres").isEmpty()) {
            categoryRepository.save(new Category("Altres"));
        }

        Account account = new Account();
        account.setName("Compte Principal");
        account.setType("CORRIENTE");
        account.setCurrentBalance(new BigDecimal("1000.00"));
        accountId = accountRepository.save(account).getId();
    }

    private Transaction manual(String amount, String type) {
        Transaction t = new Transaction();
        t.setAmount(new BigDecimal(amount));
        t.setDate(LocalDate.of(2026, 8, 14));
        t.setType(type);
        t.setCategoryName("Altres");
        t.setCompanyName("Bar de la cantonada");
        return t;
    }

    private BigDecimal balance() {
        return accountRepository.findById(accountId).orElseThrow().getCurrentBalance();
    }

    @Test
    @DisplayName("Una despesa manual resta del saldo")
    void manualExpenseSubtracts() {
        transactionController.createTransaction(manual("12.50", "EXPENSE"));

        assertThat(transactionRepository.findAll()).hasSize(1);
        assertThat(balance()).isEqualByComparingTo("987.50");
    }

    @Test
    @DisplayName("Un ingrés manual suma al saldo")
    void manualIncomeAdds() {
        transactionController.createTransaction(manual("200.00", "INCOME"));

        assertThat(balance()).isEqualByComparingTo("1200.00");
    }

    @Test
    @DisplayName("Dos moviments idèntics el mateix dia conviuen")
    void twoIdenticalManualEntriesBothCount() {
        // Dos cafès de 2,50 € al mateix bar el mateix dia. Si l'alta manual
        // portés hash com les línies d'extracte, el segon es descartaria en
        // silenci i el saldo quedaria malament.
        transactionController.createTransaction(manual("2.50", "EXPENSE"));
        transactionController.createTransaction(manual("2.50", "EXPENSE"));

        assertThat(transactionRepository.findAll()).hasSize(2);
        assertThat(balance()).isEqualByComparingTo("995.00");
    }

    @Test
    @DisplayName("Un moviment manual no porta hash ni saldo resultant")
    void manualEntriesCarryNoStatementFields() {
        transactionController.createTransaction(manual("12.50", "EXPENSE"));

        Transaction saved = transactionRepository.findAll().get(0);
        // Tots dos camps només tenen sentit venint d'un extracte: el hash
        // identifica una línia concreta i el saldo és el que deia el banc en
        // aquell moment.
        assertThat(saved.getVerificationHash()).isNull();
        assertThat(saved.getBalance()).isNull();
    }

    @Test
    @DisplayName("Es lliga a la categoria, l'empresa i el compte per defecte")
    void manualEntryIsLinked() {
        transactionController.createTransaction(manual("12.50", "EXPENSE"));

        Transaction saved = transactionRepository.findAll().get(0);
        assertThat(saved.getCategory().getName()).isEqualTo("Altres");
        assertThat(saved.getCompany().getName()).isEqualTo("Bar de la cantonada");
        assertThat(saved.getAccount().getId()).isEqualTo(accountId);
    }

    @Test
    @DisplayName("Un import negatiu es desa en positiu: el signe viu al tipus")
    void amountsAreStoredPositive() {
        transactionController.createTransaction(manual("-12.50", "EXPENSE"));

        // Sense l'abs(), un import negatiu amb tipus despesa hauria SUMAT al
        // saldo en restar-ne un negatiu.
        assertThat(transactionRepository.findAll().get(0).getAmount())
                .isEqualByComparingTo("12.50");
        assertThat(balance()).isEqualByComparingTo("987.50");
    }

    @Test
    @DisplayName("Un import de zero o sense data es rebutja i no toca el saldo")
    void invalidEntriesAreRejected() {
        assertThat(transactionController.createTransaction(manual("0.00", "EXPENSE"))
                .getStatusCode().value()).isEqualTo(400);

        Transaction undated = manual("10.00", "EXPENSE");
        undated.setDate(null);
        assertThat(transactionController.createTransaction(undated)
                .getStatusCode().value()).isEqualTo(400);

        assertThat(transactionRepository.findAll()).isEmpty();
        assertThat(balance()).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("Esborrar una despesa desfà el que va restar del saldo")
    void deletingAnExpenseGivesTheMoneyBack() {
        transactionController.createTransaction(manual("12.50", "EXPENSE"));
        Long id = transactionRepository.findAll().get(0).getId();

        transactionController.deleteTransaction(id);

        // No n'hi ha prou d'esborrar la fila: el saldo es va moure en desar-la
        // i quedaria descompensat per sempre.
        assertThat(transactionRepository.findAll()).isEmpty();
        assertThat(balance()).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("Esborrar un ingrés també el desfà")
    void deletingAnIncomeTakesItBack() {
        transactionController.createTransaction(manual("200.00", "INCOME"));
        Long id = transactionRepository.findAll().get(0).getId();

        transactionController.deleteTransaction(id);

        assertThat(balance()).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("Afegir i esborrar diverses vegades deixa el saldo on era")
    void addingAndDeletingLeavesTheBalanceWhereItWas() {
        transactionController.createTransaction(manual("33.33", "EXPENSE"));
        transactionController.createTransaction(manual("10.00", "INCOME"));

        for (Transaction t : List.copyOf(transactionRepository.findAll())) {
            transactionController.deleteTransaction(t.getId());
        }

        // Amb double, anar i tornar deixava restes com 999.9999999999999.
        assertThat(balance()).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("Esborrar un moviment que no existeix dona 404 i no toca el saldo")
    void deletingSomethingMissingIsA404() {
        assertThat(transactionController.deleteTransaction(999_999L)
                .getStatusCode().value()).isEqualTo(404);

        assertThat(balance()).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("No es pot penjar un moviment d'un grup de categories")
    void groupsAreRejected() {
        Category group = categoryRepository.save(new Category("Gast mensual"));
        Category leaf = new Category("Cafès");
        leaf.setParentId(group.getId());
        categoryRepository.save(leaf);

        Transaction t = manual("10.00", "EXPENSE");
        t.setCategoryName("Gast mensual");

        // Penjat d'un grup, el moviment es comptaria dues vegades: per ell
        // mateix i en agregar els fills.
        assertThatThrownBy(() -> transactionController.createTransaction(t))
                .hasMessageContaining("és un grup");

        assertThat(transactionRepository.findAll()).isEmpty();
    }
}
