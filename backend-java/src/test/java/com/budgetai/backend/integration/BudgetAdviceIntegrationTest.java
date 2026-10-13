package com.budgetai.backend.integration;

import com.budgetai.backend.model.Budget;
import com.budgetai.backend.model.Category;
import com.budgetai.backend.model.Transaction;
import com.budgetai.backend.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Les propostes de l'anàlisi per HTTP, de punta a punta: demanar-les, aplicar-les
 * al mes que ve i veure-les al pla d'aquell mes.
 *
 * Restaurants té 100 € de pla i els últims tres mesos s'hi han gastat 150, 130
 * i 95: s'ha passat dues vegades i de mitjana en gasta 125. Supermercat va per
 * percentatge, Oci és un import i Viatges té un pressupost trimestral que ja
 * cobreix el mes que ve.
 */
@AutoConfigureMockMvc
class BudgetAdviceIntegrationTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private BudgetRepository budgetRepository;
    @Autowired private RecurringTransactionRepository recurringRepository;
    @Autowired private MonthlyIncomeRepository monthlyIncomeRepository;

    private final YearMonth current = YearMonth.now();
    private final YearMonth next = current.plusMonths(1);

    private Cookie session;
    private Category restaurants;
    private Category groceries;
    private Category leisure;
    private Category travel;

    @BeforeEach
    void setUp() throws Exception {
        transactionRepository.deleteAll();
        recurringRepository.deleteAll();
        budgetRepository.deleteAll();
        monthlyIncomeRepository.deleteAll();
        categoryRepository.deleteAll();

        Category monthly = saveCategory("Gast mensual", null);
        restaurants = saveCategory("Restaurants", monthly.getId());
        groceries = saveCategory("Supermercat", monthly.getId());
        leisure = saveCategory("Oci", monthly.getId());
        travel = saveCategory("Viatges", null);

        saveBudget(restaurants, "100.00", null, current.atDay(1), current.atEndOfMonth());
        saveBudget(groceries, "0.00", "10.00", current.atDay(1), current.atEndOfMonth());
        saveBudget(leisure, "50.00", null, current.atDay(1), current.atEndOfMonth());
        saveBudget(travel, "300.00", null, current.atDay(1), current.plusMonths(2).atEndOfMonth());

        save(restaurants, "150.00", current.minusMonths(1));
        save(restaurants, "130.00", current.minusMonths(2));
        save(restaurants, "95.00", current.minusMonths(3));

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

    private void saveBudget(Category category, String amount, String percentage, LocalDate from, LocalDate to) {
        Budget budget = new Budget();
        budget.setCategory(category);
        budget.setLimitAmount(new BigDecimal(amount));
        budget.setPercentage(percentage == null ? null : new BigDecimal(percentage));
        budget.setPeriodStart(from);
        budget.setPeriodEnd(to);
        budget.setActive(true);
        budgetRepository.save(budget);
    }

    private int hashes = 0;

    private void save(Category category, String amount, YearMonth month) {
        Transaction transaction = new Transaction();
        transaction.setAmount(new BigDecimal(amount));
        transaction.setDate(month.atDay(5));
        transaction.setType("EXPENSE");
        transaction.setCategory(category);
        transaction.setVerificationHash("consell-" + hashes++);
        transactionRepository.save(transaction);
    }

    private JsonNode getJson(String path) throws Exception {
        return objectMapper.readTree(mockMvc.perform(get(path).cookie(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static JsonNode find(JsonNode array, String key, String value) {
        return StreamSupport.stream(array.spliterator(), false)
                .filter(node -> value.equals(node.path(key).asText()))
                .findFirst()
                .orElse(null);
    }

    private String applyBody(List<Map<String, Object>> changes) throws Exception {
        return objectMapper.writeValueAsString(Map.of("periode", next.toString(), "canvis", changes));
    }

    private Budget monthlyBudgetOf(Category category, YearMonth month) {
        return budgetRepository.findAll().stream()
                .filter(budget -> budget.getCategory().getId().equals(category.getId()))
                .filter(budget -> budget.getPeriodStart().equals(month.atDay(1))
                        && budget.getPeriodEnd().equals(month.atEndOfMonth()))
                .findFirst()
                .orElse(null);
    }

    @Test
    @DisplayName("Es proposa pujar Restaurants a la mitjana del que s'hi gasta, i aplicar-ho ho posa al pla del mes que ve")
    void adviceAndApply() throws Exception {
        JsonNode advice = getJson("/analytics/budget-advice");
        assertThat(advice.get("mes_desti").asText()).isEqualTo(next.toString());
        JsonNode raise = find(advice.get("recomanacions"), "nom", "Restaurants");
        assertThat(raise.get("tipus").asText()).isEqualTo("PUJAR");
        assertThat(raise.get("proposta").decimalValue()).isEqualByComparingTo("125");

        mockMvc.perform(post("/budgets/apply-changes").cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyBody(List.of(
                                Map.of("categoria_id", restaurants.getId(), "import", raise.get("proposta").decimalValue()),
                                Map.of("categoria_id", groceries.getId(), "percentatge", new BigDecimal("12.5")),
                                Map.of("categoria_id", travel.getId(), "import", new BigDecimal("400"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periode").value(next.toString()))
                .andExpect(jsonPath("$.aplicats").value(2))
                // Restaurants, Supermercat i Oci; Viatges ja té el trimestral.
                .andExpect(jsonPath("$.copiats").value(3))
                .andExpect(jsonPath("$.omesos[0]").value("Viatges"));

        assertThat(monthlyBudgetOf(restaurants, next).getLimitAmount()).isEqualByComparingTo("125");
        assertThat(monthlyBudgetOf(restaurants, next).getPercentage()).isNull();
        // Un pla per percentatge segueix sent-ho.
        assertThat(monthlyBudgetOf(groceries, next).getPercentage()).isEqualByComparingTo("12.5");
        // El que no es canvia es copia tal com era.
        assertThat(monthlyBudgetOf(leisure, next).getLimitAmount()).isEqualByComparingTo("50");
        // El trimestral no es toca, i no se n'hi afegeix cap de mensual.
        assertThat(monthlyBudgetOf(travel, next)).isNull();
        // Aquest mes no canvia.
        assertThat(monthlyBudgetOf(restaurants, current).getLimitAmount()).isEqualByComparingTo("100");

        JsonNode nextSummary = getJson("/budgets/monthly-summary?year=" + next.getYear() + "&month=" + next.getMonthValue());
        JsonNode variable = find(nextSummary.get("seccions"), "tipus", "VARIABLE");
        JsonNode monthlyBlock = StreamSupport.stream(variable.get("grups").spliterator(), false)
                .filter(node -> "Gast mensual".equals(node.path("categoria").path("nom").asText()))
                .findFirst().orElseThrow();
        JsonNode restaurantsNode = StreamSupport.stream(monthlyBlock.get("subcategories").spliterator(), false)
                .filter(node -> "Restaurants".equals(node.path("categoria").path("nom").asText()))
                .findFirst().orElseThrow();
        assertThat(restaurantsNode.get("cost_vida_pla").decimalValue()).isEqualByComparingTo("125");
    }

    @Test
    @DisplayName("Un canvi mal fet no aplica res, ni tan sols la còpia del mes anterior")
    void invalidChangesApplyNothing() throws Exception {
        mockMvc.perform(post("/budgets/apply-changes").cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyBody(List.of(
                                Map.of("categoria_id", restaurants.getId(), "import", new BigDecimal("125")),
                                Map.of("categoria_id", leisure.getId(), "import", new BigDecimal("60"),
                                        "percentatge", new BigDecimal("5"))))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("només un")));
        assertThat(monthlyBudgetOf(restaurants, next)).isNull();
        assertThat(monthlyBudgetOf(leisure, next)).isNull();

        mockMvc.perform(post("/budgets/apply-changes").cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyBody(List.of(Map.of("categoria_id", 999_999L, "import", new BigDecimal("10"))))))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/budgets/apply-changes").cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"periode\":\"2026-13\",\"canvis\":[{\"categoria_id\":" + restaurants.getId() + ",\"import\":10}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("AAAA-MM")));
    }
}
