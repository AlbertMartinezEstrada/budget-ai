package com.budgetai.backend.service;

import com.budgetai.backend.service.BudgetReviewService.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * El semàfor del pla contra el real, i el rang de mesos que es compara. Amb el
 * pressupost de debò pel mig, a BudgetReviewIntegrationTest.
 */
@ExtendWith(MockitoExtension.class)
class BudgetReviewServiceTest {

    private static final YearMonth OCTOBER = YearMonth.of(2026, 10);

    @Mock private BudgetService budgetService;
    @InjectMocks private BudgetReviewService service;

    private static Status status(String section, String plan, String real) {
        return BudgetReviewService.statusOf(section, new BigDecimal(plan), new BigDecimal(real), false, false);
    }

    // ============ EL SEMÀFOR ============

    @Test
    @DisplayName("A les despeses, no arribar al pla és estar bé; passar-se'n poc, un avís; passar-se'n més, desviat")
    void expenses() {
        assertThat(status("VARIABLE", "300", "290")).isEqualTo(Status.BE);
        assertThat(status("VARIABLE", "300", "300")).isEqualTo(Status.BE);
        // Fins al 10 % del pla.
        assertThat(status("VARIABLE", "300", "330")).isEqualTo(Status.AVIS);
        assertThat(status("VARIABLE", "300", "330.01")).isEqualTo(Status.DESVIAT);
        assertThat(status("FIXED", "553.33", "600")).isEqualTo(Status.AVIS);
    }

    @Test
    @DisplayName("En un pla petit, l'avís arriba com a mínim a 5 €: 3 € de més en 20 € no és cap desviament")
    void smallPlansHaveAMinimumTolerance() {
        assertThat(status("VARIABLE", "20", "25")).isEqualTo(Status.AVIS);
        assertThat(status("VARIABLE", "20", "25.01")).isEqualTo(Status.DESVIAT);
    }

    @Test
    @DisplayName("A l'estalvi i als ingressos, el que va malament és quedar-se'n curt")
    void savingsAndIncome() {
        assertThat(status("SAVINGS", "200", "250")).isEqualTo(Status.BE);
        assertThat(status("SAVINGS", "200", "185")).isEqualTo(Status.AVIS);
        assertThat(status("SAVINGS", "200", "150")).isEqualTo(Status.DESVIAT);
        assertThat(status("INCOME", "2000", "1700")).isEqualTo(Status.DESVIAT);
    }

    @Test
    @DisplayName("Sense pla: si hi ha moviments, és SENSE_PLA; si no n'hi ha, no hi ha res a jutjar")
    void withoutPlan() {
        assertThat(status("VARIABLE", "0", "45")).isEqualTo(Status.SENSE_PLA);
        assertThat(status("VARIABLE", "0", "0")).isNull();
    }

    @Test
    @DisplayName("Un càrrec trimestral o anual que passa del prorrateig és puntual, no un desviament")
    void unevenCharges() {
        assertThat(BudgetReviewService.statusOf("FIXED", new BigDecimal("50"), new BigDecimal("600"), true, false))
                .isEqualTo(Status.CARREC_PUNTUAL);
        // Els mesos que no cau, està bé.
        assertThat(BudgetReviewService.statusOf("FIXED", new BigDecimal("50"), BigDecimal.ZERO, true, false))
                .isEqualTo(Status.BE);
    }

    @Test
    @DisplayName("Al mes en curs, l'estalvi que encara no hi és va en curs; la despesa que ja s'ha passat, no")
    void monthInProgress() {
        assertThat(BudgetReviewService.statusOf("SAVINGS", new BigDecimal("200"), BigDecimal.ZERO, false, true))
                .isEqualTo(Status.EN_CURS);
        assertThat(BudgetReviewService.statusOf("VARIABLE", new BigDecimal("100"), new BigDecimal("150"), false, true))
                .isEqualTo(Status.DESVIAT);
    }

    // ============ EL RANG ============

    private void givenEmptyMonths() {
        when(budgetService.getMonthlySummary(anyInt(), anyInt())).thenReturn(Map.of("seccions", List.of()));
    }

    @Test
    @DisplayName("Sense dates, els sis mesos fins al mes actual")
    void defaultRange() {
        givenEmptyMonths();

        Map<String, Object> review = service.review(null, null, OCTOBER);

        assertThat(review).containsEntry("des_de", "2026-05").containsEntry("fins_a", "2026-10");
        assertThat((List<?>) review.get("mesos")).hasSize(6);
        verify(budgetService, times(6)).getMonthlySummary(anyInt(), anyInt());
    }

    @Test
    @DisplayName("Un mes que encara no ha arribat no es compara: el rang s'acaba al mes actual")
    void futureMonthsAreCut() {
        givenEmptyMonths();

        Map<String, Object> review = service.review(YearMonth.of(2026, 9), YearMonth.of(2027, 3), OCTOBER);

        assertThat(review).containsEntry("fins_a", "2026-10");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> months = (List<Map<String, Object>>) review.get("mesos");
        assertThat(months).extracting(month -> month.get("periode")).containsExactly("2026-09", "2026-10");
        assertThat(months).extracting(month -> month.get("mes_en_curs")).containsExactly(false, true);
    }

    @Test
    @DisplayName("Un rang al revés o de més de dos anys es rebutja amb un missatge que ho diu, sense calcular res")
    void invalidRanges() {
        assertThatThrownBy(() -> service.review(YearMonth.of(2026, 9), YearMonth.of(2026, 3), OCTOBER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("posterior");
        assertThatThrownBy(() -> service.review(YearMonth.of(2024, 1), YearMonth.of(2026, 10), OCTOBER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("24 mesos");
        verify(budgetService, never()).getMonthlySummary(anyInt(), anyInt());
    }
}
