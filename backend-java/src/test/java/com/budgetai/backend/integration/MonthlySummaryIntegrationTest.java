package com.budgetai.backend.integration;

import com.budgetai.backend.model.*;
import com.budgetai.backend.repository.*;
import com.budgetai.backend.service.BudgetService;
import com.budgetai.backend.service.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cost de vida contra caixa, amb la base de dades pel mig.
 *
 * L'escenari és el del plantejament: un grup "Cotxe" amb una assegurança de
 * 600 € l'any (fixa) i el combustible (variable). El març cau el càrrec de
 * l'assegurança.
 *
 *   COST DE VIDA del març = 50 (prorrateig) + 45 (combustible real) =  95
 *   CAIXA        del març = 600 (càrrec real) + 45                  = 645
 */
class MonthlySummaryIntegrationTest extends AbstractIntegrationTest {

    @Autowired private BudgetService budgetService;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private RecurringTransactionRepository recurringRepository;
    @Autowired private BudgetRepository budgetRepository;
    @Autowired private MonthlyIncomeRepository monthlyIncomeRepository;
    @Autowired private SettingsService settingsService;

    private Long groupId;
    private Long fixedLeafId;
    private Long variableLeafId;

    @BeforeEach
    void setUp() {
        transactionRepository.deleteAll();
        recurringRepository.deleteAll();
        budgetRepository.deleteAll();
        monthlyIncomeRepository.deleteAll();
        // La fila de settings és única i sobreviu entre tests: si no es
        // reinicia, el sou que fixa un test s'aplica als següents.
        settingsService.updateSettings(settingsWithIncome("-1"));
        categoryRepository.deleteAll();

        Category group = saveCategory("Cotxe", null, null);
        groupId = group.getId();
        fixedLeafId = saveCategory("Assegurança", groupId, Category.FIXED).getId();
        variableLeafId = saveCategory("Combustible", groupId, Category.VARIABLE).getId();

        // 600 € l'any: 50 € al mes de cost de vida.
        saveRecurring("Assegurança cotxe", "600.00", "ANUAL", fixedLeafId);

        // El càrrec real de l'assegurança cau al març.
        saveTransaction("600.00", LocalDate.of(2026, 3, 15), fixedLeafId, "h-assegurança");
        // I aquest mes s'han gastat 45 € de combustible.
        saveTransaction("45.00", LocalDate.of(2026, 3, 20), variableLeafId, "h-combustible");
    }

    private Category saveCategory(String name, Long parentId, String costType) {
        Category category = new Category(name);
        category.setParentId(parentId);
        category.setCostType(costType);
        return categoryRepository.save(category);
    }

    private void saveRecurring(String name, String amount, String frequency, Long categoryId) {
        RecurringTransaction rt = new RecurringTransaction();
        rt.setName(name);
        rt.setAmount(new BigDecimal(amount));
        rt.setFrequency(frequency);
        rt.setType("EXPENSE");
        rt.setNextDate(LocalDate.of(2026, 3, 15));
        rt.setCategory(categoryRepository.findById(categoryId).orElseThrow());
        rt.setActive(true);
        recurringRepository.save(rt);
    }

    private void saveTransaction(String amount, LocalDate date, Long categoryId, String hash) {
        Transaction t = new Transaction();
        t.setAmount(new BigDecimal(amount));
        t.setDate(date);
        t.setType("EXPENSE");
        t.setCategory(categoryRepository.findById(categoryId).orElseThrow());
        t.setVerificationHash(hash);
        transactionRepository.save(t);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> groupsOf(int year, int month) {
        return (List<Map<String, Object>>) budgetService.getMonthlySummary(year, month).get("grups");
    }

    private Map<String, Object> groupNode() {
        return groupsOf(2026, 3).stream()
                .filter(n -> "Cotxe".equals(((Category) n.get("categoria")).getName()))
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> leafNode(String name) {
        return ((List<Map<String, Object>>) groupNode().get("subcategories")).stream()
                .filter(n -> name.equals(((Category) n.get("categoria")).getName()))
                .findFirst()
                .orElseThrow();
    }

    @Test
    @DisplayName("El cost de vida del grup reparteix el fix i suma el variable real")
    void groupCostOfLiving() {
        assertThat((BigDecimal) groupNode().get("cost_vida_real"))
                .isEqualByComparingTo("95.00");
    }

    @Test
    @DisplayName("La caixa del grup és el que ha sortit del compte, amb el càrrec sencer")
    void groupCash() {
        assertThat((BigDecimal) groupNode().get("caixa_real"))
                .isEqualByComparingTo("645.00");
    }

    @Test
    @DisplayName("S'avisa que aquest mes ha caigut un càrrec fix puntual")
    void oneOffChargeIsFlagged() {
        // Sense aquesta marca, un pic de caixa de 600 € sembla un error.
        assertThat(groupNode().get("carrec_puntual_aquest_mes")).isEqualTo(true);
        assertThat(leafNode("Assegurança").get("carrec_puntual_aquest_mes")).isEqualTo(true);
        assertThat(leafNode("Combustible").get("carrec_puntual_aquest_mes")).isEqualTo(false);
    }

    @Test
    @DisplayName("Una fulla fixa val el prorrateig al cost de vida i el càrrec real a la caixa")
    void fixedLeafSplitsTheTwoViews() {
        Map<String, Object> leaf = leafNode("Assegurança");

        assertThat((BigDecimal) leaf.get("prorrateig_mensual")).isEqualByComparingTo("50.00");
        assertThat((BigDecimal) leaf.get("cost_vida_real")).isEqualByComparingTo("50.00");
        assertThat((BigDecimal) leaf.get("caixa_real")).isEqualByComparingTo("600.00");
    }

    @Test
    @DisplayName("Una fulla variable val el mateix a les dues vistes")
    void variableLeafIsTheSameInBothViews() {
        Map<String, Object> leaf = leafNode("Combustible");

        assertThat((BigDecimal) leaf.get("cost_vida_real")).isEqualByComparingTo("45.00");
        assertThat((BigDecimal) leaf.get("caixa_real")).isEqualByComparingTo("45.00");
    }

    @Test
    @DisplayName("Un mes sense el càrrec manté el cost de vida i baixa la caixa")
    void quietMonthKeepsTheProratedCost() {
        // L'abril no cau l'assegurança i no hi ha combustible.
        Map<String, Object> april = groupsOf(2026, 4).stream()
                .filter(n -> "Cotxe".equals(((Category) n.get("categoria")).getName()))
                .findFirst().orElseThrow();

        // El cost de viure no canvia perquè el rebut caigui un altre mes.
        assertThat((BigDecimal) april.get("cost_vida_real")).isEqualByComparingTo("50.00");
        assertThat((BigDecimal) april.get("caixa_real")).isEqualByComparingTo("0");
        assertThat(april.get("carrec_puntual_aquest_mes")).isEqualTo(false);
    }

    @Test
    @DisplayName("El sostre d'una variable entra al pla; el d'un fix és el prorrateig")
    void planCombinesCeilingsAndProration() {
        Budget budget = new Budget();
        budget.setCategory(categoryRepository.findById(variableLeafId).orElseThrow());
        budget.setLimitAmount(new BigDecimal("80.00"));
        budget.setPeriodStart(LocalDate.of(2026, 3, 1));
        budget.setPeriodEnd(LocalDate.of(2026, 3, 31));
        budget.setActive(true);
        budgetRepository.save(budget);

        // 50 del fix prorratejat + 80 de sostre del variable.
        assertThat((BigDecimal) groupNode().get("cost_vida_pla"))
                .isEqualByComparingTo("130.00");
    }

    @Test
    @DisplayName("El gasto d'un pressupost de grup agrega totes les seves fulles")
    void groupBudgetAggregatesLeaves() {
        Budget budget = new Budget();
        budget.setCategory(categoryRepository.findById(groupId).orElseThrow());
        budget.setLimitAmount(new BigDecimal("700.00"));
        budget.setPeriodStart(LocalDate.of(2026, 3, 1));
        budget.setPeriodEnd(LocalDate.of(2026, 3, 31));
        budget.setActive(true);
        budgetRepository.save(budget);

        Budget stored = budgetService.getAllBudgets().stream()
                .filter(b -> b.getCategory().getId().equals(groupId))
                .findFirst().orElseThrow();

        // 600 + 45: el grup no té moviments propis, els hereta de les fulles.
        assertThat(stored.getCurrentSpent()).isEqualByComparingTo("645.00");
    }

    @Test
    @DisplayName("Sense cap bloc fix, el bot dels variables és el sou sencer")
    void percentageBudgetUsesTheSalary() {
        settingsService.updateSettings(settingsWithIncome("2000.00"));

        Budget budget = new Budget();
        budget.setCategory(categoryRepository.findById(variableLeafId).orElseThrow());
        // quantitat_limit és NOT NULL: hi va l'últim import calculat.
        budget.setLimitAmount(new BigDecimal("200.00"));
        budget.setPercentage(new BigDecimal("10"));
        budget.setPeriodStart(LocalDate.of(2026, 3, 1));
        budget.setPeriodEnd(LocalDate.of(2026, 3, 31));
        budget.setActive(true);
        budgetRepository.save(budget);

        Map<String, Object> summary = budgetService.getMonthlySummary(2026, 3);

        assertThat((BigDecimal) summary.get("sou_base")).isEqualByComparingTo("2000.00");
        assertThat(summary.get("sou_base_origen")).isEqualTo("PER_DEFECTE");
        // "Cotxe" barreja fixos i variables, així que va sencer a la secció
        // variable i no descompta res del bot: 10% de 2000 = 200, i el fix hi
        // suma els seus 50 prorratejats.
        assertThat((BigDecimal) groupNode().get("cost_vida_pla")).isEqualByComparingTo("250.00");
    }

    @Test
    @DisplayName("El sou d'un mes concret canvia el sostre d'aquell mes")
    void monthOverrideChangesTheCeiling() {
        settingsService.updateSettings(settingsWithIncome("2000.00"));
        monthlyIncomeRepository.save(new MonthlyIncome("2026-03", new BigDecimal("4000.00")));

        Budget budget = new Budget();
        budget.setCategory(categoryRepository.findById(variableLeafId).orElseThrow());
        budget.setLimitAmount(new BigDecimal("200.00"));
        budget.setPercentage(new BigDecimal("10"));
        budget.setPeriodStart(LocalDate.of(2026, 3, 1));
        budget.setPeriodEnd(LocalDate.of(2026, 3, 31));
        budget.setActive(true);
        budgetRepository.save(budget);

        Map<String, Object> summary = budgetService.getMonthlySummary(2026, 3);

        assertThat(summary.get("sou_base_origen")).isEqualTo("MES");
        // 10% de 4000 = 400, no els 200 de quantitat_limit.
        assertThat((BigDecimal) groupNode().get("cost_vida_pla")).isEqualByComparingTo("450.00");
    }

    @Test
    @DisplayName("Sense sou configurat, un pressupost per percentatge no posa sostre")
    void percentageWithoutSalaryIsIgnored() {
        Budget budget = new Budget();
        budget.setCategory(categoryRepository.findById(variableLeafId).orElseThrow());
        budget.setLimitAmount(new BigDecimal("200.00"));
        budget.setPercentage(new BigDecimal("10"));
        budget.setPeriodStart(LocalDate.of(2026, 3, 1));
        budget.setPeriodEnd(LocalDate.of(2026, 3, 31));
        budget.setActive(true);
        budgetRepository.save(budget);

        // Només el prorrateig del fix: el percentatge no dona cap xifra, i
        // ensenyar-ne una d'inventada seria pitjor que no ensenyar-ne cap.
        assertThat((BigDecimal) groupNode().get("cost_vida_pla")).isEqualByComparingTo("50.00");
    }

    @Test
    @DisplayName("El resum diu quant sou s'ha repartit i quins ingressos reals hi ha")
    void summaryReportsAssignedShareAndRealIncome() {
        settingsService.updateSettings(settingsWithIncome("2000.00"));

        Budget budget = new Budget();
        budget.setCategory(categoryRepository.findById(groupId).orElseThrow());
        budget.setLimitAmount(new BigDecimal("600.00"));
        budget.setPercentage(new BigDecimal("30"));
        budget.setPeriodStart(LocalDate.of(2026, 3, 1));
        budget.setPeriodEnd(LocalDate.of(2026, 3, 31));
        budget.setActive(true);
        budgetRepository.save(budget);

        Map<String, Object> summary = budgetService.getMonthlySummary(2026, 3);

        assertThat((BigDecimal) summary.get("percentatge_assignat")).isEqualByComparingTo("30");
        // Al març només hi ha despeses, cap ingrés.
        assertThat((BigDecimal) summary.get("ingressos_reals")).isEqualByComparingTo("0");
    }

    // ============ EL REPARTIMENT EN CASCADA ============

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> sectionsOf(int year, int month) {
        return (List<Map<String, Object>>) budgetService.getMonthlySummary(year, month).get("seccions");
    }

    private Map<String, Object> section(String type) {
        return sectionsOf(2026, 3).stream()
                .filter(s -> type.equals(s.get("tipus")))
                .findFirst()
                .orElseThrow();
    }

    private void saveBudget(Long categoryId, String amount, String percentage) {
        Budget budget = new Budget();
        budget.setCategory(categoryRepository.findById(categoryId).orElseThrow());
        // quantitat_limit és NOT NULL fins i tot quan mana el percentatge.
        budget.setLimitAmount(new BigDecimal(amount));
        if (percentage != null) budget.setPercentage(new BigDecimal(percentage));
        budget.setPeriodStart(LocalDate.of(2026, 3, 1));
        budget.setPeriodEnd(LocalDate.of(2026, 3, 31));
        budget.setActive(true);
        budgetRepository.save(budget);
    }

    @Test
    @DisplayName("El bot dels variables és el sou menys els blocs fixos")
    void variablePotIsWhatIsLeftAfterFixed() {
        settingsService.updateSettings(settingsWithIncome("2000.00"));
        // Un bloc de primer nivell amb totes les fulles fixes va a la secció
        // de fixos i es menja la seva part del sou abans que ningú.
        Long rent = saveCategory("Lloguer", null, Category.FIXED).getId();
        saveBudget(rent, "800.00", null);

        assertThat((BigDecimal) section("FIXED").get("base")).isEqualByComparingTo("2000.00");
        assertThat((BigDecimal) section("FIXED").get("assignat")).isEqualByComparingTo("800.00");
        assertThat((BigDecimal) section("FIXED").get("percentatge_del_sou")).isEqualByComparingTo("40.00");

        // 2000 − 800: és el que queda per repartir entre els variables.
        assertThat((BigDecimal) section("VARIABLE").get("base")).isEqualByComparingTo("1200.00");
    }

    @Test
    @DisplayName("Un percentatge d'una subsecció es mesura contra el seu bloc, no contra el sou")
    void childPercentageIsMeasuredAgainstItsParentPot() {
        settingsService.updateSettings(settingsWithIncome("2000.00"));
        saveBudget(groupId, "600.00", null);        // el bloc es queda 600 €
        saveBudget(variableLeafId, "0.00", "25");   // i el combustible, un quart

        // 25% de 600 = 150. Amb la regla antiga haurien sortit 500 (25% de 2000).
        assertThat((BigDecimal) leafNode("Combustible").get("cost_vida_pla"))
                .isEqualByComparingTo("150.00");
        assertThat((BigDecimal) leafNode("Combustible").get("base_assignacio"))
                .isEqualByComparingTo("600.00");
        // I el que en queda per repartir: 600 − 50 del fix − 150.
        assertThat((BigDecimal) groupNode().get("restant")).isEqualByComparingTo("400.00");
    }

    @Test
    @DisplayName("Un import exacte també diu quin percentatge representa")
    void exactAmountsReportTheirShare() {
        settingsService.updateSettings(settingsWithIncome("2000.00"));
        saveBudget(groupId, "600.00", null);

        // Perquè es puguin comparar blocs fixats per import amb blocs fixats
        // per percentatge sense fer el càlcul de cap.
        assertThat((BigDecimal) groupNode().get("percentatge_efectiu")).isEqualByComparingTo("30.00");
        assertThat((BigDecimal) groupNode().get("percentatge_del_sou")).isEqualByComparingTo("30.00");
    }

    @Test
    @DisplayName("El percentatge repartit surt dels euros, no de sumar percentatges de nivells diferents")
    void assignedShareComesFromTheAmounts() {
        settingsService.updateSettings(settingsWithIncome("2000.00"));
        saveBudget(groupId, "600.00", null);
        saveBudget(variableLeafId, "0.00", "25");

        Map<String, Object> summary = budgetService.getMonthlySummary(2026, 3);

        // El bloc mana sobre els seus fills: el repartit són els seus 600, no
        // 600 + 150. Sumar el 25% del combustible amb el 30% del bloc donaria
        // 55% del sou i seria mentida.
        assertThat((BigDecimal) summary.get("total_assignat")).isEqualByComparingTo("600.00");
        assertThat((BigDecimal) summary.get("percentatge_assignat")).isEqualByComparingTo("30.00");
    }

    @Test
    @DisplayName("Un bloc pot declarar que va a fixos encara que barregi fulles variables")
    void aBlockCanDeclareItsSection() {
        settingsService.updateSettings(settingsWithIncome("2000.00"));

        // El cas real: "Llar" és una despesa fixa, però la llum de dins es
        // mesura pel consum. Sense declarar-ho, una sola fulla variable
        // s'enduia el bloc sencer —lloguer inclòs— a la secció de variables.
        Category home = saveCategory("Llar", null, Category.FIXED);
        Long rent = saveCategory("Casa", home.getId(), Category.FIXED).getId();
        saveCategory("Llum", home.getId(), Category.VARIABLE);
        saveBudget(rent, "800.00", null);

        assertThat(section("FIXED").get("grups").toString()).contains("Llar");
        assertThat((BigDecimal) section("FIXED").get("assignat")).isEqualByComparingTo("800.00");
        // I el bot dels variables ja descompta el bloc sencer.
        assertThat((BigDecimal) section("VARIABLE").get("base")).isEqualByComparingTo("1200.00");
    }

    @Test
    @DisplayName("Sense declarar-la, la secció segueix sortint de les fulles")
    void anUndeclaredBlockStillDerivesItsSection() {
        settingsService.updateSettings(settingsWithIncome("2000.00"));

        // "Cotxe" no declara res i barreja, així que va a variables i no
        // descompta res del bot.
        assertThat(section("VARIABLE").get("grups").toString()).contains("Cotxe");
        assertThat((BigDecimal) section("VARIABLE").get("base")).isEqualByComparingTo("2000.00");
    }

    private void saveIncome(String amount, Long categoryId, String hash) {
        Transaction t = new Transaction();
        t.setAmount(new BigDecimal(amount));
        t.setDate(LocalDate.of(2026, 3, 10));
        t.setType("INCOME");
        t.setCategory(categoryRepository.findById(categoryId).orElseThrow());
        t.setVerificationHash(hash);
        transactionRepository.save(t);
    }

    @Test
    @DisplayName("Els ingressos van a la seva secció i es mesuren pel que ha entrat")
    void incomeLivesInItsOwnSection() {
        settingsService.updateSettings(settingsWithIncome("2000.00"));

        Category income = saveCategory("Ingressos", null, "INCOME");
        Long payroll = saveCategory("Nòmina", income.getId(), null).getId();
        saveIncome("2000.00", payroll, "h-nomina");

        // A la secció de despeses no hi pinta res: no és una manera de gastar.
        assertThat(section("VARIABLE").get("grups").toString()).doesNotContain("Ingressos");
        // I una fulla d'ingrés val el que ha entrat, no el que s'hi ha gastat,
        // que és sempre zero i la deixava a zero permanentment.
        assertThat((BigDecimal) section("INCOME").get("real")).isEqualByComparingTo("2000.00");
    }

    @Test
    @DisplayName("El que es reparteix és la suma dels ingressos, amb el sou com un bloc més")
    void whatIsDistributedIsTheSumOfIncome() {
        Category income = saveCategory("Ingressos", null, "INCOME");
        Long payroll = saveCategory("Nòmina", income.getId(), null).getId();
        Long gifts = saveCategory("Regals i premis", income.getId(), null).getId();

        saveBudget(payroll, "2000.00", null);   // la nòmina que s'espera
        saveIncome("500.00", gifts, "h-regal"); // i un regal que ha arribat

        Map<String, Object> summary = budgetService.getMonthlySummary(2026, 3);

        // 2000 de nòmina prevista + 500 de regal rebut.
        assertThat((BigDecimal) summary.get("total_disponible")).isEqualByComparingTo("2500.00");
        assertThat(summary.get("total_disponible_origen")).isEqualTo("INGRESSOS");
        // Cap bloc fix, així que el bot dels variables és tot el disponible.
        assertThat((BigDecimal) section("VARIABLE").get("base")).isEqualByComparingTo("2500.00");
    }

    @Test
    @DisplayName("Una previsió i el seu moviment real no es compten dues vegades")
    void forecastAndActualAreTheSameMoney() {
        Category income = saveCategory("Ingressos", null, "INCOME");
        Long payroll = saveCategory("Nòmina", income.getId(), null).getId();
        saveBudget(payroll, "2000.00", null);
        saveIncome("2100.00", payroll, "h-nomina");

        Map<String, Object> summary = budgetService.getMonthlySummary(2026, 3);

        // La previsió i el moviment són la mateixa cosa vista dos cops: compta
        // el més gran, no la suma. Sumar-los donaria 4100 € inexistents.
        assertThat((BigDecimal) summary.get("total_disponible")).isEqualByComparingTo("2100.00");
        assertThat((BigDecimal) summary.get("ingressos_previstos")).isEqualByComparingTo("2000.00");
        assertThat((BigDecimal) summary.get("ingressos_reals")).isEqualByComparingTo("2100.00");
    }

    @Test
    @DisplayName("Sense secció d'ingressos configurada, s'aplica el sou de referència")
    void theSalaryIsTheFallback() {
        settingsService.updateSettings(settingsWithIncome("2000.00"));

        // Sense això la pantalla es quedaria morta fins a donar d'alta els
        // blocs d'ingrés, i el sou de referència ja diu quant hi ha.
        Map<String, Object> summary = budgetService.getMonthlySummary(2026, 3);

        assertThat((BigDecimal) summary.get("total_disponible")).isEqualByComparingTo("2000.00");
        assertThat(summary.get("total_disponible_origen")).isEqualTo("SOU");
    }

    @Test
    @DisplayName("Copiar el mes anterior no trepitja el que ja hi ha assignat")
    void copyingKeepsWhatIsAlreadyThere() {
        saveBudget(variableLeafId, "0.00", "10");           // març
        budgetService.copyFromPreviousMonth(2026, 4);       // a l'abril

        // El percentatge viatja; l'import el recalcula el mes destí.
        assertThat(budgetService.getAllBudgets())
                .filteredOn(b -> b.getPeriodStart().equals(LocalDate.of(2026, 4, 1)))
                .singleElement()
                .extracting(Budget::getPercentage)
                .isEqualTo(new BigDecimal("10.00"));

        // Cridar-ho dues vegades no duplica res.
        assertThat(budgetService.copyFromPreviousMonth(2026, 4).get("copiats")).isEqualTo(0);
    }

    @Test
    @DisplayName("Una previsió que encara no ha arribat segueix comptant")
    void aForecastThatHasNotArrivedStillCounts() {
        Category income = saveCategory("Ingressos", null, "INCOME");
        Long payroll = saveCategory("Nòmina", income.getId(), null).getId();
        saveBudget(payroll, "2000.00", null);
        // Cap moviment d'ingrés: la nòmina del març encara no s'ha importat.

        Map<String, Object> summary = budgetService.getMonthlySummary(2026, 3);

        assertThat((BigDecimal) summary.get("ingressos_reals")).isEqualByComparingTo("0");
        // Si manessin els ingressos reals, el pla del mes seria de zero euros
        // fins a importar l'extracte. La previsió és el terra.
        assertThat((BigDecimal) summary.get("total_disponible")).isEqualByComparingTo("2000.00");
        // I surt de la secció d'ingressos, no del sou de referència: aquí no
        // n'hi ha cap de configurat.
        assertThat(summary.get("total_disponible_origen")).isEqualTo("INGRESSOS");
    }

    @Test
    @DisplayName("Una fulla fixa amb import assignat val l'import, no el prorrateig")
    void fixedLeafPrefersTheAssignedAmount() {
        // El prorrateig només cobreix el que hi ha donat d'alta com a
        // recurrent; si l'usuari hi posa una xifra, mana la seva.
        saveBudget(fixedLeafId, "70.00", null);

        assertThat((BigDecimal) leafNode("Assegurança").get("cost_vida_pla"))
                .isEqualByComparingTo("70.00");
        // El cost real segueix sortint del rebut, no del pla.
        assertThat((BigDecimal) leafNode("Assegurança").get("cost_vida_real"))
                .isEqualByComparingTo("50.00");
    }

    private Settings settingsWithIncome(String amount) {
        Settings settings = new Settings();
        settings.setExpectedMonthlyIncome(new BigDecimal(amount));
        return settings;
    }
}
