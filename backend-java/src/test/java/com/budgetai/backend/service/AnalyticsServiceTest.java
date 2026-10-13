package com.budgetai.backend.service;

import com.budgetai.backend.model.Account;
import com.budgetai.backend.model.Category;
import com.budgetai.backend.model.Transaction;
import com.budgetai.backend.model.TransactionPart;
import com.budgetai.backend.repository.TransactionPartRepository;
import com.budgetai.backend.repository.TransactionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Els noms de les claus que retorna aquest servei són contracte amb el
 * frontend. La pàgina d'anàlisi llegia "total_expenses" en plural quan el
 * servei retorna "total_expense": les despeses sortien sempre a 0,00 €, el
 * balanç igualava els ingressos i l'estalvi era sempre del 100%.
 */
@ExtendWith(MockitoExtension.class)
class AnalyticsServiceTest {

    @Mock private TransactionRepository transactionRepository;
    // Sense parts: cap moviment està dividit i tots compten sencers.
    @Mock private TransactionPartRepository transactionPartRepository;
    @InjectMocks private AnalyticsService service;

    private Transaction transaction(String type, String amount, LocalDate date, String categoryName) {
        Transaction transaction = new Transaction();
        transaction.setType(type);
        transaction.setAmount(new BigDecimal(amount));
        transaction.setDate(date);
        if (categoryName != null) {
            Category category = new Category();
            category.setId(1L);
            category.setName(categoryName);
            transaction.setCategory(category);
        }
        return transaction;
    }

    @Test
    @DisplayName("El resum mensual retorna total_expense en singular")
    void monthlySummaryKeyIsSingular() {
        when(transactionRepository.findAll()).thenReturn(List.of(
                transaction("INCOME", "291.21", LocalDate.of(2026, 2, 10), null),
                transaction("EXPENSE", "262.95", LocalDate.of(2026, 2, 15), null)
        ));

        Map<String, Object> summary = service.getMonthlySummary(2026, 2);

        assertThat(summary).containsKeys("period", "total_income", "total_expense", "balance", "transaction_count");
        // El nom que llegia el frontend, i que no ha existit mai.
        assertThat(summary).doesNotContainKey("total_expenses");

        assertThat((BigDecimal) summary.get("total_income")).isEqualByComparingTo("291.21");
        assertThat((BigDecimal) summary.get("total_expense")).isEqualByComparingTo("262.95");
        // Amb double això donava 28.25999999999999.
        assertThat((BigDecimal) summary.get("balance")).isEqualByComparingTo("28.26");
    }

    @Test
    @DisplayName("Els moviments de fora del mes no compten")
    void monthlySummaryFiltersByPeriod() {
        when(transactionRepository.findAll()).thenReturn(List.of(
                transaction("EXPENSE", "100.00", LocalDate.of(2026, 2, 15), null),
                transaction("EXPENSE", "999.00", LocalDate.of(2026, 3, 1), null)
        ));

        Map<String, Object> summary = service.getMonthlySummary(2026, 2);

        assertThat((BigDecimal) summary.get("total_expense")).isEqualByComparingTo("100.00");
        assertThat(summary.get("transaction_count")).isEqualTo(1);
    }

    @Test
    @DisplayName("El desglossament retorna la categoria com a text, no com a objecte")
    void categoryBreakdownReturnsPlainName() {
        when(transactionRepository.findAll()).thenReturn(List.of(
                transaction("EXPENSE", "75.00", LocalDate.of(2026, 2, 10), "Transport"),
                transaction("EXPENSE", "25.00", LocalDate.of(2026, 2, 11), "Transport")
        ));

        List<Map<String, Object>> breakdown = service.getCategoryBreakdown(2026, 2);

        assertThat(breakdown).hasSize(1);
        Map<String, Object> item = breakdown.get(0);

        // El frontend llegia item.category.nom i sempre en sortia
        // "Sin categoría", perquè category ja és la cadena.
        assertThat(item.get("category")).isInstanceOf(String.class);
        assertThat(item.get("category")).isEqualTo("Transport");
        assertThat((BigDecimal) item.get("total")).isEqualByComparingTo("100.00");
        assertThat((BigDecimal) item.get("percentage")).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("La tendència anual té dotze mesos amb les claus period i total_expense")
    void monthlyTrendShape() {
        when(transactionRepository.findAll()).thenReturn(List.of(
                transaction("EXPENSE", "50.00", LocalDate.of(2026, 3, 5), null)
        ));

        List<Map<String, Object>> trend = service.getMonthlyTrend(2026);

        assertThat(trend).hasSize(12);
        // El frontend llegia month/income/expenses; cap dels tres existeix.
        assertThat(trend.get(0)).containsKeys("period", "total_income", "total_expense");
        assertThat(trend.get(0)).doesNotContainKeys("month", "income", "expenses");

        assertThat(trend.get(2).get("period")).isEqualTo("2026-03");
        assertThat((BigDecimal) trend.get(2).get("total_expense")).isEqualByComparingTo("50.00");
        assertThat((BigDecimal) trend.get(0).get("total_expense")).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("Un mes sense moviments no peta i retorna zeros")
    void emptyMonthIsSafe() {
        when(transactionRepository.findAll()).thenReturn(List.of());

        Map<String, Object> summary = service.getMonthlySummary(2026, 1);

        assertThat((BigDecimal) summary.get("total_expense")).isEqualByComparingTo("0");
        assertThat((BigDecimal) summary.get("balance")).isEqualByComparingTo("0");
    }

    // ============ EL QUE NO ÉS NI INGRÉS NI DESPESA ============

    private static Account account(long id, String name) {
        Account account = new Account();
        account.setId(id);
        account.setName(name);
        account.setType("CORRIENTE");
        return account;
    }

    @Test
    @DisplayName("Un traspàs entre comptes propis no surt a l'anàlisi: ni al resum, ni al desglossament, ni a la tendència")
    void transfersDoNotCount() {
        Transaction transfer = transaction("EXPENSE", "1276.00", LocalDate.of(2026, 10, 6), "Estalvis");
        transfer.setAccount(account(1L, "Compte Principal"));
        transfer.setCounterpartAccount(account(2L, "Trade Republic"));
        when(transactionRepository.findAll()).thenReturn(List.of(
                transfer,
                transaction("EXPENSE", "553.33", LocalDate.of(2026, 10, 1), "Lloguer")));

        Map<String, Object> summary = service.getMonthlySummary(2026, 10);
        assertThat((BigDecimal) summary.get("total_expense")).isEqualByComparingTo("553.33");
        assertThat(summary.get("transaction_count")).isEqualTo(1);

        assertThat(service.getCategoryBreakdown(2026, 10)).extracting(item -> item.get("category"))
                .containsExactly("Lloguer");
        assertThat((BigDecimal) service.getMonthlyTrend(2026).get(9).get("total_expense"))
                .isEqualByComparingTo("553.33");
        assertThat((BigDecimal) service.getYearlySummary(2026).get("total_expense"))
                .isEqualByComparingTo("553.33");
    }

    @Test
    @DisplayName("El que està marcat com a «no comptar» tampoc surt a l'anàlisi")
    void excludedDoesNotCount() {
        Transaction excluded = transaction("INCOME", "200.00", LocalDate.of(2026, 10, 3), null);
        excluded.setExcludedFromBudget(true);
        when(transactionRepository.findAll()).thenReturn(List.of(
                excluded,
                transaction("INCOME", "1500.00", LocalDate.of(2026, 10, 1), null)));

        Map<String, Object> summary = service.getMonthlySummary(2026, 10);

        assertThat((BigDecimal) summary.get("total_income")).isEqualByComparingTo("1500.00");
        assertThat(summary.get("transaction_count")).isEqualTo(1);
    }

    @Test
    @DisplayName("D'un moviment dividit, la part marcada com a «no comptar» no suma, i el moviment compta una vegada")
    void excludedPartDoesNotCount() {
        Transaction split = transaction("EXPENSE", "500.00", LocalDate.of(2026, 10, 5), null);
        split.setId(10L);
        TransactionPart counted = new TransactionPart();
        counted.setId(1L);
        counted.setTransaction(split);
        counted.setAmount(new BigDecimal("460.00"));
        counted.setExcludedFromBudget(false);
        TransactionPart excluded = new TransactionPart();
        excluded.setId(2L);
        excluded.setTransaction(split);
        excluded.setAmount(new BigDecimal("40.00"));
        excluded.setExcludedFromBudget(true);
        when(transactionRepository.findAll()).thenReturn(List.of(split));
        when(transactionPartRepository.findAll()).thenReturn(List.of(counted, excluded));

        Map<String, Object> summary = service.getMonthlySummary(2026, 10);

        assertThat((BigDecimal) summary.get("total_expense")).isEqualByComparingTo("460.00");
        assertThat(summary.get("transaction_count")).isEqualTo(1);
    }

    @Test
    @DisplayName("L'estalviat és el que passa als comptes d'estalvi menys el que en torna, i no és despesa")
    void savingsAreTheNetTransfersIntoSavings() {
        Account principal = account(1L, "Compte Principal");
        Account revolut = account(2L, "Revolut");
        Account tradeRepublic = account(3L, "Trade Republic");
        tradeRepublic.setType("INVERSIONES");

        Transaction salary = transaction("INCOME", "2000.00", LocalDate.of(2026, 10, 1), null);
        Transaction toSavings = transaction("EXPENSE", "500.00", LocalDate.of(2026, 10, 6), "Estalvis");
        toSavings.setAccount(principal);
        toSavings.setCounterpartAccount(tradeRepublic);
        Transaction back = transaction("INCOME", "100.00", LocalDate.of(2026, 10, 20), "Estalvis");
        back.setAccount(principal);
        back.setCounterpartAccount(tradeRepublic);
        Transaction toRevolut = transaction("EXPENSE", "80.00", LocalDate.of(2026, 10, 7), null);
        toRevolut.setAccount(principal);
        toRevolut.setCounterpartAccount(revolut);
        toRevolut.setExcludedFromBudget(true);
        when(transactionRepository.findAll()).thenReturn(List.of(salary, toSavings, back, toRevolut));

        Map<String, Object> summary = service.getMonthlySummary(2026, 10);

        assertThat((BigDecimal) summary.get("total_savings")).isEqualByComparingTo("400.00");
        assertThat((BigDecimal) summary.get("savings_rate")).isEqualByComparingTo("20.00");
        assertThat((BigDecimal) summary.get("total_expense")).isEqualByComparingTo("0");
        assertThat((BigDecimal) summary.get("total_income")).isEqualByComparingTo("2000.00");
        assertThat((BigDecimal) service.getYearlySummary(2026).get("total_savings")).isEqualByComparingTo("400.00");
        assertThat((BigDecimal) service.getMonthlyTrend(2026).get(9).get("total_savings")).isEqualByComparingTo("400.00");
        // Sense ingressos no hi ha taxa: no és 0 %, és que no se sap.
        assertThat(service.getMonthlySummary(2026, 11).get("savings_rate")).isNull();
    }
}
