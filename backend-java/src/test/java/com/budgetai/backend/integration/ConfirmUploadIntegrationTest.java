package com.budgetai.backend.integration;

import com.budgetai.backend.controller.TransactionController;
import com.budgetai.backend.model.Account;
import com.budgetai.backend.model.Transaction;
import com.budgetai.backend.repository.AccountRepository;
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

    private Long accountId;

    @BeforeEach
    void setUp() {
        transactionRepository.deleteAll();
        accountRepository.deleteAll();

        Account account = new Account();
        // El controlador busca aquest compte pel nom com a compte per defecte.
        account.setName("Compte Principal");
        account.setType("CORRIENTE");
        account.setCurrentBalance(new BigDecimal("1000.00"));
        accountId = accountRepository.save(account).getId();
    }

    private Transaction movement(String concept, String amount, String type, String hash) {
        Transaction t = new Transaction();
        t.setOriginalConcept(concept);
        t.setCompanyName(concept);
        t.setCategoryName("Altres");
        t.setAmount(new BigDecimal(amount));
        t.setDate(LocalDate.of(2026, 2, 15));
        t.setType(type);
        t.setVerificationHash(hash);
        return t;
    }

    private BigDecimal balance() {
        return accountRepository.findById(accountId).orElseThrow().getCurrentBalance();
    }

    @Test
    @DisplayName("Confirmar desa els moviments i ajusta el saldo")
    void confirmPersistsAndAdjustsBalance() {
        transactionController.confirmUpload(List.of(
                movement("CONDIS", "45.30", "EXPENSE", "hash-1"),
                movement("NOMINA", "1500.00", "INCOME", "hash-2")
        ));

        assertThat(transactionRepository.findAll()).hasSize(2);
        // 1000 - 45.30 + 1500 = 2454.70
        assertThat(balance()).isEqualByComparingTo("2454.70");
    }

    @Test
    @DisplayName("Confirmar dues vegades el mateix lot no duplica res")
    void confirmingTwiceIsIdempotent() {
        List<Transaction> batch = List.of(movement("CONDIS", "45.30", "EXPENSE", "hash-dup"));

        transactionController.confirmUpload(batch);
        BigDecimal afterFirst = balance();

        // Un doble clic o un reintent enviaven el mateix lot una segona
        // vegada: el hash només es comprovava en pujar el fitxer, no aquí,
        // així que es duplicaven moviments i es tornava a restar del saldo.
        ResponseEntity<?> second = transactionController.confirmUpload(
                List.of(movement("CONDIS", "45.30", "EXPENSE", "hash-dup")));

        assertThat(transactionRepository.findAll()).hasSize(1);
        assertThat(balance()).isEqualByComparingTo(afterFirst);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) second.getBody();
        assertThat(body.get("skipped")).isEqualTo(1);
    }

    @Test
    @DisplayName("Un lot amb moviments nous i repetits només desa els nous")
    void mixedBatchOnlyPersistsTheNewOnes() {
        transactionController.confirmUpload(List.of(movement("CONDIS", "10.00", "EXPENSE", "hash-a")));

        ResponseEntity<?> response = transactionController.confirmUpload(List.of(
                movement("CONDIS", "10.00", "EXPENSE", "hash-a"),
                movement("BENZINERA", "20.00", "EXPENSE", "hash-b")
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
        transactionController.confirmUpload(List.of(movement("CONDIS", "45.30", "EXPENSE", "hash-link")));

        Transaction saved = transactionRepository.findAll().get(0);

        assertThat(saved.getCategory()).isNotNull();
        assertThat(saved.getCategory().getName()).isEqualTo("Altres");
        assertThat(saved.getCompany()).isNotNull();
        assertThat(saved.getAccount().getId()).isEqualTo(accountId);
        // El valor per defecte s'aplica en desar, no en construir l'objecte.
        assertThat(saved.getCurrency()).isEqualTo("EUR");
    }

    @Test
    @DisplayName("L'import es desa amb dos decimals exactes")
    void amountsKeepTheirScale() {
        transactionController.confirmUpload(List.of(
                movement("CONDIS", "0.10", "EXPENSE", "hash-x"),
                movement("CONDIS", "0.20", "EXPENSE", "hash-y")
        ));

        // Amb double, 1000 - 0.1 - 0.2 donava 999.6999999999999.
        assertThat(balance()).isEqualByComparingTo("999.70");
    }
}
