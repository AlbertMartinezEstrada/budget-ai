package com.budgetai.backend.integration;

import com.budgetai.backend.model.Budget;
import com.budgetai.backend.model.Category;
import com.budgetai.backend.model.FinancialGoal;
import com.budgetai.backend.model.RecurringTransaction;
import com.budgetai.backend.model.Transaction;
import com.budgetai.backend.repository.*;
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
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * La pestanya Decisions per HTTP, amb el pressupost de debò pel mig: un
 * recurrent, una meta i sis mesos on el Delivery no para de pujar.
 */
@AutoConfigureMockMvc
class DecisionsIntegrationTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private BudgetRepository budgetRepository;
    @Autowired private RecurringTransactionRepository recurringRepository;
    @Autowired private FinancialGoalRepository goalRepository;

    private final YearMonth current = YearMonth.now();
    private Cookie session;

    @BeforeEach
    void setUp() throws Exception {
        transactionRepository.deleteAll();
        recurringRepository.deleteAll();
        budgetRepository.deleteAll();
        goalRepository.deleteAll();
        categoryRepository.deleteAll();

        Category monthly = saveCategory("Gast mensual", null, Category.VARIABLE);
        Category delivery = saveCategory("Delivery", monthly.getId(), Category.VARIABLE);
        Category subscriptions = saveCategory("Subscripcions", null, Category.FIXED);
        Category netflix = saveCategory("Netflix", subscriptions.getId(), Category.FIXED);
        Category savings = saveCategory("Estalvis", null, Category.SAVINGS);

        String[] deliveryByMonth = {"40.00", "45.00", "42.00", "50.00", "70.00", "95.00"};
        for (int back = 6; back >= 1; back--) {
            YearMonth month = current.minusMonths(back);
            save(delivery, deliveryByMonth[6 - back], month);
            save(savings, "200.00", month);
        }

        Budget budget = new Budget();
        budget.setCategory(delivery);
        budget.setLimitAmount(new BigDecimal("60.00"));
        budget.setPeriodStart(current.atDay(1));
        budget.setPeriodEnd(current.atEndOfMonth());
        budget.setActive(true);
        budgetRepository.save(budget);

        RecurringTransaction recurring = new RecurringTransaction();
        recurring.setName("Netflix");
        recurring.setAmount(new BigDecimal("12.99"));
        recurring.setFrequency("MENSUAL");
        recurring.setType("EXPENSE");
        recurring.setNextDate(LocalDate.now());
        recurring.setCategory(netflix);
        recurring.setActive(true);
        recurringRepository.save(recurring);

        FinancialGoal goal = new FinancialGoal();
        goal.setName("Viatge");
        goal.setTargetAmount(new BigDecimal("3000.00"));
        goal.setCurrentAmount(new BigDecimal("1200.00"));
        goal.setTargetDate(LocalDate.now().plusYears(1));
        goal.setCompleted(false);
        goalRepository.save(goal);

        session = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", "test-user", "password", "test-password"))))
                .andReturn().getResponse().getCookie("budget_session");
    }

    private Category saveCategory(String name, Long parentId, String costType) {
        Category category = new Category(name);
        category.setParentId(parentId);
        category.setCostType(costType);
        return categoryRepository.save(category);
    }

    private int hashes = 0;

    private void save(Category category, String amount, YearMonth month) {
        Transaction transaction = new Transaction();
        transaction.setAmount(new BigDecimal(amount));
        transaction.setDate(month.atDay(5));
        transaction.setType("EXPENSE");
        transaction.setCategory(category);
        transaction.setVerificationHash("decisio-" + hashes++);
        transactionRepository.save(transaction);
    }

    @Test
    @DisplayName("Els recurrents a l'any, el Delivery que puja, el simulador amb el pla i l'estalvi mitjà, i la meta")
    void decisions() throws Exception {
        mockMvc.perform(get("/analytics/decisions").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periode").value(current.toString()))
                .andExpect(jsonPath("$.recurrents.partides[0].nom").value("Netflix"))
                .andExpect(jsonPath("$.recurrents.partides[0].anual").value(155.88))
                .andExpect(jsonPath("$.recurrents.partides[0].bloc").value("Subscripcions"))
                .andExpect(jsonPath("$.tendencies[0].nom").value("Delivery"))
                .andExpect(jsonPath("$.tendencies[0].ultim").value(95.00))
                .andExpect(jsonPath("$.simulador.partides[0].nom").value("Delivery"))
                .andExpect(jsonPath("$.simulador.partides[0].pla").value(60.00))
                .andExpect(jsonPath("$.simulador.partides[0].mitjana_real").value(71.67))
                .andExpect(jsonPath("$.simulador.estalvi_mensual").value(200.00))
                .andExpect(jsonPath("$.metes[0].nom").value("Viatge"))
                .andExpect(jsonPath("$.metes[0].falta").value(1800.00));
    }
}
