package com.budgetai.backend.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fixa els noms dels camps JSON que el frontend consumeix.
 *
 * Aquests tests existeixen perquè el frontend llegia camps que el backend no
 * ha produït mai. Un camp que no existeix en JavaScript és `undefined`, no un
 * error, així que la interfície ensenyava zeros i graelles buides sense que
 * res petés: l'única manera d'adonar-se'n era mirar-ho a ull.
 *
 * Si algú reanomena una propietat aquí, aquests tests han de fallar. Quan
 * fallin, cal actualitzar també el frontend que la llegeix.
 */
class JsonContractTest {

    // El mateix mapper que configura Spring Boot: cal el mòdul de java.time
    // perquè les entitats porten LocalDate i LocalDateTime.
    private final ObjectMapper mapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    @Test
    @DisplayName("Account: el frontend llegeix nom, tipus, saldo_actual, moneda, activa i color")
    void accountKeys() throws Exception {
        Account account = new Account();
        account.setName("Compte Principal");
        account.setType("CORRIENTE");
        account.setCurrentBalance(new BigDecimal("112.97"));
        account.setCurrency("EUR");
        account.setActive(true);
        account.setColor("#4CAF50");

        JsonNode json = mapper.valueToTree(account);

        assertThat(json.has("nom")).isTrue();
        assertThat(json.has("tipus")).isTrue();
        assertThat(json.has("saldo_actual")).isTrue();
        assertThat(json.has("moneda")).isTrue();
        assertThat(json.has("activa")).isTrue();
        assertThat(json.has("color")).isTrue();

        assertThat(json.get("nom").asText()).isEqualTo("Compte Principal");
        assertThat(json.get("saldo_actual").asText()).isEqualTo("112.97");
    }

    @Test
    @DisplayName("FinancialGoal: nom, quantitat_objectiu i quantitat_actual, no name/target_amount")
    void goalKeys() throws Exception {
        FinancialGoal goal = new FinancialGoal();
        goal.setName("Viatge");
        goal.setTargetAmount(new BigDecimal("1500.00"));
        goal.setCurrentAmount(new BigDecimal("250.00"));
        goal.setTargetDate(LocalDate.of(2026, 12, 31));

        JsonNode json = mapper.valueToTree(goal);

        assertThat(json.has("nom")).isTrue();
        assertThat(json.has("quantitat_objectiu")).isTrue();
        assertThat(json.has("quantitat_actual")).isTrue();
        assertThat(json.has("data_objectiu")).isTrue();
        assertThat(json.has("progres_percentatge")).isTrue();

        // Els noms que el formulari feia servir per error: enviar-los feia que
        // Jackson els descartés i la inserció petava amb un 500, perquè
        // quantitat_objectiu és NOT NULL.
        assertThat(json.has("name")).isFalse();
        assertThat(json.has("target_amount")).isFalse();
        assertThat(json.has("current_amount")).isFalse();
        assertThat(json.has("deadline")).isFalse();
    }

    @Test
    @DisplayName("FinancialGoal: es pot crear des del JSON que envia el formulari")
    void goalDeserializesFromFormPayload() throws Exception {
        String payload = """
            {"nom":"Viatge","quantitat_objectiu":1500.00,"data_objectiu":"2026-12-31"}
            """;

        FinancialGoal goal = mapper.readValue(payload, FinancialGoal.class);

        assertThat(goal.getName()).isEqualTo("Viatge");
        assertThat(goal.getTargetAmount()).isEqualByComparingTo("1500.00");
        assertThat(goal.getTargetDate()).isEqualTo(LocalDate.of(2026, 12, 31));
    }

    @Test
    @DisplayName("Transfer: els comptes són sourceAccount i destinationAccount")
    void transferKeys() throws Exception {
        Account source = new Account();
        source.setName("Origen");
        Account destination = new Account();
        destination.setName("Destí");

        Transfer transfer = new Transfer();
        transfer.setSourceAccount(source);
        transfer.setDestinationAccount(destination);
        transfer.setAmount(new BigDecimal("25.50"));
        transfer.setDate(LocalDate.of(2026, 8, 5));

        JsonNode json = mapper.valueToTree(transfer);

        assertThat(json.has("sourceAccount")).isTrue();
        assertThat(json.has("destinationAccount")).isTrue();
        assertThat(json.has("import")).isTrue();
        assertThat(json.has("data")).isTrue();

        // Els noms de les columnes de la base de dades no són els del JSON.
        // El formulari els enviava i crear una transferència no funcionava mai.
        assertThat(json.has("account_origen_id")).isFalse();
        assertThat(json.has("account_desti_id")).isFalse();
    }

    @Test
    @DisplayName("Transfer: es pot crear des del JSON que envia el formulari")
    void transferDeserializesFromFormPayload() throws Exception {
        String payload = """
            {"sourceAccount":{"id":1},"destinationAccount":{"id":2},
             "import":25.50,"data":"2026-08-05","descripcio":"prova"}
            """;

        Transfer transfer = mapper.readValue(payload, Transfer.class);

        assertThat(transfer.getSourceAccount().getId()).isEqualTo(1L);
        assertThat(transfer.getDestinationAccount().getId()).isEqualTo(2L);
        assertThat(transfer.getAmount()).isEqualByComparingTo("25.50");
    }

    @Test
    @DisplayName("Transaction: l'import és 'cost' i la data és 'data'")
    void transactionKeys() throws Exception {
        Transaction transaction = new Transaction();
        transaction.setAmount(new BigDecimal("45.30"));
        transaction.setDate(LocalDate.of(2026, 2, 15));
        transaction.setType("EXPENSE");
        transaction.setCompanyName("CONDIS");
        transaction.setCategoryName("Menjar i supermercat");

        JsonNode json = mapper.valueToTree(transaction);

        assertThat(json.has("cost")).isTrue();
        assertThat(json.has("data")).isTrue();
        assertThat(json.has("empresa")).isTrue();
        assertThat(json.has("categoria")).isTrue();
        assertThat(json.has("descripcio_curta")).isTrue();

        // Sobre l'import es comprova la sortida real: valueToTree normalitza
        // el BigDecimal i li lleva el zero final, cosa que no passa en
        // serialitzar de debò.
        assertThat(mapper.writeValueAsString(transaction)).contains("\"cost\":45.30");
        assertThat(json.get("data").asText()).isEqualTo("2026-02-15");

        // El hash no ha de sortir mai cap al client.
        assertThat(json.has("verificationHash")).isFalse();
        assertThat(json.has("hash_verificacio")).isFalse();
    }

    @Test
    @DisplayName("Budget: quantitat_limit i gasto_actual")
    void budgetKeys() throws Exception {
        Budget budget = new Budget();
        budget.setLimitAmount(new BigDecimal("300.00"));
        budget.setCurrentSpent(new BigDecimal("120.50"));
        budget.setPeriodStart(LocalDate.of(2026, 8, 1));
        budget.setPeriodEnd(LocalDate.of(2026, 8, 31));

        JsonNode json = mapper.valueToTree(budget);

        assertThat(json.has("quantitat_limit")).isTrue();
        assertThat(json.has("gasto_actual")).isTrue();
        assertThat(json.has("periode_inici")).isTrue();
        assertThat(json.has("periode_fi")).isTrue();
    }

    @Test
    @DisplayName("Category: parent_id i tipus_cost per a l'arbre de categories")
    void categoryHierarchyKeys() throws Exception {
        Category category = new Category();
        category.setName("Assegurança cotxe");
        category.setParentId(7L);
        category.setCostType(Category.FIXED);

        JsonNode json = mapper.valueToTree(category);

        assertThat(json.has("nom")).isTrue();
        assertThat(json.has("parent_id")).isTrue();
        assertThat(json.has("tipus_cost")).isTrue();
        assertThat(json.has("es_fix")).isTrue();

        assertThat(json.get("parent_id").asLong()).isEqualTo(7L);
        assertThat(json.get("tipus_cost").asText()).isEqualTo("FIXED");
        assertThat(json.get("es_fix").asBoolean()).isTrue();

        // Els noms en anglès no s'exposen.
        assertThat(json.has("parentId")).isFalse();
        assertThat(json.has("costType")).isFalse();
    }

    @Test
    @DisplayName("Category: una categoria de primer nivell té parent_id nul")
    void rootCategoryHasNullParent() throws Exception {
        Category category = new Category("Gastos passius");

        JsonNode json = mapper.valueToTree(category);

        assertThat(json.get("parent_id").isNull()).isTrue();
        // Sense naturalesa declarada no és fixa: compta com a variable.
        assertThat(json.get("es_fix").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("Category: es pot crear des del JSON que envia el formulari")
    void categoryDeserializesFromFormPayload() throws Exception {
        String payload = """
            {"nom":"Combustible","parent_id":3,"tipus_cost":"VARIABLE"}
            """;

        Category category = mapper.readValue(payload, Category.class);

        assertThat(category.getName()).isEqualTo("Combustible");
        assertThat(category.getParentId()).isEqualTo(3L);
        assertThat(category.getCostType()).isEqualTo("VARIABLE");
    }

    @Test
    @DisplayName("RecurringTransaction exposa el prorrateig mensual")
    void recurringExposesProratedAmount() throws Exception {
        RecurringTransaction recurring = new RecurringTransaction();
        recurring.setName("Assegurança");
        recurring.setAmount(new BigDecimal("600.00"));
        recurring.setFrequency("ANUAL");
        recurring.setType("EXPENSE");

        JsonNode json = mapper.valueToTree(recurring);

        assertThat(json.has("import")).isTrue();
        assertThat(json.has("frequencia")).isTrue();
        assertThat(json.has("prorrateig_mensual")).isTrue();
        assertThat(json.get("prorrateig_mensual").decimalValue()).isEqualByComparingTo("50.00");
    }

    @Test
    @DisplayName("Settings és l'única entitat en camelCase; queda documentat aquí")
    void settingsKeysAreCamelCase() throws Exception {
        Settings settings = new Settings();
        settings.setUserName("Usuario");
        settings.setNotificationsExpenses(true);

        JsonNode json = mapper.valueToTree(settings);

        // A diferència de la resta de models, Settings no porta @JsonProperty.
        // El frontend llegia user_name i notifications_expenses, que no
        // existeixen. Si algun dia s'unifica el criteri, aquest test fallarà i
        // caldrà actualitzar loadAppState() a api.js.
        assertThat(json.has("userName")).isTrue();
        assertThat(json.has("notificationsExpenses")).isTrue();
        assertThat(json.has("user_name")).isFalse();
        assertThat(json.has("notifications_expenses")).isFalse();
    }

    @Test
    @DisplayName("Els imports se serialitzen amb dos decimals exactes, sense soroll binari")
    void amountsHaveNoFloatingPointNoise() throws Exception {
        Account account = new Account();
        // Aquest és el valor que hi havia realment desat a la base de dades
        // quan els imports eren Double.
        account.setCurrentBalance(new BigDecimal("112.97"));

        String json = mapper.writeValueAsString(account);

        assertThat(json).contains("\"saldo_actual\":112.97");
        assertThat(json).doesNotContain("112.97000000000018");
    }
}
