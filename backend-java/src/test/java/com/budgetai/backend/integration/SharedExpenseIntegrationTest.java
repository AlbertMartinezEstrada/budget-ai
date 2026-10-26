package com.budgetai.backend.integration;

import com.budgetai.backend.controller.DebtController;
import com.budgetai.backend.controller.SharedExpenseController;
import com.budgetai.backend.controller.SharedExpenseController.ShareRequest;
import com.budgetai.backend.controller.TransactionController;
import com.budgetai.backend.model.Account;
import com.budgetai.backend.model.Category;
import com.budgetai.backend.model.Debt;
import com.budgetai.backend.model.SharedExpense;
import com.budgetai.backend.model.Transaction;
import com.budgetai.backend.model.TransactionPart;
import com.budgetai.backend.repository.AccountRepository;
import com.budgetai.backend.repository.BudgetRepository;
import com.budgetai.backend.repository.CategoryRepository;
import com.budgetai.backend.repository.DebtRepository;
import com.budgetai.backend.repository.RecurringTransactionRepository;
import com.budgetai.backend.repository.TransactionPartRepository;
import com.budgetai.backend.repository.TransactionRepository;
import com.budgetai.backend.service.AnalyticsService;
import com.budgetai.backend.service.BudgetService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Una despesa compartida, de punta a punta: el sopar de 40 € entre quatre que
 * pago jo, i els Bizums de 10 € que me'n tornen la part.
 *
 * Sense agrupar-los, el pressupost hi veia 40 € de despesa i 20 € d'ingressos.
 * Agrupats, compten els 10 € que són meus, els Bizums no compten, i un deute
 * diu quant em deuen i quant en falta.
 */
@AutoConfigureMockMvc
class SharedExpenseIntegrationTest extends AbstractIntegrationTest {

    private static final LocalDate DINNER = LocalDate.of(2026, 10, 3);

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TransactionController transactionController;
    @Autowired private SharedExpenseController sharedExpenseController;
    @Autowired private DebtController debtController;
    @Autowired private BudgetService budgetService;
    @Autowired private AnalyticsService analyticsService;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private TransactionPartRepository partRepository;
    @Autowired private DebtRepository debtRepository;
    @Autowired private RecurringTransactionRepository recurringRepository;
    @Autowired private BudgetRepository budgetRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private CategoryRepository categoryRepository;

    private Long accountId;

    @BeforeEach
    void setUp() {
        transactionRepository.deleteAll();
        debtRepository.deleteAll();
        recurringRepository.deleteAll();
        budgetRepository.deleteAll();
        accountRepository.deleteAll();
        categoryRepository.deleteAll();

        categoryRepository.save(new Category("Altres"));
        leafIn(block("Gast mensual", Category.VARIABLE), "Bars i restaurants");
        leafIn(block("Ingressos", "INCOME"), "Ingressos Altres");

        Account account = new Account();
        account.setName("Compte Principal");
        account.setType("CORRIENTE");
        account.setCurrentBalance(new BigDecimal("1000.00"));
        accountId = accountRepository.save(account).getId();
    }

    private Long block(String name, String costType) {
        Category block = new Category(name);
        block.setCostType(costType);
        return categoryRepository.save(block).getId();
    }

    private void leafIn(Long blockId, String name) {
        Category leaf = new Category(name);
        leaf.setParentId(blockId);
        categoryRepository.save(leaf);
    }

    private static Transaction movement(String type, String amount, LocalDate date, String company, String category) {
        Transaction transaction = new Transaction();
        transaction.setType(type);
        transaction.setAmount(new BigDecimal(amount));
        transaction.setDate(date);
        transaction.setCompanyName(company);
        transaction.setCategoryName(category);
        transaction.setOriginalConcept(company + " " + date);
        return transaction;
    }

    private Long save(Transaction transaction) {
        return (Long) ((Map<?, ?>) transactionController.createTransaction(transaction).getBody()).get("id");
    }

    private Long dinner() {
        return save(movement("EXPENSE", "40.00", DINNER, "Restaurant La Plaça", "Bars i restaurants"));
    }

    private Long bizum(String from, int day) {
        return save(movement("INCOME", "10.00", DINNER.plusDays(day), "Bizum " + from, "Ingressos Altres"));
    }

    /** El que s'ha gastat de debò a una fulla aquell mes, segons el resum del pressupost. */
    @SuppressWarnings("unchecked")
    private BigDecimal spentIn(String categoryName) {
        List<Map<String, Object>> pending = new ArrayList<>(
                (List<Map<String, Object>>) budgetService.getMonthlySummary(2026, 10).get("grups"));
        while (!pending.isEmpty()) {
            Map<String, Object> node = pending.remove(0);
            if (categoryName.equals(((Category) node.get("categoria")).getName())) {
                return (BigDecimal) node.get("caixa_real");
            }
            pending.addAll((List<Map<String, Object>>) node.get("subcategories"));
        }
        throw new AssertionError("No hi ha cap node " + categoryName);
    }

    private BigDecimal monthTotal(String key) {
        return (BigDecimal) analyticsService.getMonthlySummary(2026, 10).get(key);
    }

    private BigDecimal balance() {
        return accountRepository.findById(accountId).orElseThrow().getCurrentBalance();
    }

    private Debt onlyDebt() {
        List<Debt> debts = debtRepository.findAll();
        assertThat(debts).hasSize(1);
        return (Debt) debtController.get(debts.get(0).getId()).getBody();
    }

    private List<TransactionPart> partsOf(Long transactionId) {
        return partRepository.findByTransactions(List.of(transactionId)).stream()
                .sorted(Comparator.comparing(TransactionPart::getAmount))
                .toList();
    }

    @Test
    @DisplayName("El sopar de 40 € entre quatre amb dos Bizums: compten 10 €, els Bizums no, i en falten 10")
    void dinnerSharedAmongFour() {
        Long payment = dinner();
        Long marta = bizum("Marta", 1);
        Long joan = bizum("Joan", 2);
        // Sense agrupar: 40 € de despesa i 20 € d'ingressos que no són cap sou.
        assertThat(spentIn("Bars i restaurants")).isEqualByComparingTo("40.00");
        assertThat(monthTotal("total_income")).isEqualByComparingTo("20.00");
        BigDecimal balanceBefore = balance();

        Debt debt = sharedExpenseController.share(
                new ShareRequest(payment, new BigDecimal("10.00"), "Sopar", List.of(marta, joan)));

        assertThat(spentIn("Bars i restaurants")).isEqualByComparingTo("10.00");
        assertThat(monthTotal("total_expense")).isEqualByComparingTo("10.00");
        assertThat(monthTotal("total_income")).isEqualByComparingTo("0");

        assertThat(debt.getName()).isEqualTo("Sopar");
        assertThat(debt.getDirection()).isEqualTo(Debt.OWED_TO_ME);
        assertThat(debt.getAmount()).isEqualByComparingTo("30.00");
        assertThat(debt.getRepaid()).isEqualByComparingTo("20.00");
        assertThat(debt.getPending()).isEqualByComparingTo("10.00");
        assertThat(debt.getMovements()).hasSize(3);
        // Ni el que vaig avançar ni el que em tornen compten: és el que diu a
        // la pantalla que un Bizum que arriba tard tampoc ha de comptar.
        assertThat(debt.getMovements()).allSatisfy(movement -> assertThat(movement.excludedFromBudget()).isTrue());

        List<TransactionPart> parts = partsOf(payment);
        assertThat(parts).extracting(TransactionPart::getAmount)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("10.00"), new BigDecimal("30.00"));
        assertThat(parts.get(0).isExcludedFromBudget()).isFalse();
        assertThat(parts.get(0).getDebtId()).isNull();
        assertThat(parts.get(1).isExcludedFromBudget()).isTrue();
        assertThat(parts.get(1).getDebtId()).isEqualTo(debt.getId());
        assertThat(parts).allSatisfy(part -> assertThat(part.getCategory().getName()).isEqualTo("Bars i restaurants"));

        Transaction martaSaved = transactionRepository.findById(marta).orElseThrow();
        assertThat(martaSaved.isExcludedFromBudget()).isTrue();
        assertThat(martaSaved.getDebtId()).isEqualTo(debt.getId());
        // Agrupar no és cap moviment nou: cap saldo es torna a moure.
        assertThat(balance()).isEqualByComparingTo(balanceBefore);
    }

    @Test
    @DisplayName("Sense el nom, el deute es diu com l'empresa del pagament, i els Bizums poden arribar després")
    void nameDefaultsToTheCompanyAndRepaymentsCanComeLater() {
        Long payment = dinner();

        Debt debt = sharedExpenseController.share(new ShareRequest(payment, new BigDecimal("10.00"), " ", null));

        assertThat(debt.getName()).isEqualTo("Restaurant La Plaça");
        assertThat(debt.getPending()).isEqualByComparingTo("30.00");

        // El Bizum que arriba la setmana següent es vincula com qualsevol
        // moviment a un deute, marcat perquè no compti.
        Long late = bizum("Pau", 7);
        Transaction changes = new Transaction();
        changes.setDebtId(debt.getId());
        changes.setExcludedFromBudget(true);
        transactionController.updateTransaction(late, changes);

        assertThat(onlyDebt().getPending()).isEqualByComparingTo("20.00");
        assertThat(monthTotal("total_income")).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("Si la meva part és zero, el pagament no es divideix: queda sencer sense comptar i vinculat al deute")
    void zeroShareKeepsThePaymentWhole() {
        Long payment = dinner();

        Debt debt = sharedExpenseController.share(new ShareRequest(payment, BigDecimal.ZERO, "Regal Anna", List.of()));

        assertThat(partsOf(payment)).isEmpty();
        Transaction saved = transactionRepository.findById(payment).orElseThrow();
        assertThat(saved.isExcludedFromBudget()).isTrue();
        assertThat(saved.getDebtId()).isEqualTo(debt.getId());
        assertThat(debt.getAmount()).isEqualByComparingTo("40.00");
        assertThat(spentIn("Bars i restaurants")).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("El que no quadra es rebutja dient per què, i no canvia res")
    void invalidRequestsChangeNothing() {
        Long payment = dinner();
        Long marta = bizum("Marta", 1);
        Long joan = bizum("Joan", 2);
        Long anna = bizum("Anna", 3);
        Long pau = bizum("Pau", 4);

        // Quatre Bizums de 10 i només em deuen 30.
        assertThatThrownBy(() -> sharedExpenseController.share(
                new ShareRequest(payment, new BigDecimal("10.00"), "Sopar", List.of(marta, joan, anna, pau))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("només et deuen 30,00 €");
        // Tot meu: no hi ha res a compartir.
        assertThatThrownBy(() -> sharedExpenseController.share(
                new ShareRequest(payment, new BigDecimal("40.00"), "Sopar", List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("menys de 40,00 €");
        // Un ingrés no és cap pagament, ni una despesa un cobrament.
        assertThatThrownBy(() -> sharedExpenseController.share(
                new ShareRequest(marta, new BigDecimal("5.00"), "Sopar", List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("comença per un pagament");
        Long otherDinner = save(movement("EXPENSE", "12.00", DINNER, "Bar Pepe", "Bars i restaurants"));
        assertThatThrownBy(() -> sharedExpenseController.share(
                new ShareRequest(payment, new BigDecimal("10.00"), "Sopar", List.of(otherDinner))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("només un ingrés");

        assertThat(debtRepository.findAll()).isEmpty();
        assertThat(partRepository.findAll()).isEmpty();
        assertThat(transactionRepository.findById(marta).orElseThrow().isExcludedFromBudget()).isFalse();
        assertThat(spentIn("Bars i restaurants")).isEqualByComparingTo("52.00");
    }

    @Test
    @DisplayName("Un pagament ja dividit no es comparteix: les parts serien d'una altra cosa")
    void splitPaymentIsRejected() {
        Long payment = dinner();
        sharedExpenseController.share(new ShareRequest(payment, new BigDecimal("10.00"), "Sopar", List.of()));

        assertThatThrownBy(() -> sharedExpenseController.share(
                new ShareRequest(payment, new BigDecimal("20.00"), "Sopar 2", List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ja està dividit");
        assertThat(debtRepository.findAll()).hasSize(1);
    }

    // ---- En importar l'extracte ----

    /** Les credencials que AbstractIntegrationTest dona al backend. */
    @Value("${app.auth.username}")
    private String username;
    @Value("${app.auth.password}")
    private String rightPassword;

    private Cookie login() throws Exception {
        return mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", username, "password", rightPassword))))
                .andReturn().getResponse().getCookie("budget_session");
    }

    /** Una fila de la revisió, com l'envia la pantalla. */
    private Map<String, Object> row(String type, String amount, LocalDate date, String company, String category) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("type", type);
        row.put("cost", amount);
        row.put("data", date.toString());
        row.put("empresa", company);
        row.put("categoria", category);
        row.put("concepte_original", company + " " + date);
        row.put("exclos_pressupost", false);
        row.put("account", Map.of("id", accountId));
        return row;
    }

    @Test
    @DisplayName("En importar l'extracte, el sopar i els Bizums s'agrupen a la mateixa confirmació")
    void sharedWhileImporting() throws Exception {
        Map<String, Object> payment = row("EXPENSE", "40.00", DINNER, "Restaurant La Plaça", "Bars i restaurants");
        payment.put("despesa_compartida", Map.of("clau", "sopar", "nom", "Sopar", "part_meva", 10));
        Map<String, Object> marta = row("INCOME", "10.00", DINNER.plusDays(1), "Bizum Marta", "Ingressos Altres");
        marta.put("cobrament_de", "sopar");
        Map<String, Object> joan = row("INCOME", "10.00", DINNER.plusDays(2), "Bizum Joan", "Ingressos Altres");
        joan.put("cobrament_de", "sopar");
        Map<String, Object> coffee = row("EXPENSE", "2.50", DINNER, "Cafeteria", "Bars i restaurants");

        mockMvc.perform(post("/confirm-upload").cookie(login())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(List.of(payment, marta, joan, coffee))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saved").value(4));

        assertThat(spentIn("Bars i restaurants")).isEqualByComparingTo("12.50");
        assertThat(monthTotal("total_income")).isEqualByComparingTo("0");
        Debt debt = onlyDebt();
        assertThat(debt.getName()).isEqualTo("Sopar");
        assertThat(debt.getAmount()).isEqualByComparingTo("30.00");
        assertThat(debt.getPending()).isEqualByComparingTo("10.00");
        // Les parts són les d'una fila dividida qualsevol: la meva i la que em deuen.
        Long paymentId = transactionRepository.findAll().stream()
                .filter(transaction -> transaction.getAmount().compareTo(new BigDecimal("40.00")) == 0)
                .findFirst().orElseThrow().getId();
        assertThat(partsOf(paymentId)).hasSize(2);
        // El saldo es mou pel que ha passat de debò: −40 −2,50 +10 +10.
        assertThat(balance()).isEqualByComparingTo("977.50");
    }

    @Test
    @DisplayName("Un Bizum d'una despesa compartida el pagament de la qual no s'importa: no s'importa res")
    void repaymentWithoutItsPaymentImportsNothing() throws Exception {
        Map<String, Object> marta = row("INCOME", "10.00", DINNER.plusDays(1), "Bizum Marta", "Ingressos Altres");
        marta.put("cobrament_de", "sopar");
        Map<String, Object> coffee = row("EXPENSE", "2.50", DINNER, "Cafeteria", "Bars i restaurants");

        mockMvc.perform(post("/confirm-upload").cookie(login())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(List.of(marta, coffee))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Bizum Marta")))
                .andExpect(jsonPath("$.message", containsString("no s'importa")));

        assertThat(transactionRepository.findAll()).isEmpty();
        assertThat(debtRepository.findAll()).isEmpty();
        assertThat(balance()).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("En importar, uns Bizums que sumen més del que em deuen no importen res")
    void importRepayingTooMuchImportsNothing() throws Exception {
        Map<String, Object> payment = row("EXPENSE", "40.00", DINNER, "Restaurant La Plaça", "Bars i restaurants");
        payment.put("despesa_compartida", Map.of("clau", "sopar", "nom", "Sopar", "part_meva", 35));
        Map<String, Object> marta = row("INCOME", "10.00", DINNER.plusDays(1), "Bizum Marta", "Ingressos Altres");
        marta.put("cobrament_de", "sopar");

        mockMvc.perform(post("/confirm-upload").cookie(login())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(List.of(payment, marta))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("només et deuen 5,00 €")));

        assertThat(transactionRepository.findAll()).isEmpty();
        assertThat(debtRepository.findAll()).isEmpty();
        assertThat(balance()).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("En importar, el Bizum que arriba tard es vincula a la despesa compartida d'abans")
    void lateRepaymentWhileImporting() throws Exception {
        Long payment = dinner();
        Debt debt = sharedExpenseController.share(new ShareRequest(payment, new BigDecimal("10.00"), "Sopar", List.of()));

        Map<String, Object> late = row("INCOME", "10.00", DINNER.plusDays(9), "Bizum Pau", "Ingressos Altres");
        late.put("deute_id", debt.getId());
        late.put("exclos_pressupost", true);

        mockMvc.perform(post("/confirm-upload").cookie(login())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(List.of(late))))
                .andExpect(status().isOk());

        assertThat(onlyDebt().getPending()).isEqualByComparingTo("20.00");
        assertThat(monthTotal("total_income")).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("Per HTTP, el pagament desat es comparteix a /despeses-compartides amb els noms del contracte")
    void shareOverHttp() throws Exception {
        Long payment = dinner();
        Long marta = bizum("Marta", 1);

        mockMvc.perform(post("/despeses-compartides").cookie(login())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "moviment_id", payment, "part_meva", 10, "nom", "Sopar", "cobraments", List.of(marta)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nom").value("Sopar"))
                .andExpect(jsonPath("$.direccio").value("EM_DEUEN"))
                .andExpect(jsonPath("$.pendent").value(20.0));

        mockMvc.perform(post("/despeses-compartides").cookie(login())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "moviment_id", 999999, "part_meva", 10, "nom", "Sopar"))))
                .andExpect(status().isNotFound());
    }
}
