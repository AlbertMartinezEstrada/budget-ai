package com.budgetai.backend.integration;

import com.budgetai.backend.model.Category;
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

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * La pestanya General per HTTP: el període, les categories desplegables i els
 * moviments d'una categoria, amb el format que llegeix el navegador.
 */
@AutoConfigureMockMvc
class GeneralAnalysisIntegrationTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private BudgetRepository budgetRepository;
    @Autowired private RecurringTransactionRepository recurringRepository;

    private final YearMonth previous = YearMonth.now().minusMonths(1);

    private Cookie session;
    private Category monthly;
    private Category groceries;

    @BeforeEach
    void setUp() throws Exception {
        transactionRepository.deleteAll();
        recurringRepository.deleteAll();
        budgetRepository.deleteAll();
        categoryRepository.deleteAll();

        monthly = saveCategory("Gast mensual", null);
        groceries = saveCategory("Supermercat", monthly.getId());
        Category bars = saveCategory("Bars", monthly.getId());

        save(groceries, "300.00", previous.atDay(10), "Mercadona");
        save(bars, "4.50", previous.atDay(11), "Cafeteria");
        save(bars, "4.50", previous.atDay(12), "Cafeteria");

        session = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", "test-user", "password", "test-password"))))
                .andReturn().getResponse().getCookie("budget_session");
    }

    private Category saveCategory(String name, Long parentId) {
        Category category = new Category(name);
        category.setParentId(parentId);
        category.setCostType(Category.VARIABLE);
        return categoryRepository.save(category);
    }

    private int hashes = 0;

    private void save(Category category, String amount, LocalDate date, String merchant) {
        Transaction transaction = new Transaction();
        transaction.setAmount(new BigDecimal(amount));
        transaction.setDate(date);
        transaction.setType("EXPENSE");
        transaction.setCategory(category);
        transaction.setEmpresa(merchant);
        transaction.setVerificationHash("general-" + hashes++);
        transactionRepository.save(transaction);
    }

    @Test
    @DisplayName("El període, les categories per blocs, els comerços i la despesa formiga")
    void general() throws Exception {
        mockMvc.perform(get("/analytics/general").param("from", previous.toString()).param("to", previous.toString()).cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.des_de").value(previous.toString()))
                .andExpect(jsonPath("$.mesos").value(1))
                .andExpect(jsonPath("$.resum.total_expense").value(309.00))
                .andExpect(jsonPath("$.categories[0].nom").value("Gast mensual"))
                .andExpect(jsonPath("$.categories[0].categoria_id").value(monthly.getId()))
                .andExpect(jsonPath("$.categories[0].subcategories[0].nom").value("Supermercat"))
                .andExpect(jsonPath("$.comercos.per_visites[0].nom").value("Cafeteria"))
                .andExpect(jsonPath("$.comercos.per_visites[0].moviments").value(2))
                .andExpect(jsonPath("$.despesa_formiga.total").value(9.00))
                .andExpect(jsonPath("$.evolucio.length()").value(12));
    }

    @Test
    @DisplayName("Els moviments d'un bloc, amb la data com la llegeix el navegador")
    void movements() throws Exception {
        mockMvc.perform(get("/analytics/category-movements").param("categoryId", String.valueOf(monthly.getId()))
                        .param("from", previous.toString()).param("to", previous.toString()).cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].data").value(previous.atDay(12).toString()))
                .andExpect(jsonPath("$[0].empresa").value("Cafeteria"))
                .andExpect(jsonPath("$[2].categoria").value("Supermercat"));
    }

    @Test
    @DisplayName("Un mes mal escrit és un 400, i una categoria que no existeix, un 404")
    void errors() throws Exception {
        mockMvc.perform(get("/analytics/general").param("from", "2026-13").cookie(session))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("AAAA-MM")));
        mockMvc.perform(get("/analytics/category-movements").param("categoryId", "999999").cookie(session))
                .andExpect(status().isNotFound());
    }
}
