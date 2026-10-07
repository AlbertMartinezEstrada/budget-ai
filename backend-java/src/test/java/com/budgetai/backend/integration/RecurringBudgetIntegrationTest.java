package com.budgetai.backend.integration;

import com.budgetai.backend.model.Account;
import com.budgetai.backend.model.Budget;
import com.budgetai.backend.model.Category;
import com.budgetai.backend.model.RecurringTransaction;
import com.budgetai.backend.model.Transaction;
import com.budgetai.backend.repository.*;
import com.budgetai.backend.service.BudgetService;
import com.budgetai.backend.service.RecurringTransactionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Els recurrents i el pressupost: el que es posa en un ha de sortir a l'altre.
 *
 * Abans no ho feia en quatre casos, i en cap no sortia cap error:
 *
 *   - un recurrent a la llum (una fulla variable) no comptava al pla;
 *   - editar un recurrent des de la pantalla el desactivava, perquè el
 *     formulari no envia "activa", i deixava de comptar;
 *   - un recurrent en un bloc o sense categoria no comptava enlloc;
 *   - un ingrés recurrent (la nòmina) no era cap ingrés previst.
 *
 * L'escenari: un bloc fix "Llar" amb el lloguer (fix) i la llum (variable),
 * i la secció d'ingressos amb la nòmina. El mes és l'octubre.
 */
@AutoConfigureMockMvc
class RecurringBudgetIntegrationTest extends AbstractIntegrationTest {

    private static final YearMonth OCTOBER = YearMonth.of(2026, 10);
    private static final YearMonth NOVEMBER = YearMonth.of(2026, 11);

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private RecurringTransactionService recurringService;
    @Autowired private BudgetService budgetService;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private RecurringTransactionRepository recurringRepository;
    @Autowired private BudgetRepository budgetRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private MonthlyIncomeRepository monthlyIncomeRepository;

    private Category home;
    private Category electricity;
    private Category payroll;
    private Account account;

    @BeforeEach
    void setUp() {
        transactionRepository.deleteAll();
        recurringRepository.deleteAll();
        budgetRepository.deleteAll();
        monthlyIncomeRepository.deleteAll();
        categoryRepository.deleteAll();

        home = saveCategory("Llar", null, Category.FIXED);
        saveCategory("Lloguer", home.getId(), Category.FIXED);
        electricity = saveCategory("Llum", home.getId(), Category.VARIABLE);
        Category income = saveCategory("Ingressos", null, "INCOME");
        payroll = saveCategory("Nòmina", income.getId(), null);

        account = new Account();
        account.setName("Compte Principal");
        account.setCurrentBalance(new BigDecimal("1000.00"));
        account = accountRepository.save(account);
    }

    private Category saveCategory(String name, Long parentId, String costType) {
        Category category = new Category(name);
        category.setParentId(parentId);
        category.setCostType(costType);
        return categoryRepository.save(category);
    }

    private RecurringTransaction recurring(Category category, String type, String amount) {
        RecurringTransaction recurring = new RecurringTransaction();
        recurring.setName(category == null ? "Sense categoria" : category.getName());
        recurring.setCategory(category);
        recurring.setType(type);
        recurring.setAmount(new BigDecimal(amount));
        recurring.setFrequency("MENSUAL");
        recurring.setNextDate(OCTOBER.atDay(5));
        recurring.setAccount(account);
        return recurring;
    }

    private void spend(Category category, String amount, LocalDate date) {
        Transaction transaction = new Transaction();
        transaction.setType("EXPENSE");
        transaction.setAmount(new BigDecimal(amount));
        transaction.setDate(date);
        transaction.setCategory(category);
        transactionRepository.save(transaction);
    }

    private void saveMonthlyBudget(Category category, YearMonth month, String amount) {
        Budget budget = new Budget();
        budget.setCategory(category);
        budget.setLimitAmount(new BigDecimal(amount));
        budget.setPeriodStart(month.atDay(1));
        budget.setPeriodEnd(month.atEndOfMonth());
        budget.setActive(true);
        budgetRepository.save(budget);
    }

    private Map<String, Object> summaryOf(YearMonth month) {
        return budgetService.getMonthlySummary(month.getYear(), month.getMonthValue());
    }

    /** El node d'una categoria al resum, a qualsevol profunditat. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> nodeOf(Category category, YearMonth month) {
        List<Map<String, Object>> pending = new ArrayList<>();
        for (Map<String, Object> section : (List<Map<String, Object>>) summaryOf(month).get("seccions")) {
            pending.addAll((List<Map<String, Object>>) section.get("grups"));
        }
        while (!pending.isEmpty()) {
            Map<String, Object> node = pending.remove(0);
            if (category.getId().equals(((Category) node.get("categoria")).getId())) return node;
            pending.addAll((List<Map<String, Object>>) node.get("subcategories"));
        }
        throw new AssertionError("No s'ha trobat " + category.getName());
    }

    private BigDecimal planOf(Category category, YearMonth month) {
        return (BigDecimal) nodeOf(category, month).get("cost_vida_pla");
    }

    private Cookie login() throws Exception {
        return mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", "test-user", "password", "test-password"))))
                .andReturn().getResponse().getCookie("budget_session");
    }

    @Test
    @DisplayName("Un recurrent a la llum (fulla variable) és el seu pla, i es compara amb el que s'hi gasta")
    void aRecurringInAVariableLeafIsItsPlan() {
        recurringService.createRecurringTransaction(recurring(electricity, "EXPENSE", "60.00"));
        spend(electricity, "45.00", OCTOBER.atDay(12));

        assertThat(planOf(electricity, OCTOBER)).isEqualByComparingTo("60.00");
        // Una variable continua costant el que s'hi gasta, no el recurrent.
        assertThat((BigDecimal) nodeOf(electricity, OCTOBER).get("cost_vida_real")).isEqualByComparingTo("45.00");
        // I el bloc el reserva: és diners que aquest mes ja tenen destí.
        assertThat(planOf(home, OCTOBER)).isEqualByComparingTo("60.00");
    }

    @Test
    @DisplayName("Un import posat en un mes continua manant sobre el recurrent, només aquell mes")
    void aMonthlyAmountStillWins() {
        recurringService.createRecurringTransaction(recurring(electricity, "EXPENSE", "60.00"));
        saveMonthlyBudget(electricity, OCTOBER, "80.00");

        assertThat(planOf(electricity, OCTOBER)).isEqualByComparingTo("80.00");
        assertThat(planOf(electricity, NOVEMBER)).isEqualByComparingTo("60.00");
    }

    @Test
    @DisplayName("Copiar el mes anterior no arrossega el canvi puntual d'una fulla variable amb recurrent")
    void copyingSkipsVariableLeavesWithARecurring() {
        recurringService.createRecurringTransaction(recurring(electricity, "EXPENSE", "60.00"));
        saveMonthlyBudget(electricity, OCTOBER, "80.00");

        budgetService.copyFromPreviousMonth(NOVEMBER.getYear(), NOVEMBER.getMonthValue());

        assertThat(planOf(electricity, NOVEMBER)).isEqualByComparingTo("60.00");
    }

    @Test
    @DisplayName("Un ingrés recurrent és la previsió de la seva fulla, i entra al que es reparteix")
    void anIncomeRecurringIsTheForecast() {
        recurringService.createRecurringTransaction(recurring(payroll, "INCOME", "2000.00"));

        assertThat(planOf(payroll, OCTOBER)).isEqualByComparingTo("2000.00");
        Map<String, Object> summary = summaryOf(OCTOBER);
        assertThat((BigDecimal) summary.get("ingressos_previstos")).isEqualByComparingTo("2000.00");
        assertThat((BigDecimal) summary.get("total_disponible")).isEqualByComparingTo("2000.00");
    }

    @Test
    @DisplayName("Editar un recurrent des de la pantalla no el desactiva ni li treu el compte")
    void editingFromTheScreenKeepsItActive() throws Exception {
        RecurringTransaction created = recurringService.createRecurringTransaction(
                recurring(electricity, "EXPENSE", "60.00"));

        // El mateix que envia RecurringTransactions.js: sense "activa" ni compte.
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("nom", "Llum");
        form.put("tipus", "EXPENSE");
        form.put("import", 70);
        form.put("frequencia", "MENSUAL");
        form.put("proxima_data", "2026-10-05");
        form.put("descripcio", "");
        form.put("category", Map.of("id", electricity.getId()));

        mockMvc.perform(put("/recurring/" + created.getId()).cookie(login())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(form)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activa").value(true))
                .andExpect(jsonPath("$.category.nom").value("Llum"));

        RecurringTransaction stored = recurringRepository.findById(created.getId()).orElseThrow();
        assertThat(stored.getActive()).isTrue();
        assertThat(stored.getAccount()).isNotNull();
        assertThat(planOf(electricity, OCTOBER)).isEqualByComparingTo("70.00");
    }

    @Test
    @DisplayName("Un recurrent en un bloc es rebutja i diu per què")
    void aRecurringInABlockIsRejected() throws Exception {
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("nom", "Llar");
        form.put("tipus", "EXPENSE");
        form.put("import", 50);
        form.put("frequencia", "MENSUAL");
        form.put("proxima_data", "2026-10-05");
        form.put("category", Map.of("id", home.getId()));

        mockMvc.perform(post("/recurring").cookie(login())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(form)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("«Llar» és un bloc")));
        assertThat(recurringRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("Un recurrent sense categoria o de l'altre sentit es rebutja: no comptaria enlloc")
    void aRecurringNeedsACategoryOfItsDirection() {
        assertThatThrownBy(() -> recurringService.createRecurringTransaction(recurring(null, "EXPENSE", "10.00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Falta la categoria");
        assertThatThrownBy(() -> recurringService.createRecurringTransaction(recurring(payroll, "EXPENSE", "10.00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("és una categoria d'ingressos");
        assertThatThrownBy(() -> recurringService.createRecurringTransaction(recurring(electricity, "INCOME", "10.00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ha d'anar a una categoria d'ingressos");
        assertThat(recurringRepository.findAll()).isEmpty();
    }
}
