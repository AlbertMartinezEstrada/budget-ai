package com.budgetai.backend.service;

import com.budgetai.backend.model.Account;
import com.budgetai.backend.model.Category;
import com.budgetai.backend.model.Transaction;
import com.budgetai.backend.model.TransactionPart;
import com.budgetai.backend.repository.TransactionPartRepository;
import com.budgetai.backend.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * La pestanya General de l'anàlisi, amb moviments fets a mà.
 *
 *   Habitatge (FIXED)   └─ Lloguer, iCloud
 *   Gast mensual        └─ Supermercat, Bars
 *   Altres              ← on cau el que no es classifica
 *   Estalvis (SAVINGS)
 *   Ingressos (INCOME)  └─ Nòmina
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GeneralAnalysisServiceTest {

    private static final YearMonth OCTOBER = YearMonth.of(2026, 10);

    @Mock private TransactionRepository transactionRepository;
    @Mock private TransactionPartRepository partRepository;
    @Mock private CategoryHierarchyService hierarchyService;
    @InjectMocks private GeneralAnalysisService service;

    private final Category housing = category(1L, "Habitatge", null, Category.FIXED);
    private final Category rent = category(2L, "Lloguer", 1L, Category.FIXED);
    private final Category icloud = category(3L, "iCloud", 1L, Category.FIXED);
    private final Category monthly = category(4L, "Gast mensual", null, Category.VARIABLE);
    private final Category groceries = category(5L, "Supermercat", 4L, Category.VARIABLE);
    private final Category bars = category(6L, "Bars", 4L, Category.VARIABLE);
    private final Category others = category(7L, "Altres", null, Category.VARIABLE);
    private final Category savings = category(8L, "Estalvis", null, Category.SAVINGS);
    private final Category income = category(9L, "Ingressos", null, "INCOME");
    private final Category payroll = category(10L, "Nòmina", 9L, null);

    private final Account principal = account(1L, "Compte Principal", "CORRIENTE");
    private final Account tradeRepublic = account(2L, "Trade Republic", "INVERSIONES");

    private final List<Transaction> transactions = new ArrayList<>();
    private final List<TransactionPart> parts = new ArrayList<>();
    private long nextId = 100;

    @BeforeEach
    void givenRepositories() {
        when(hierarchyService.loadTree()).thenReturn(CategoryHierarchyService.treeOf(List.of(
                housing, rent, icloud, monthly, groceries, bars, others, savings, income, payroll)));
        when(transactionRepository.findAll()).thenAnswer(invocation -> transactions);
        when(partRepository.findAll()).thenAnswer(invocation -> parts);
    }

    private static Category category(long id, String name, Long parentId, String costType) {
        Category category = new Category(name);
        category.setId(id);
        category.setParentId(parentId);
        category.setCostType(costType);
        return category;
    }

    private static Account account(long id, String name, String type) {
        Account account = new Account();
        account.setId(id);
        account.setName(name);
        account.setType(type);
        return account;
    }

    private Transaction add(String type, String amount, String date, Category category, String merchant) {
        return add(type, amount, date, category, merchant, principal);
    }

    private Transaction add(String type, String amount, String date, Category category, String merchant, Account account) {
        Transaction transaction = new Transaction();
        transaction.setId(nextId++);
        transaction.setType(type);
        transaction.setAmount(new BigDecimal(amount));
        transaction.setDate(LocalDate.parse(date));
        transaction.setCategory(category);
        transaction.setEmpresa(merchant);
        transaction.setAccount(account);
        transactions.add(transaction);
        return transaction;
    }

    /** Un mes normal: nòmina, lloguer, súper i estalvi. */
    private void normalMonth(YearMonth month, String groceriesAmount) {
        add("INCOME", "2000.00", month.atDay(1).toString(), payroll, "Empresa");
        add("EXPENSE", "550.00", month.atDay(2).toString(), rent, "Lloguer");
        add("EXPENSE", groceriesAmount, month.atDay(10).toString(), groceries, "Mercadona");
        add("EXPENSE", "200.00", month.atDay(3).toString(), savings, "Traspàs");
    }

    @SuppressWarnings("unchecked")
    private static <T> T get(Map<String, Object> map, String key) {
        return (T) map.get(key);
    }

    private static BigDecimal amount(Map<String, Object> map, String key) {
        return (BigDecimal) map.get(key);
    }

    // ============ EL PERÍODE ============

    @Test
    @DisplayName("Sense dates, el mes actual; un mes futur no compta; un rang al revés o de més de 36 mesos es rebutja")
    void ranges() {
        Map<String, Object> current = service.general(null, null, OCTOBER);
        assertThat(current).containsEntry("des_de", "2026-10").containsEntry("fins_a", "2026-10").containsEntry("mesos", 1);

        Map<String, Object> year = service.general(YearMonth.of(2026, 1), YearMonth.of(2026, 12), OCTOBER);
        assertThat(year).containsEntry("fins_a", "2026-10").containsEntry("mesos", 10);

        assertThatThrownBy(() -> service.general(YearMonth.of(2026, 9), YearMonth.of(2026, 7), OCTOBER))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("posterior");
        assertThatThrownBy(() -> service.general(YearMonth.of(2023, 1), YearMonth.of(2026, 10), OCTOBER))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("36 mesos");
    }

    @Test
    @DisplayName("El període anterior és el del mateix llarg just abans, i l'habitual, la mitjana dels mesos amb moviments pel llarg del període")
    void previousAndUsual() {
        // Abril sense res: és d'abans de fer servir l'aplicació.
        normalMonth(YearMonth.of(2026, 5), "300.00");
        normalMonth(YearMonth.of(2026, 6), "340.00");
        normalMonth(YearMonth.of(2026, 7), "320.00");
        normalMonth(YearMonth.of(2026, 8), "280.00");
        normalMonth(YearMonth.of(2026, 9), "310.00");

        Map<String, Object> result = service.general(YearMonth.of(2026, 8), YearMonth.of(2026, 9), OCTOBER);

        Map<String, Object> summary = get(result, "resum");
        assertThat(amount(summary, "total_expense")).isEqualByComparingTo("1690.00");
        assertThat(amount(summary, "total_savings")).isEqualByComparingTo("400.00");

        Map<String, Object> previous = get(result, "anterior");
        assertThat(previous).containsEntry("des_de", "2026-06").containsEntry("fins_a", "2026-07");
        assertThat(amount(previous, "total_expense")).isEqualByComparingTo("1760.00");

        // Maig, juny i juliol (febrer, març i abril no tenen res): de mitjana
        // 870 € de despesa al mes, per dos mesos.
        Map<String, Object> usual = get(result, "habitual");
        assertThat(usual.get("mesos_base")).isEqualTo(3);
        assertThat(amount(usual, "total_expense")).isEqualByComparingTo("1740.00");
        assertThat(amount(usual, "total_income")).isEqualByComparingTo("4000.00");
    }

    @Test
    @DisplayName("Sense cap mes amb moviments abans del període no hi ha habitual")
    void noUsualWithoutHistory() {
        normalMonth(OCTOBER, "300.00");

        assertThat(service.general(OCTOBER, OCTOBER, OCTOBER).get("habitual")).isNull();
    }

    // ============ ESTRUCTURA I EVOLUCIÓ ============

    @Test
    @DisplayName("L'estructura reparteix cada mes entre fixos, variables, estalvi i el que es paga del fons")
    void structure() {
        normalMonth(YearMonth.of(2026, 9), "300.00");
        add("EXPENSE", "60.00", "2026-09-20", savings, "Regal", tradeRepublic);

        Map<String, Object> result = service.general(YearMonth.of(2026, 9), YearMonth.of(2026, 9), OCTOBER);
        List<Map<String, Object>> structure = get(result, "estructura");

        assertThat(structure).hasSize(1);
        Map<String, Object> september = structure.get(0);
        assertThat(september.get("periode")).isEqualTo("2026-09");
        assertThat(amount(september, "ingressos")).isEqualByComparingTo("2000.00");
        assertThat(amount(september, "fixos")).isEqualByComparingTo("550.00");
        assertThat(amount(september, "variables")).isEqualByComparingTo("300.00");
        assertThat(amount(september, "estalvi")).isEqualByComparingTo("200.00");
        assertThat(amount(september, "gastat_del_fons")).isEqualByComparingTo("60.00");
        // El regal surt del que ja s'havia apartat: no resta.
        assertThat(amount(september, "sense_destinar")).isEqualByComparingTo("950.00");

        List<Map<String, Object>> evolution = get(result, "evolucio");
        assertThat(evolution).hasSize(12);
        assertThat(evolution.get(0).get("period")).isEqualTo("2025-10");
        assertThat(evolution.get(11).get("period")).isEqualTo("2026-09");
        assertThat(amount(evolution.get(11), "total_savings")).isEqualByComparingTo("200.00");
    }

    // ============ ON VAN ELS DINERS ============

    @Test
    @DisplayName("Les categories van per blocs i subcategories, de més a menys, amb el percentatge de la despesa i els moviments")
    void categoryTree() {
        normalMonth(OCTOBER, "300.00");
        add("EXPENSE", "50.00", "2026-10-11", bars, "Bar Pepe");
        add("EXPENSE", "25.00", "2026-10-12", null, "Desconegut");
        add("EXPENSE", "75.00", "2026-10-13", others, "Botiga");

        Map<String, Object> result = service.general(OCTOBER, OCTOBER, OCTOBER);
        List<Map<String, Object>> categories = get(result, "categories");

        assertThat(categories).extracting(node -> node.get("nom"))
                .containsExactly("Habitatge", "Gast mensual", "Altres", "Sin categoría");
        Map<String, Object> monthlyNode = categories.get(1);
        assertThat(amount(monthlyNode, "total")).isEqualByComparingTo("350.00");
        assertThat(amount(monthlyNode, "percentatge")).isEqualByComparingTo("35.0");
        assertThat(monthlyNode.get("moviments")).isEqualTo(2);
        assertThat(monthlyNode.get("seccio")).isEqualTo(Category.VARIABLE);
        List<Map<String, Object>> children = get(monthlyNode, "subcategories");
        assertThat(children).extracting(node -> node.get("nom")).containsExactly("Supermercat", "Bars");
        // L'estalvi no és despesa: no hi surt.
        assertThat(categories).extracting(node -> node.get("nom")).doesNotContain("Estalvis");

        // Sense classificar: sense categoria i a «Altres».
        Map<String, Object> unclassified = get(result, "sense_classificar");
        assertThat(amount(unclassified, "total")).isEqualByComparingTo("100.00");
        assertThat(unclassified.get("moviments")).isEqualTo(2);
        assertThat(amount(unclassified, "percentatge")).isEqualByComparingTo("10.0");
    }

    @Test
    @DisplayName("Els comerços per import i per visites; un moviment dividit és una sola visita")
    void merchants() {
        add("EXPENSE", "550.00", "2026-10-02", rent, "Lloguer");
        for (int day = 1; day <= 4; day++) add("EXPENSE", "4.50", "2026-10-0" + (day + 4), bars, "Cafeteria");
        Transaction split = add("EXPENSE", "120.00", "2026-10-09", groceries, "Mercadona");
        parts.add(part(split, "100.00", groceries));
        parts.add(part(split, "20.00", bars));

        Map<String, Object> merchants = get(service.general(OCTOBER, OCTOBER, OCTOBER), "comercos");

        List<Map<String, Object>> byAmount = get(merchants, "per_import");
        assertThat(byAmount).extracting(merchant -> merchant.get("nom")).containsExactly("Lloguer", "Mercadona", "Cafeteria");
        assertThat(byAmount.get(1).get("moviments")).isEqualTo(1);
        assertThat(amount(byAmount.get(1), "total")).isEqualByComparingTo("120.00");
        List<Map<String, Object>> byVisits = get(merchants, "per_visites");
        assertThat(byVisits.get(0).get("nom")).isEqualTo("Cafeteria");
        assertThat(byVisits.get(0).get("moviments")).isEqualTo(4);
    }

    private TransactionPart part(Transaction transaction, String amount, Category category) {
        TransactionPart part = new TransactionPart();
        part.setId(nextId++);
        part.setTransaction(transaction);
        part.setAmount(new BigDecimal(amount));
        part.setCategory(category);
        part.setExcludedFromBudget(false);
        return part;
    }

    @Test
    @DisplayName("La despesa formiga: fins a 10 € i fora dels fixos, amb els comerços on se'n fa més d'una")
    void smallExpenses() {
        add("EXPENSE", "300.00", "2026-10-10", groceries, "Mercadona");
        for (int day = 1; day <= 3; day++) add("EXPENSE", "3.20", "2026-10-1" + day, bars, "Cafeteria");
        add("EXPENSE", "9.90", "2026-10-15", bars, "Forn");
        // Una subscripció petita és un fix, no una despesa formiga.
        add("EXPENSE", "0.99", "2026-10-03", icloud, "Apple");
        add("EXPENSE", "10.01", "2026-10-16", bars, "Forn");

        Map<String, Object> small = get(service.general(OCTOBER, OCTOBER, OCTOBER), "despesa_formiga");

        assertThat(amount(small, "llindar")).isEqualByComparingTo("10");
        assertThat(amount(small, "total")).isEqualByComparingTo("19.50");
        assertThat(small.get("moviments")).isEqualTo(4);
        List<Map<String, Object>> merchants = get(small, "comercos");
        assertThat(merchants).extracting(merchant -> merchant.get("nom")).containsExactly("Cafeteria");
        assertThat(amount(merchants.get(0), "total")).isEqualByComparingTo("9.60");
    }

    // ============ ELS MOVIMENTS D'UNA CATEGORIA ============

    @Test
    @DisplayName("Els moviments d'un bloc són els de totes les seves subcategories, amb l'import de la part, del més nou al més vell")
    void movements() {
        add("EXPENSE", "300.00", "2026-10-10", groceries, "Mercadona");
        Transaction split = add("EXPENSE", "120.00", "2026-10-12", groceries, "Mercadona");
        parts.add(part(split, "100.00", groceries));
        parts.add(part(split, "20.00", bars));
        add("EXPENSE", "550.00", "2026-10-02", rent, "Lloguer");
        add("EXPENSE", "25.00", "2026-10-13", null, "Desconegut");
        add("EXPENSE", "40.00", "2026-09-30", bars, "Bar Pepe");

        List<Map<String, Object>> block = service.movements(4L, OCTOBER, OCTOBER, OCTOBER);
        assertThat(block).extracting(movement -> ((BigDecimal) movement.get("import")).toPlainString())
                .containsExactly("100.00", "20.00", "300.00");
        assertThat(block.get(0).get("es_part")).isEqualTo(true);
        assertThat(block.get(2).get("data")).isEqualTo(LocalDate.of(2026, 10, 10));

        assertThat(service.movements(6L, OCTOBER, OCTOBER, OCTOBER)).hasSize(1);
        // Sense categoria, les que no en tenen.
        assertThat(service.movements(null, OCTOBER, OCTOBER, OCTOBER))
                .extracting(movement -> movement.get("empresa")).containsExactly("Desconegut");
        assertThatThrownBy(() -> service.movements(999L, OCTOBER, OCTOBER, OCTOBER))
                .isInstanceOf(NotFoundException.class);
    }
}
