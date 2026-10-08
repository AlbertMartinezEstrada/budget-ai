package com.budgetai.backend.integration;

import com.budgetai.backend.model.Account;
import com.budgetai.backend.model.Category;
import com.budgetai.backend.model.Transaction;
import com.budgetai.backend.repository.*;
import com.budgetai.backend.service.BudgetService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Traspassos entre comptes propis, per HTTP com els fa la pantalla.
 *
 * L'escenari és el de l'usuari: des del compte principal envia diners a
 * Revolut, i des de Revolut paga Claude i altres coses; i en envia a Trade
 * Republic per estalviar.
 *
 *   - El traspàs a Revolut no compta: compta Claude, pagat des de Revolut.
 *   - El traspàs a Trade Republic compta com a estalvi.
 *   - Tots dos mouen el saldo dels dos comptes.
 *   - Importar l'extracte de Revolut no torna a desar l'entrada del traspàs.
 */
@AutoConfigureMockMvc
class InternalTransfersIntegrationTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private BudgetRepository budgetRepository;
    @Autowired private RecurringTransactionRepository recurringRepository;
    @Autowired private MonthlyIncomeRepository monthlyIncomeRepository;
    @Autowired private ImportRuleRepository importRuleRepository;
    @Autowired private BudgetService budgetService;

    private Cookie session;
    private Account principal;
    private Account revolut;
    private Account tradeRepublic;
    private Category savings;
    private Category claude;

    @BeforeEach
    void setUp() throws Exception {
        transactionRepository.deleteAll();
        recurringRepository.deleteAll();
        budgetRepository.deleteAll();
        monthlyIncomeRepository.deleteAll();
        importRuleRepository.deleteAll();
        accountRepository.deleteAll();
        categoryRepository.deleteAll();

        categoryRepository.save(new Category("Altres"));
        Category subscriptions = saveCategory("Subscripcions", null, Category.FIXED);
        claude = saveCategory("Claude", subscriptions.getId(), Category.FIXED);
        savings = saveCategory("Estalvis", null, Category.VARIABLE);

        principal = saveAccount("Compte Principal", "CORRIENTE", "1000.00");
        revolut = saveAccount("Revolut", "CORRIENTE", "0.00");
        tradeRepublic = saveAccount("Trade Republic", "INVERSIONES", "0.00");

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

    private Account saveAccount(String name, String type, String balance) {
        Account account = new Account();
        account.setName(name);
        account.setType(type);
        account.setCurrentBalance(new BigDecimal(balance));
        return accountRepository.save(account);
    }

    private BigDecimal balanceOf(Account account) {
        return accountRepository.findById(account.getId()).orElseThrow().getCurrentBalance();
    }

    /** El que envia el formulari de Transaccions. */
    private Map<String, Object> form(String type, String amount, String category, Account account, Account counterpart) {
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("data", "2026-10-01");
        form.put("type", type);
        form.put("cost", new BigDecimal(amount));
        form.put("categoria", category);
        form.put("empresa", "Prova");
        form.put("account", Map.of("id", account.getId()));
        // Com el formulari: la casella de «no compta» desmarcada, i -1 quan no
        // és cap traspàs.
        form.put("exclos_pressupost", false);
        form.put("compte_contrapart_id", counterpart != null ? counterpart.getId() : -1);
        return form;
    }

    private void create(Map<String, Object> form) throws Exception {
        mockMvc.perform(post("/gastos").cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(form)))
                .andExpect(status().isOk());
    }

    private Transaction onlyTransfer() {
        return transactionRepository.findAll().stream().filter(Transaction::isTransfer).findFirst().orElseThrow();
    }

    /** El node d'una categoria al resum d'octubre, a qualsevol profunditat. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> nodeOf(Category category) {
        List<Map<String, Object>> pending = new ArrayList<>();
        for (Map<String, Object> section : (List<Map<String, Object>>) budgetService.getMonthlySummary(2026, 10).get("seccions")) {
            pending.addAll((List<Map<String, Object>>) section.get("grups"));
        }
        while (!pending.isEmpty()) {
            Map<String, Object> node = pending.remove(0);
            if (category.getId().equals(((Category) node.get("categoria")).getId())) return node;
            pending.addAll((List<Map<String, Object>>) node.get("subcategories"));
        }
        throw new AssertionError("No s'ha trobat " + category.getName());
    }

    private BigDecimal realOf(Category category) {
        return (BigDecimal) nodeOf(category).get("cost_vida_real");
    }

    @Test
    @DisplayName("El traspàs a Revolut no compta; el que s'hi paga, sí, a la seva categoria")
    void aTransferToRevolutDoesNotCountButWhatItPaysDoes() throws Exception {
        create(form("EXPENSE", "100.00", "Altres", principal, revolut));
        create(form("EXPENSE", "20.00", "Claude", revolut, null));

        Transaction transfer = onlyTransfer();
        assertThat(transfer.isExcludedFromBudget()).isTrue();
        assertThat(transfer.getCounterpartAccount().getId()).isEqualTo(revolut.getId());
        // Els 100 € no surten enlloc del pressupost, i Claude compta 20.
        assertThat((BigDecimal) nodeOf(categoryRepository.findByName("Altres").orElseThrow()).get("caixa_real"))
                .isEqualByComparingTo("0");
        assertThat((BigDecimal) nodeOf(claude).get("caixa_real")).isEqualByComparingTo("20.00");

        // I mou els dos saldos: 1000 − 100, i 0 + 100 − 20.
        assertThat(balanceOf(principal)).isEqualByComparingTo("900.00");
        assertThat(balanceOf(revolut)).isEqualByComparingTo("80.00");
    }

    @Test
    @DisplayName("El traspàs a Trade Republic compta com a estalvi, i el que en torna el resta")
    void aTransferToSavingsCountsAsSavings() throws Exception {
        create(form("EXPENSE", "300.00", "Estalvis", principal, tradeRepublic));
        assertThat(realOf(savings)).isEqualByComparingTo("300.00");
        assertThat(balanceOf(tradeRepublic)).isEqualByComparingTo("300.00");

        Map<String, Object> withdrawal = form("INCOME", "100.00", "Estalvis", principal, tradeRepublic);
        create(withdrawal);

        assertThat(realOf(savings)).isEqualByComparingTo("200.00");
        assertThat(balanceOf(tradeRepublic)).isEqualByComparingTo("200.00");
        assertThat(balanceOf(principal)).isEqualByComparingTo("800.00");
        // Treure diners de l'estalvi no és cap ingrés: el que entra és el mateix.
        assertThat((BigDecimal) budgetService.getMonthlySummary(2026, 10).get("ingressos_reals"))
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("Esborrar un traspàs desfà el saldo dels dos comptes")
    void deletingATransferRevertsBothBalances() throws Exception {
        create(form("EXPENSE", "100.00", "Altres", principal, revolut));

        mockMvc.perform(delete("/gastos/" + onlyTransfer().getId()).cookie(session)).andExpect(status().isOk());

        assertThat(balanceOf(principal)).isEqualByComparingTo("1000.00");
        assertThat(balanceOf(revolut)).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("Canviar l'altre compte mou el diner d'un a l'altre, i passar a l'estalvi el fa comptar")
    void changingTheCounterpartMovesTheMoney() throws Exception {
        create(form("EXPENSE", "100.00", "Estalvis", principal, revolut));
        Long id = onlyTransfer().getId();

        mockMvc.perform(put("/gastos/" + id).cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        // Sense dir res de l'exclòs: el decideix el compte nou.
                        .content("{\"compte_contrapart_id\":" + tradeRepublic.getId() + "}"))
                .andExpect(status().isOk());

        assertThat(balanceOf(revolut)).isEqualByComparingTo("0.00");
        assertThat(balanceOf(tradeRepublic)).isEqualByComparingTo("100.00");
        assertThat(balanceOf(principal)).isEqualByComparingTo("900.00");
        assertThat(realOf(savings)).isEqualByComparingTo("100.00");

        // I deixar de ser un traspàs el torna a un moviment normal.
        mockMvc.perform(put("/gastos/" + id).cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"compte_contrapart_id\":-1}"))
                .andExpect(status().isOk());
        assertThat(balanceOf(tradeRepublic)).isEqualByComparingTo("0.00");
        assertThat(transactionRepository.findById(id).orElseThrow().isTransfer()).isFalse();
    }

    @Test
    @DisplayName("Un traspàs al mateix compte es rebutja sense moure cap saldo")
    void aTransferToTheSameAccountIsRejected() throws Exception {
        mockMvc.perform(post("/gastos").cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(form("EXPENSE", "100.00", "Altres", principal, principal))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("ha d'anar a un altre compte")));

        assertThat(transactionRepository.findAll()).isEmpty();
        assertThat(balanceOf(principal)).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("A l'extracte de Revolut, l'entrada del traspàs ja desat surt reconeguda i no es torna a desar")
    void theOtherStatementRecognisesTheTransfer() throws Exception {
        create(form("EXPENSE", "100.00", "Altres", principal, revolut));
        Long transferId = onlyTransfer().getId();

        String statement = "Fecha;Concepto;Importe\n"
                + "02/10/2026;TRANSFERENCIA DE ALBERT;100,00 EUR\n"
                + "03/10/2026;CLAUDE.AI SUBSCRIPTION;-20,00 EUR\n";
        String response = mockMvc.perform(multipart("/upload-csv")
                        .file(new MockMultipartFile("file", "revolut.csv", "text/csv",
                                statement.getBytes(StandardCharsets.UTF_8)))
                        .param("accountId", String.valueOf(revolut.getId()))
                        .cookie(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        JsonNode rows = objectMapper.readTree(response).get("data");
        JsonNode arrival = rows.get(0).get("cost").decimalValue().compareTo(new BigDecimal("100")) == 0 ? rows.get(0) : rows.get(1);
        JsonNode payment = arrival == rows.get(0) ? rows.get(1) : rows.get(0);
        assertThat(arrival.get("traspas_registrat").asLong()).isEqualTo(transferId);
        assertThat(arrival.get("compte_contrapart_id").asLong()).isEqualTo(principal.getId());
        assertThat(payment.get("traspas_registrat").isNull()).isTrue();
    }

    @Test
    @DisplayName("Una regla «REVOLUT» marca la línia com a traspàs a Revolut, i que no compta")
    void aRuleMarksTheTransfer() throws Exception {
        mockMvc.perform(post("/import-rules").cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"patro\":\"REVOLUT\",\"compte_traspas_id\":" + revolut.getId() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.compte_traspas_id").value(revolut.getId()))
                .andExpect(jsonPath("$.marca_exclos").value(false));

        String statement = "Fecha;Concepto;Importe\n01/10/2026;TRASPASO A REVOLUT;-100,00 EUR\n";
        mockMvc.perform(multipart("/upload-csv")
                        .file(new MockMultipartFile("file", "principal.csv", "text/csv",
                                statement.getBytes(StandardCharsets.UTF_8)))
                        .param("accountId", String.valueOf(principal.getId()))
                        .cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].compte_contrapart_id").value(revolut.getId()))
                .andExpect(jsonPath("$.data[0].exclos_pressupost").value(true));
    }

    @Test
    @DisplayName("Filtrar per Trade Republic hi treu els traspassos, encara que no se n'importi l'extracte")
    void filteringByTheCounterpartFindsTheTransfer() throws Exception {
        create(form("EXPENSE", "300.00", "Estalvis", principal, tradeRepublic));

        mockMvc.perform(get("/gastos").param("accountId", String.valueOf(tradeRepublic.getId())).cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].compte_contrapart_id").value(tradeRepublic.getId()));
    }
}
