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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La confirmació de la pujada desa moviments i ajusta el saldo del compte.
 * Fer-ho dues vegades no ha de duplicar res.
 */
class ConfirmUploadIntegrationTest extends AbstractIntegrationTest {

    @Autowired private TransactionController transactionController;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private CategoryRepository categoryRepository;

    private Long accountId;

    @BeforeEach
    void setUp() {
        transactionRepository.deleteAll();
        accountRepository.deleteAll();

        // El controlador cau a "Altres" quan no reconeix la categoria. El
        // contenidor de PostgreSQL es comparteix entre classes de test, i
        // alguna altra pot haver buidat la taula, així que aquesta classe
        // s'assegura del que necessita en comptes de confiar-hi.
        if (categoryRepository.findByName("Altres").isEmpty()) {
            categoryRepository.save(new Category("Altres"));
        }

        Account account = new Account();
        // El controlador busca aquest compte pel nom com a compte per defecte.
        account.setName("Compte Principal");
        account.setType("CORRIENTE");
        account.setCurrentBalance(new BigDecimal("1000.00"));
        accountId = accountRepository.save(account).getId();
    }

    /**
     * Un moviment tal com arriba del navegador: sense hash.
     *
     * Abans els tests el fixaven a mà i per això no van veure l'error. El camp
     * porta @JsonIgnore, així que per l'API arriba sempre a null i la identitat
     * l'ha de calcular el controlador a partir dels camps del moviment.
     */
    private Transaction movement(String concept, String amount, String type) {
        Transaction t = new Transaction();
        t.setOriginalConcept(concept);
        t.setCompanyName(concept);
        t.setCategoryName("Altres");
        t.setAmount(new BigDecimal(amount));
        t.setDate(LocalDate.of(2026, 2, 15));
        t.setType(type);
        return t;
    }

    private BigDecimal balance() {
        return accountRepository.findById(accountId).orElseThrow().getCurrentBalance();
    }

    @Test
    @DisplayName("Confirmar desa els moviments i ajusta el saldo")
    void confirmPersistsAndAdjustsBalance() {
        transactionController.confirmUpload(List.of(
                movement("CONDIS", "45.30", "EXPENSE"),
                movement("NOMINA", "1500.00", "INCOME")
        ));

        assertThat(transactionRepository.findAll()).hasSize(2);
        // 1000 - 45.30 + 1500 = 2454.70
        assertThat(balance()).isEqualByComparingTo("2454.70");
    }

    @Test
    @DisplayName("Confirmar dues vegades el mateix lot no duplica res")
    void confirmingTwiceIsIdempotent() {
        List<Transaction> batch = List.of(movement("CONDIS", "45.30", "EXPENSE"));

        transactionController.confirmUpload(batch);
        BigDecimal afterFirst = balance();

        // Un doble clic o un reintent enviaven el mateix lot una segona
        // vegada: el hash només es comprovava en pujar el fitxer, no aquí,
        // així que es duplicaven moviments i es tornava a restar del saldo.
        ResponseEntity<?> second = transactionController.confirmUpload(
                List.of(movement("CONDIS", "45.30", "EXPENSE")));

        assertThat(transactionRepository.findAll()).hasSize(1);
        assertThat(balance()).isEqualByComparingTo(afterFirst);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) second.getBody();
        assertThat(body.get("skipped")).isEqualTo(1);
    }

    @Test
    @DisplayName("Un lot amb moviments nous i repetits només desa els nous")
    void mixedBatchOnlyPersistsTheNewOnes() {
        transactionController.confirmUpload(List.of(movement("CONDIS", "10.00", "EXPENSE")));

        ResponseEntity<?> response = transactionController.confirmUpload(List.of(
                movement("CONDIS", "10.00", "EXPENSE"),
                movement("BENZINERA", "20.00", "EXPENSE")
        ));

        assertThat(transactionRepository.findAll()).hasSize(2);
        // 1000 - 10 - 20: el repetit no torna a restar.
        assertThat(balance()).isEqualByComparingTo("970.00");

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body.get("saved")).isEqualTo(1);
        assertThat(body.get("skipped")).isEqualTo(1);
    }

    @Test
    @DisplayName("Els moviments es lliguen a la categoria i al compte per defecte")
    void movementsAreLinkedToCategoryAndAccount() {
        transactionController.confirmUpload(List.of(movement("CONDIS", "45.30", "EXPENSE")));

        Transaction saved = transactionRepository.findAll().get(0);

        assertThat(saved.getCategory()).isNotNull();
        assertThat(saved.getCategory().getName()).isEqualTo("Altres");
        assertThat(saved.getCompany()).isNotNull();
        assertThat(saved.getAccount().getId()).isEqualTo(accountId);
        // El valor per defecte s'aplica en desar, no en construir l'objecte.
        assertThat(saved.getCurrency()).isEqualTo("EUR");
    }

    @Test
    @DisplayName("El hash es calcula en confirmar i es desa, no arriba del client")
    void theHashIsComputedOnConfirm() {
        transactionController.confirmUpload(List.of(movement("CONDIS", "45.30", "EXPENSE")));

        // Sense desar-lo, la columna quedava a null. Com que PostgreSQL admet
        // tants nulls com vulguis en una columna única, no hi havia res que
        // impedís desar el mateix moviment una segona vegada.
        assertThat(transactionRepository.findAll().get(0).getVerificationHash()).isNotBlank();
    }

    @Test
    @DisplayName("Un lot que porta la mateixa fila dues vegades només en desa una")
    void aBatchWithItsOwnDuplicatesPersistsOnce() {
        // El fitxer pot portar la fila repetida. Sense filtrar dins del propi
        // lot, totes dues passaven la comprovació —cap de les dues era encara
        // a la base de dades— i la columna única rebentava la transacció
        // sencera, que amb @Transactional se n'enduia el lot complet.
        ResponseEntity<?> response = transactionController.confirmUpload(List.of(
                movement("CONDIS", "45.30", "EXPENSE"),
                movement("CONDIS", "45.30", "EXPENSE")
        ));

        assertThat(transactionRepository.findAll()).hasSize(1);
        assertThat(balance()).isEqualByComparingTo("954.70");

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body.get("saved")).isEqualTo(1);
        assertThat(body.get("skipped")).isEqualTo(1);
    }

    @Test
    @DisplayName("Corregir el tipus no fa que el moviment es torni a desar")
    void correctingTheTypeDoesNotDuplicate() {
        // Un abonament que el banc porta en negatiu: l'usuari el canvia a
        // ingrés a la pantalla de revisió. Si el tipus entrés al hash, tornar
        // a importar el mateix extracte el desaria un altre cop.
        transactionController.confirmUpload(List.of(movement("ABONAMENT", "19.99", "INCOME")));
        transactionController.confirmUpload(List.of(movement("ABONAMENT", "19.99", "EXPENSE")));

        assertThat(transactionRepository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("L'import es desa amb dos decimals exactes")
    void amountsKeepTheirScale() {
        transactionController.confirmUpload(List.of(
                movement("CONDIS", "0.10", "EXPENSE"),
                movement("CONDIS", "0.20", "EXPENSE")
        ));

        // Amb double, 1000 - 0.1 - 0.2 donava 999.6999999999999.
        assertThat(balance()).isEqualByComparingTo("999.70");
    }
}
