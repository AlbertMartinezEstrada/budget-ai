package com.budgetai.backend.service;

import com.budgetai.backend.model.Category;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * La previsió del mes en curs i les propostes per al mes que ve, amb resums
 * del pressupost fets a mà. Amb la base de dades pel mig, a
 * BudgetAdviceIntegrationTest.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BudgetAdviceServiceTest {

    /** Dissabte 10 d'octubre: en queden 21 dies després d'avui, de 31. */
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 10);

    @Mock private BudgetService budgetService;
    @InjectMocks private BudgetAdviceService service;

    private final Category restaurants = category(1L, "Restaurants");
    private final Category groceries = category(2L, "Supermercat");

    /** Els blocs variables de cada mes. */
    private final Map<YearMonth, List<Map<String, Object>>> months = new HashMap<>();

    private static Category category(long id, String name) {
        Category category = new Category(name);
        category.setId(id);
        return category;
    }

    private static Map<String, Object> node(Category category, String plan, String real) {
        Map<String, Object> node = new HashMap<>();
        node.put("categoria", category);
        node.put("es_grup", false);
        node.put("cost_vida_pla", new BigDecimal(plan));
        node.put("caixa_real", new BigDecimal(real));
        node.put("subcategories", List.of());
        return node;
    }

    /** Un node amb el pla posat a mà, com un import. */
    private static Map<String, Object> planned(Category category, String plan, String real) {
        Map<String, Object> node = node(category, plan, real);
        node.put("quantitat_limit", new BigDecimal(plan));
        return node;
    }

    private void given(YearMonth month, Map<String, Object>... blocks) {
        months.put(month, List.of(blocks));
    }

    private Map<String, Object> advice() {
        when(budgetService.getMonthlySummary(anyInt(), anyInt())).thenAnswer(invocation -> {
            YearMonth month = YearMonth.of((int) invocation.getArgument(0), (int) invocation.getArgument(1));
            Map<String, Object> variable = new HashMap<>();
            variable.put("tipus", "VARIABLE");
            variable.put("grups", months.getOrDefault(month, List.of()));
            return Map.of("seccions", List.of(variable));
        });
        return service.advice(TODAY);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> recommendationFor(Map<String, Object> advice, String name) {
        return ((List<Map<String, Object>>) advice.get("recomanacions")).stream()
                .filter(recommendation -> name.equals(recommendation.get("nom")))
                .findFirst()
                .orElse(null);
    }

    // ============ LES PROPOSTES ============

    @Test
    @DisplayName("Si s'ha passat almenys dos de tres mesos i de mitjana gasta més, es proposa pujar a la mitjana, arrodonida a 5 €")
    void raiseWhenUsuallyOver() {
        given(YearMonth.of(2026, 10), planned(restaurants, "100", "40"));
        given(YearMonth.of(2026, 9), node(restaurants, "100", "150"));
        given(YearMonth.of(2026, 8), node(restaurants, "100", "130"));
        given(YearMonth.of(2026, 7), node(restaurants, "100", "95"));

        Map<String, Object> advice = advice();
        Map<String, Object> raise = recommendationFor(advice, "Restaurants");

        assertThat(raise.get("tipus")).isEqualTo(BudgetAdviceService.Kind.PUJAR);
        assertThat((BigDecimal) raise.get("mitjana_real")).isEqualByComparingTo("125");
        assertThat((BigDecimal) raise.get("proposta")).isEqualByComparingTo("125");
        assertThat((BigDecimal) raise.get("diferencia")).isEqualByComparingTo("25");
        assertThat(raise.get("mesos_passats")).isEqualTo(2L);
        assertThat(advice.get("mes_desti")).isEqualTo("2026-11");
        assertThat(advice.get("mesos_mostra")).isEqualTo(List.of("2026-09", "2026-08", "2026-07"));
        assertThat((BigDecimal) advice.get("efecte_net")).isEqualByComparingTo("25");
    }

    @Test
    @DisplayName("Si en sobra tots els mesos, es proposa baixar al màxim que s'hi ha gastat: amb el pla nou cap mes s'hauria passat")
    void lowerWhenAlwaysUnder() {
        given(YearMonth.of(2026, 10), planned(groceries, "300", "100"));
        given(YearMonth.of(2026, 9), node(groceries, "300", "200"));
        given(YearMonth.of(2026, 8), node(groceries, "300", "247"));
        given(YearMonth.of(2026, 7), node(groceries, "300", "230"));

        Map<String, Object> lower = recommendationFor(advice(), "Supermercat");

        assertThat(lower.get("tipus")).isEqualTo(BudgetAdviceService.Kind.BAIXAR);
        assertThat((BigDecimal) lower.get("proposta")).isEqualByComparingTo("250");
        assertThat((BigDecimal) lower.get("diferencia")).isEqualByComparingTo("-50");
    }

    @Test
    @DisplayName("Un pla que s'ajusta al que es gasta, un pla que no s'ha posat a mà o un canvi de menys de 5 € no es toquen")
    void noRecommendation() {
        Map<String, Object> fromRecurring = node(category(3L, "Llum"), "60", "20");
        Map<String, Object> tiny = planned(category(4L, "Transport"), "103", "30");
        given(YearMonth.of(2026, 10), planned(groceries, "300", "100"), fromRecurring, tiny);
        for (YearMonth month : List.of(YearMonth.of(2026, 9), YearMonth.of(2026, 8), YearMonth.of(2026, 7))) {
            given(month, node(groceries, "300", month.getMonthValue() == 8 ? "310" : "290"),
                    node(category(3L, "Llum"), "60", "100"), node(category(4L, "Transport"), "103", "104"));
        }

        Map<String, Object> advice = advice();

        assertThat(recommendationFor(advice, "Supermercat")).isNull();
        assertThat(recommendationFor(advice, "Llum")).isNull();
        // De mitjana 104: arrodonit, 105, només 2 € més.
        assertThat(recommendationFor(advice, "Transport")).isNull();
    }

    @Test
    @DisplayName("Un pla per percentatge es proposa en percentatge, sobre la base d'aquest mes")
    void percentagePlans() {
        Map<String, Object> current = node(groceries, "100", "20");
        current.put("percentatge", new BigDecimal("10"));
        current.put("base_assignacio", new BigDecimal("1000"));
        given(YearMonth.of(2026, 10), current);
        given(YearMonth.of(2026, 9), node(groceries, "100", "40"));
        given(YearMonth.of(2026, 8), node(groceries, "100", "58"));

        Map<String, Object> lower = recommendationFor(advice(), "Supermercat");

        assertThat((BigDecimal) lower.get("proposta")).isEqualByComparingTo("60");
        assertThat((BigDecimal) lower.get("percentatge_actual")).isEqualByComparingTo("10");
        assertThat((BigDecimal) lower.get("percentatge_proposat")).isEqualByComparingTo("6.0");
    }

    @Test
    @DisplayName("Els mesos sense cap moviment no compten, i amb un sol mes no es proposa res")
    void monthsWithoutMovementsAreSkipped() {
        given(YearMonth.of(2026, 10), planned(restaurants, "100", "40"));
        // El setembre no hi ha res: és abans de començar a fer servir l'aplicació.
        given(YearMonth.of(2026, 9), node(restaurants, "100", "0"));
        given(YearMonth.of(2026, 8), node(restaurants, "100", "150"));

        Map<String, Object> advice = advice();

        assertThat(advice.get("mesos_mostra")).isEqualTo(List.of("2026-08"));
        assertThat((List<?>) advice.get("recomanacions")).isEmpty();
    }

    // ============ LA PREVISIÓ ============

    @SuppressWarnings("unchecked")
    private static Map<String, Object> forecastOf(Map<String, Object> advice, String name) {
        return ((List<Map<String, Object>>) advice.get("previsio")).stream()
                .filter(view -> name.equals(view.get("nom")))
                .findFirst()
                .orElseThrow();
    }

    @Test
    @DisplayName("La previsió és el gastat fins avui més el que se sol gastar en els dies que queden")
    void forecastFromHistory() {
        given(YearMonth.of(2026, 10), planned(groceries, "300", "100"), planned(restaurants, "100", "90"));
        given(YearMonth.of(2026, 9), node(groceries, "300", "310"), node(restaurants, "100", "155"));
        given(YearMonth.of(2026, 8), node(groceries, "300", "310"), node(restaurants, "100", "155"));

        Map<String, Object> advice = advice();

        // 100 + 310 × 21/31 = 310.00
        Map<String, Object> groceriesForecast = forecastOf(advice, "Supermercat");
        assertThat((BigDecimal) groceriesForecast.get("previsio")).isEqualByComparingTo("310.00");
        assertThat(groceriesForecast.get("base_previsio")).isEqualTo("HISTORIC");
        assertThat(groceriesForecast.get("estat_previst")).isEqualTo(BudgetReviewService.Status.AVIS);
        // (300 − 100) entre els 22 dies que queden, avui inclòs.
        assertThat((BigDecimal) groceriesForecast.get("disponible_per_dia")).isEqualByComparingTo("9.09");

        // 90 + 155 × 21/31 = 195.00: va camí de passar-se'n.
        Map<String, Object> restaurantsForecast = forecastOf(advice, "Restaurants");
        assertThat((BigDecimal) restaurantsForecast.get("previsio")).isEqualByComparingTo("195.00");
        assertThat(restaurantsForecast.get("estat_previst")).isEqualTo(BudgetReviewService.Status.DESVIAT);
        assertThat(advice.get("dia")).isEqualTo(10);
        assertThat(advice.get("dies_mes")).isEqualTo(31);
    }

    @Test
    @DisplayName("Sense historial, la previsió compta amb el pla; sense pla tampoc, amb el ritme d'aquest mes")
    void forecastWithoutHistory() {
        Map<String, Object> unplanned = node(category(5L, "Oci"), "0", "50");
        given(YearMonth.of(2026, 10), planned(groceries, "310", "100"), unplanned);

        Map<String, Object> advice = advice();

        // 100 + el pla dels dies que queden: 310 × 21/31 = 210.
        Map<String, Object> groceriesForecast = forecastOf(advice, "Supermercat");
        assertThat(groceriesForecast.get("base_previsio")).isEqualTo("PLA");
        assertThat((BigDecimal) groceriesForecast.get("previsio")).isEqualByComparingTo("310.00");
        // 50 en 10 dies: 5 € al dia, 21 dies més.
        Map<String, Object> leisure = forecastOf(advice, "Oci");
        assertThat(leisure.get("base_previsio")).isEqualTo("RITME");
        assertThat((BigDecimal) leisure.get("previsio")).isEqualByComparingTo("155.00");
        assertThat(leisure.get("estat_previst")).isEqualTo(BudgetReviewService.Status.SENSE_PLA);
    }

    @Test
    @DisplayName("Les propostes s'arrodoneixen cap amunt al múltiple de 5 €")
    void roundUp() {
        assertThat(BudgetAdviceService.roundUp(new BigDecimal("301"))).isEqualByComparingTo("305");
        assertThat(BudgetAdviceService.roundUp(new BigDecimal("300"))).isEqualByComparingTo("300");
        assertThat(BudgetAdviceService.roundUp(new BigDecimal("0.01"))).isEqualByComparingTo("5");
        assertThat(BudgetAdviceService.roundUp(BigDecimal.ZERO)).isEqualByComparingTo("0");
    }
}
