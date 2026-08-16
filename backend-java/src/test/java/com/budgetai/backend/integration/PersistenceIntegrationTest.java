package com.budgetai.backend.integration;

import com.budgetai.backend.model.Account;
import com.budgetai.backend.model.FinancialGoal;
import com.budgetai.backend.model.Transaction;
import com.budgetai.backend.repository.AccountRepository;
import com.budgetai.backend.repository.FinancialGoalRepository;
import com.budgetai.backend.repository.TransactionRepository;
import com.budgetai.backend.service.AccountService;
import com.budgetai.backend.service.FinancialGoalService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Com es comporten les dades en anar i tornar de PostgreSQL de debò.
 *
 * Els tests amb mocks comproven la lògica; aquests comproven el que només es
 * veu amb la base de dades pel mig: l'escala de les columnes NUMERIC, els
 * valors per defecte de @PrePersist i les claus foranes.
 */
class PersistenceIntegrationTest extends AbstractIntegrationTest {

    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private FinancialGoalRepository goalRepository;
    @Autowired private AccountService accountService;
    @Autowired private FinancialGoalService goalService;
    @Autowired private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        transactionRepository.deleteAll();
        goalRepository.deleteAll();
        accountRepository.deleteAll();
    }

    @Test
    @DisplayName("Els imports tornen de la base de dades amb dos decimals exactes")
    @Transactional
    void amountsRoundTripWithExactScale() {
        Account account = new Account();
        account.setName("Compte");
        account.setCurrentBalance(new BigDecimal("112.97"));
        Long id = accountRepository.save(account).getId();

        // Es buida el context de persistència per llegir de la base de dades
        // i no de la memòria.
        entityManager.flush();
        entityManager.clear();

        BigDecimal stored = accountRepository.findById(id).orElseThrow().getCurrentBalance();

        assertThat(stored).isEqualByComparingTo("112.97");
        // La columna és NUMERIC(15,2): l'escala ha de ser exactament 2.
        assertThat(stored.scale()).isEqualTo(2);
        assertThat(stored.toPlainString()).isEqualTo("112.97");
    }

    @Test
    @DisplayName("Sumar cèntims moltes vegades no acumula error")
    void repeatedCentAdditionsDoNotDrift() {
        Account account = new Account();
        account.setName("Compte");
        account.setCurrentBalance(BigDecimal.ZERO);
        Long id = accountRepository.save(account).getId();

        // Cent sumes de 0,01: amb double el resultat no seria exactament 1,00.
        for (int i = 0; i < 100; i++) {
            accountService.updateAccountBalance(id, new BigDecimal("0.01"), "ADD");
        }

        assertThat(accountRepository.findById(id).orElseThrow().getCurrentBalance())
                .isEqualByComparingTo("1.00");
    }

    @Test
    @DisplayName("Els valors per defecte s'apliquen en desar, no en construir l'objecte")
    void defaultsAreAppliedOnPersist() {
        Account account = new Account();
        account.setName("Mínim");
        // No s'informa ni saldo, ni divisa, ni estat.

        Account saved = accountRepository.save(account);

        assertThat(saved.getCurrentBalance()).isEqualByComparingTo("0.00");
        assertThat(saved.getCurrency()).isEqualTo("EUR");
        assertThat(saved.getActive()).isTrue();
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("Una actualització parcial no esborra els camps que no s'envien")
    void partialUpdateKeepsStoredValues() {
        Account account = new Account();
        account.setName("Original");
        account.setType("CORRIENTE");
        account.setCurrentBalance(new BigDecimal("250.75"));
        account.setCurrency("USD");
        account.setActive(false);
        Long id = accountRepository.save(account).getId();

        // Exactament el que envia el formulari en editar.
        Account partial = new Account();
        partial.setName("Editat");
        partial.setType("TARJETA");
        accountService.updateAccount(id, partial);

        Account updated = accountRepository.findById(id).orElseThrow();
        assertThat(updated.getName()).isEqualTo("Editat");
        assertThat(updated.getType()).isEqualTo("TARJETA");
        // Aquests tres es perdien: el saldo tornava a zero, la divisa a EUR
        // i el compte es reactivava.
        assertThat(updated.getCurrentBalance()).isEqualByComparingTo("250.75");
        assertThat(updated.getCurrency()).isEqualTo("USD");
        assertThat(updated.getActive()).isFalse();
    }

    @Test
    @DisplayName("Editar un objectiu no esborra els diners ja estalviats")
    void updatingAGoalKeepsSavedMoney() {
        FinancialGoal goal = new FinancialGoal();
        goal.setName("Viatge");
        goal.setTargetAmount(new BigDecimal("1500.00"));
        goal.setCurrentAmount(new BigDecimal("250.00"));
        goal.setTargetDate(LocalDate.of(2026, 12, 31));
        Long id = goalRepository.save(goal).getId();

        FinancialGoal partial = new FinancialGoal();
        partial.setName("Viatge al Japó");
        partial.setTargetAmount(new BigDecimal("2000.00"));
        goalService.updateGoal(id, partial);

        FinancialGoal updated = goalRepository.findById(id).orElseThrow();
        assertThat(updated.getName()).isEqualTo("Viatge al Japó");
        assertThat(updated.getCurrentAmount()).isEqualByComparingTo("250.00");
        assertThat(updated.getTargetDate()).isEqualTo(LocalDate.of(2026, 12, 31));
    }

    @Test
    @DisplayName("No es pot esborrar un compte que té moviments")
    void deletingAnAccountWithTransactionsIsBlocked() {
        Account account = new Account();
        account.setName("Amb moviments");
        account.setCurrentBalance(BigDecimal.ZERO);
        Long id = accountRepository.save(account).getId();

        Transaction transaction = new Transaction();
        transaction.setAccount(account);
        transaction.setAmount(new BigDecimal("10.00"));
        transaction.setDate(LocalDate.of(2026, 2, 15));
        transaction.setType("EXPENSE");
        transaction.setVerificationHash("hash-fk");
        transactionRepository.save(transaction);

        // Sense la comprovació prèvia, això petava com una violació de clau
        // forana i arribava a la interfície com un 500 sense explicació.
        assertThatThrownBy(() -> accountService.deleteAccount(id))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1 moviments");

        assertThat(accountRepository.findById(id)).isPresent();
    }

    private Transaction statementLine(String hash, Account account) {
        Transaction t = new Transaction();
        t.setAmount(new BigDecimal("10.00"));
        t.setDate(LocalDate.of(2026, 2, 15));
        t.setType("EXPENSE");
        t.setVerificationHash(hash);
        t.setAccount(account);
        return t;
    }

    @Test
    @DisplayName("El hash de verificació és únic dins d'un mateix compte")
    void verificationHashIsUniquePerAccount() {
        Account account = accountRepository.save(accountNamed("Compte del hash"));
        transactionRepository.saveAndFlush(statementLine("hash-repetit", account));

        // L'última barrera contra duplicats: encara que la comprovació de
        // l'aplicació fallés, la base de dades no ho permet.
        assertThatThrownBy(() -> transactionRepository.saveAndFlush(
                statementLine("hash-repetit", account)))
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("El mateix hash a dos comptes diferents sí que s'admet")
    void theSameHashIsAllowedInAnotherAccount() {
        Account origin = accountRepository.save(accountNamed("Origen del traspàs"));
        Account destination = accountRepository.save(accountNamed("Destí del traspàs"));

        transactionRepository.saveAndFlush(statementLine("hash-traspas", origin));

        // Un traspàs deixa el mateix import el mateix dia als extractes dels
        // dos comptes. Amb la unicitat només sobre el hash, la segona pota es
        // rebutjava i el compte destí es quedava sense el moviment.
        transactionRepository.saveAndFlush(statementLine("hash-traspas", destination));

        assertThat(transactionRepository.findAll())
                .filteredOn(t -> "hash-traspas".equals(t.getVerificationHash()))
                .hasSize(2);
    }

    private Account accountNamed(String name) {
        Account account = new Account();
        account.setName(name);
        account.setType("CORRIENTE");
        account.setCurrentBalance(new BigDecimal("0.00"));
        return account;
    }
}
