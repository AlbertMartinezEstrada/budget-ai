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
    @DisplayName("Un import negatiu es rebutja en comptes de girar-li el signe")
    void negativeAmountsAreRejected() {
        // El signe viu al tipus, així que un negatiu al camp de l'import és un
        // error de qui l'escriu. Normalitzar-lo amb abs() seria endevinar: qui
        // posa "-12,50" en un ingrés tant pot voler dir "és una despesa" com
        // "m'he equivocat de camp". I amb el signe intacte, una despesa
        // negativa hauria SUMAT al saldo en restar-ne un negatiu.
        assertThat(transactionController.createTransaction(manual("-12.50", "EXPENSE"))
                .getStatusCode().value()).isEqualTo(400);

        assertThat(transactionRepository.findAll()).isEmpty();
        assertThat(balance()).isEqualByComparingTo("1000.00");
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

    /** Un canvi solt, per no repetir el moviment sencer a cada test. */
    private Transaction change() {
        return new Transaction();
    }

    @Test
    @DisplayName("Canviar l'import ajusta el saldo per la diferència")
    void editingTheAmountMovesOnlyTheDifference() {
        transactionController.createTransaction(manual("40.00", "EXPENSE"));
        Long id = transactionRepository.findAll().get(0).getId();

        Transaction changes = change();
        changes.setAmount(new BigDecimal("50.00"));
        transactionController.updateTransaction(id, changes);

        // 1000 − 50. Sense desfer l'efecte anterior, hauria restat 50 més
        // sobre els 960 que ja hi havia i hauria quedat a 910.
        assertThat(balance()).isEqualByComparingTo("950.00");
    }

    @Test
    @DisplayName("Canviar de despesa a ingrés gira el saldo sencer")
    void editingTheTypeFlipsTheBalance() {
        transactionController.createTransaction(manual("40.00", "EXPENSE"));
        Long id = transactionRepository.findAll().get(0).getId();

        Transaction changes = change();
        changes.setType("INCOME");
        transactionController.updateTransaction(id, changes);

        // Es tornen els 40 restats i se'n sumen 40: de 960 a 1040.
        assertThat(balance()).isEqualByComparingTo("1040.00");
    }

    @Test
    @DisplayName("Un camp que no s'envia no es toca")
    void absentFieldsAreLeftAlone() {
        transactionController.createTransaction(manual("40.00", "EXPENSE"));
        Long id = transactionRepository.findAll().get(0).getId();

        Transaction changes = change();
        changes.setCategoria("Altres");
        transactionController.updateTransaction(id, changes);

        Transaction saved = transactionRepository.findById(id).orElseThrow();
        assertThat(saved.getCategory().getName()).isEqualTo("Altres");
        // L'import i l'empresa no anaven a la petició.
        assertThat(saved.getAmount()).isEqualByComparingTo("40.00");
        assertThat(saved.getCompany().getName()).isEqualTo("Bar de la cantonada");
        assertThat(balance()).isEqualByComparingTo("960.00");
    }

    @Test
    @DisplayName("Editar no canvia el hash: segueix sent la mateixa línia d'extracte")
    void editingKeepsTheStatementIdentity() {
        Transaction imported = manual("40.00", "EXPENSE");
        imported.setVerificationHash("hash-de-l-extracte");
        transactionRepository.save(imported);

        Transaction changes = change();
        changes.setCompanyName("Nom corregit");
        transactionController.updateTransaction(imported.getId(), changes);

        // Recalcular-lo faria que tornar a importar el mateix fitxer dupliqués
        // el moviment, que és justament el que el hash evita.
        assertThat(transactionRepository.findById(imported.getId()).orElseThrow()
                .getVerificationHash()).isEqualTo("hash-de-l-extracte");
    }

    @Test
    @DisplayName("Editar un import a zero es rebutja i no toca el saldo")
    void editingToAnInvalidAmountIsRejected() {
        transactionController.createTransaction(manual("40.00", "EXPENSE"));
        Long id = transactionRepository.findAll().get(0).getId();

        Transaction changes = change();
        changes.setAmount(new BigDecimal("0.00"));

        assertThat(transactionController.updateTransaction(id, changes)
                .getStatusCode().value()).isEqualTo(400);
        // Es valida abans de desfer res: el saldo es queda com estava.
        assertThat(balance()).isEqualByComparingTo("960.00");
    }

    @Test
    @DisplayName("Editar un moviment que no existeix dona 404")
    void editingSomethingMissingIsA404() {
        assertThat(transactionController.updateTransaction(999_999L, change())
                .getStatusCode().value()).isEqualTo(404);
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
        // Noms propis d'aquest test i creats només si falten. "nom" és únic i
        // el contenidor es comparteix entre classes: agafar un nom real de
        // l'init.sql, com "Gast mensual", peta contra la restricció, i donar
        // per fet que la categoria no hi és peta la segona vegada.
        Category group = categoryRepository.findByName("ZZ Grup de prova")
                .orElseGet(() -> categoryRepository.save(new Category("ZZ Grup de prova")));

        if (categoryRepository.findByName("ZZ Fulla de prova").isEmpty()) {
            Category leaf = new Category("ZZ Fulla de prova");
            leaf.setParentId(group.getId());
            categoryRepository.save(leaf);
        }

        Transaction t = manual("10.00", "EXPENSE");
        t.setCategoryName("ZZ Grup de prova");

        // Penjat d'un grup, el moviment es comptaria dues vegades: per ell
        // mateix i en agregar els fills.
        assertThatThrownBy(() -> transactionController.createTransaction(t))
                .hasMessageContaining("és un grup");

        assertThat(transactionRepository.findAll()).isEmpty();
    }
}
