package com.budgetai.backend.integration;

import com.budgetai.backend.model.Budget;
import com.budgetai.backend.model.Category;
import com.budgetai.backend.model.RecurringTransaction;
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
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * El pla contra el real per HTTP, com el demana la pestanya Pressupost de
 * l'anàlisi, amb el pressupost de debò pel mig.
 *
 * Tres mesos, l'últim el mes en curs (els mesos van relatius a avui):
 *
 *                    fa dos mesos      el mes passat     aquest mes
 *   Restaurants 100  150  desviat      105  avís         120  desviat
 *   Supermercat 300  290  bé           340  desviat        0  bé
 *   Estalvis    200  200  bé           150  desviat        0  en curs
 *   Assegurança  50  600  puntual        0  bé             0  bé
 *   (anual de 600 €: el pla en reparteix 50 cada mes)
 */
@AutoConfigureMockMvc
class BudgetReviewIntegrationTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private BudgetRepository budgetRepository;
    @Autowired private RecurringTransactionRepository recurringRepository;
    @Autowired private MonthlyIncomeRepository monthlyIncomeRepository;

    private final YearMonth current = YearMonth.now();
    private final YearMonth previous = current.minusMonths(1);
    private final YearMonth twoAgo = current.minusMonths(2);

    private Cookie session;

    @BeforeEach
    void setUp() throws Exception {
        transactionRepository.deleteAll();
        recurringRepository.deleteAll();
        budgetRepository.deleteAll();
        monthlyIncomeRepository.deleteAll();
        categoryRepository.deleteAll();

        Category monthly = saveCategory("Gast mensual", null, Category.VARIABLE);
        Category restaurants = saveCategory("Restaurants", monthly.getId(), Category.VARIABLE);
        Category groceries = saveCategory("Supermercat", monthly.getId(), Category.VARIABLE);
        Category insurances = saveCategory("Assegurances", null, Category.FIXED);
        Category car = saveCategory("Assegurança cotxe", insurances.getId(), Category.FIXED);
        Category savings = saveCategory("Estalvis", null, Category.SAVINGS);

        for (YearMonth month : new YearMonth[]{twoAgo, previous, current}) {
            saveBudget(restaurants, "100.00", month);
            saveBudget(groceries, "300.00", month);
            saveBudget(savings, "200.00", month);
        }
        saveRecurring("Assegurança del cotxe", "600.00", "ANUAL", car);

        save(restaurants, "150.00", twoAgo);
        save(groceries, "290.00", twoAgo);
        save(savings, "200.00", twoAgo);
        save(car, "600.00", twoAgo);

        save(restaurants, "105.00", previous);
        save(groceries, "340.00", previous);
        save(savings, "150.00", previous);

        save(restaurants, "120.00", current);

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

    private void saveBudget(Category category, String amount, YearMonth month) {
        Budget budget = new Budget();
        budget.setCategory(category);
        budget.setLimitAmount(new BigDecimal(amount));
        budget.setPeriodStart(month.atDay(1));
        budget.setPeriodEnd(month.atEndOfMonth());
        budget.setActive(true);
        budgetRepository.save(budget);
    }

    private void saveRecurring(String name, String amount, String frequency, Category category) {
        RecurringTransaction recurring = new RecurringTransaction();
        recurring.setName(name);
        recurring.setAmount(new BigDecimal(amount));
        recurring.setFrequency(frequency);
        recurring.setType("EXPENSE");
        recurring.setNextDate(LocalDate.now());
        recurring.setCategory(category);
        recurring.setActive(true);
        recurringRepository.save(recurring);
    }

    private int hashes = 0;

    /** Una despesa el dia 5 del mes: el mes en curs sempre en té cinc. */
    private void save(Category category, String amount, YearMonth month) {
        Transaction transaction = new Transaction();
        transaction.setAmount(new BigDecimal(amount));
        transaction.setDate(month.atDay(5).isAfter(LocalDate.now()) ? LocalDate.now() : month.atDay(5));
        transaction.setType("EXPENSE");
        transaction.setCategory(category);
        transaction.setVerificationHash("revisio-" + hashes++);
        transactionRepository.save(transaction);
    }

    private JsonNode review(String from, String to) throws Exception {
        String response = mockMvc.perform(get("/analytics/budget-vs-actual")
                        .param("from", from).param("to", to).cookie(session))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(response);
    }

    private static JsonNode find(JsonNode array, String key, String value) {
        return StreamSupport.stream(array.spliterator(), false)
                .filter(node -> value.equals(node.path(key).asText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No hi ha cap " + key + " = " + value + " a " + array));
    }

    private JsonNode month(JsonNode review, YearMonth month) {
        return find(review.get("mesos"), "periode", month.toString());
    }

    private static JsonNode block(JsonNode month, String section, String name) {
        return find(find(month.get("seccions"), "tipus", section).get("blocs"), "nom", name);
    }

    @Test
    @DisplayName("Cada mes, cada bloc i cada subcategoria amb el pla, el real que ha passat de debò i el semàfor")
    void monthByMonth() throws Exception {
        JsonNode review = review(twoAgo.toString(), current.toString());

        JsonNode monthly = block(month(review, twoAgo), "VARIABLE", "Gast mensual");
        assertThat(monthly.get("pla").decimalValue()).isEqualByComparingTo("400");
        assertThat(monthly.get("real").decimalValue()).isEqualByComparingTo("440");
        assertThat(monthly.get("desviacio").decimalValue()).isEqualByComparingTo("40");
        assertThat(monthly.get("desviacio_percent").decimalValue()).isEqualByComparingTo("10.0");
        assertThat(monthly.get("estat").asText()).isEqualTo("AVIS");
        assertThat(find(monthly.get("subcategories"), "nom", "Restaurants").get("estat").asText()).isEqualTo("DESVIAT");
        assertThat(find(monthly.get("subcategories"), "nom", "Supermercat").get("estat").asText()).isEqualTo("BE");

        assertThat(block(month(review, previous), "SAVINGS", "Estalvis").get("estat").asText()).isEqualTo("DESVIAT");
        // El mes en curs no s'ha acabat: l'estalvi encara pot arribar, però
        // els restaurants ja s'han passat.
        assertThat(month(review, current).get("mes_en_curs").asBoolean()).isTrue();
        assertThat(block(month(review, current), "SAVINGS", "Estalvis").get("estat").asText()).isEqualTo("EN_CURS");
        assertThat(find(block(month(review, current), "VARIABLE", "Gast mensual").get("subcategories"), "nom", "Restaurants")
                .get("estat").asText()).isEqualTo("DESVIAT");
    }

    @Test
    @DisplayName("El càrrec anual de l'assegurança surt pel que s'ha pagat de debò, com a puntual i no com a desviament")
    void annualChargeIsOneOff() throws Exception {
        JsonNode review = review(twoAgo.toString(), current.toString());

        JsonNode insurance = block(month(review, twoAgo), "FIXED", "Assegurances");
        assertThat(insurance.get("pla").decimalValue()).isEqualByComparingTo("50");
        assertThat(insurance.get("real").decimalValue()).isEqualByComparingTo("600");
        assertThat(insurance.get("estat").asText()).isEqualTo("CARREC_PUNTUAL");
        assertThat(find(month(review, twoAgo).get("seccions"), "tipus", "FIXED").get("estat").asText())
                .isEqualTo("CARREC_PUNTUAL");
        assertThat(block(month(review, previous), "FIXED", "Assegurances").get("estat").asText()).isEqualTo("BE");
    }

    @Test
    @DisplayName("L'historial de cada bloc compta només els mesos tancats: quantes vegades s'ha passat i de quant de mitjana")
    void blockHistory() throws Exception {
        JsonNode review = review(twoAgo.toString(), current.toString());

        JsonNode monthly = find(review.get("blocs"), "nom", "Gast mensual");
        assertThat(monthly.get("seccio").asText()).isEqualTo("VARIABLE");
        assertThat(monthly.get("mesos")).hasSize(3);
        assertThat(monthly.get("mesos_tancats_amb_pla").asInt()).isEqualTo(2);
        assertThat(monthly.get("mesos_avis").asInt()).isEqualTo(1);
        assertThat(monthly.get("mesos_desviats").asInt()).isEqualTo(1);
        // (40 + 45) / 2
        assertThat(monthly.get("desviacio_mitjana").decimalValue()).isEqualByComparingTo("42.50");
        assertThat(monthly.get("estat_acumulat").asText()).isEqualTo("DESVIAT");

        JsonNode savings = find(review.get("blocs"), "nom", "Estalvis");
        assertThat(savings.get("mesos_be").asInt()).isEqualTo(1);
        assertThat(savings.get("mesos_desviats").asInt()).isEqualTo(1);
        assertThat(savings.get("desviacio_mitjana").decimalValue()).isEqualByComparingTo("-25.00");

        // En dos mesos, un càrrec anual de 600 € no es pot jutjar per l'acumulat.
        JsonNode insurance = find(review.get("blocs"), "nom", "Assegurances");
        assertThat(insurance.get("mesos_puntuals").asInt()).isEqualTo(1);
        assertThat(insurance.get("estat_acumulat").asText()).isEqualTo("CARREC_PUNTUAL");
    }

    @Test
    @DisplayName("Un mes mal escrit o un rang al revés tornen un 400 que diu què passa")
    void invalidRequests() throws Exception {
        mockMvc.perform(get("/analytics/budget-vs-actual").param("from", "2026-13").cookie(session))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("AAAA-MM")));
        mockMvc.perform(get("/analytics/budget-vs-actual")
                        .param("from", previous.toString()).param("to", twoAgo.toString()).cookie(session))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("posterior")));
    }
}
