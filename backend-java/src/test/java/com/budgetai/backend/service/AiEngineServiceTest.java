package com.budgetai.backend.service;

import com.budgetai.backend.model.Category;
import com.budgetai.backend.model.Transaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * El punt delicat d'aquest servei és que la resposta de la IA no ha de poder
 * corrompre les dades del banc.
 *
 * La versió antiga aparellava per posició i, si les mides no coincidien,
 * retornava les transaccions inventades per la IA: sense hash de verificació,
 * sense tipus i amb l'import que ella hagués dit. Això es desava tal qual.
 */
class AiEngineServiceTest {

    private AiEngineService service;

    @BeforeEach
    void setUp() {
        // La jerarquia només fa falta per muntar el prompt, i aquests tests
        // comproven què es fa amb la resposta, no què se li demana.
        service = new AiEngineService(null);
    }

    private Transaction original(String concept, String amount) {
        Transaction transaction = new Transaction();
        transaction.setOriginalConcept(concept);
        transaction.setAmount(new BigDecimal(amount));
        transaction.setDate(LocalDate.of(2026, 2, 15));
        transaction.setType("EXPENSE");
        transaction.setVerificationHash("hash-" + concept);
        return transaction;
    }

    @Test
    @DisplayName("Sense clau d'API es retornen els moviments originals intactes")
    void withoutApiKeyReturnsOriginals() {
        ReflectionTestUtils.setField(service, "apiKey", "");

        Transaction transaction = original("CONDIS", "45.30");
        List<Transaction> result = service.classifyTransactions(List.of(transaction));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getAmount()).isEqualByComparingTo("45.30");
        assertThat(result.get(0).getType()).isEqualTo("EXPENSE");
        assertThat(result.get(0).getVerificationHash()).isEqualTo("hash-CONDIS");
    }

    @Test
    @DisplayName("Una clau a null tampoc no impedeix la importació")
    void nullApiKeyIsHandled() {
        ReflectionTestUtils.setField(service, "apiKey", null);

        List<Transaction> result = service.classifyTransactions(List.of(original("CONDIS", "45.30")));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getVerificationHash()).isNotNull();
    }

    @Test
    @DisplayName("Una llista buida no crida la IA i retorna una llista buida")
    void emptyListShortCircuits() {
        ReflectionTestUtils.setField(service, "apiKey", "clau-qualsevol");

        assertThat(service.classifyTransactions(List.of())).isEmpty();
        assertThat(service.classifyTransactions(null)).isEmpty();
    }

    @Test
    @DisplayName("Si la IA retorna un nombre de files diferent, es descarta la classificació")
    void sizeMismatchDiscardsAiOutput() {
        List<Transaction> originals = List.of(
                original("CONDIS", "45.30"),
                original("BENZINERA", "60.00")
        );

        // La IA només retorna una fila per a dos moviments.
        String aiResponse = """
                [{"companyName":"Condis","category":"Menjar i supermercat",
                  "description_curta":"Compra","cost":999.99,"date":"2020-01-01"}]
                """;

        List<Transaction> result = invokeParseAndMerge(aiResponse, originals);

        // Es conserven els dos moviments bons, amb el seu import i el seu hash.
        assertThat(result).hasSize(2);
        assertThat(result.get(0).getAmount()).isEqualByComparingTo("45.30");
        assertThat(result.get(1).getAmount()).isEqualByComparingTo("60.00");
        assertThat(result).allSatisfy(transaction -> {
            assertThat(transaction.getVerificationHash()).isNotNull();
            assertThat(transaction.getType()).isEqualTo("EXPENSE");
        });
    }

    @Test
    @DisplayName("Quan les mides coincideixen, la IA només decideix empresa, categoria i descripció")
    void aiCannotOverrideFinancialFields() {
        List<Transaction> originals = List.of(original("CONDIS", "45.30"));

        // La IA intenta canviar l'import i la data.
        String aiResponse = """
                [{"companyName":"Condis","category":"Menjar i supermercat",
                  "description_curta":"Compra setmanal","cost":999.99,"date":"2020-01-01"}]
                """;

        List<Transaction> result = invokeParseAndMerge(aiResponse, originals);

        assertThat(result).hasSize(1);
        Transaction transaction = result.get(0);

        // El que sí que pot decidir:
        assertThat(transaction.getCompanyName()).isEqualTo("Condis");
        assertThat(transaction.getCategoryName()).isEqualTo("Menjar i supermercat");
        assertThat(transaction.getShortDescription()).isEqualTo("Compra setmanal");

        // El que no ha de poder tocar mai:
        assertThat(transaction.getAmount()).isEqualByComparingTo("45.30");
        assertThat(transaction.getDate()).isEqualTo(LocalDate.of(2026, 2, 15));
        assertThat(transaction.getType()).isEqualTo("EXPENSE");
        assertThat(transaction.getVerificationHash()).isEqualTo("hash-CONDIS");
    }

    @Test
    @DisplayName("Una resposta que no és JSON deixa els moviments com estaven")
    void malformedResponseKeepsOriginals() {
        List<Transaction> originals = List.of(original("CONDIS", "45.30"));

        List<Transaction> result = invokeParseAndMerge("això no és JSON", originals);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getAmount()).isEqualByComparingTo("45.30");
        assertThat(result.get(0).getVerificationHash()).isEqualTo("hash-CONDIS");
    }

    @Test
    @DisplayName("La resposta pot venir embolcallada en un bloc de codi markdown")
    void stripsMarkdownFence() {
        List<Transaction> originals = List.of(original("CONDIS", "45.30"));

        String aiResponse = """
                ```json
                [{"companyName":"Condis","category":"Menjar i supermercat","description_curta":"Compra"}]
                ```
                """;

        List<Transaction> result = invokeParseAndMerge(aiResponse, originals);

        assertThat(result.get(0).getCompanyName()).isEqualTo("Condis");
    }

    @SuppressWarnings("unchecked")
    private List<Transaction> invokeParseAndMerge(String response, List<Transaction> originals) {
        return (List<Transaction>) ReflectionTestUtils.invokeMethod(
                service, "parseAndMergeTransactions", response, originals);
    }
    // ---- La crida a Gemini, amb un servidor simulat ----

    /** Un servei amb clau i una categoria, que parla amb el servidor simulat en comptes de Google. */
    private AiEngineService serviceTalkingTo(RestTemplate restTemplate) {
        Category groceries = new Category();
        groceries.setId(1L);
        groceries.setName("Menjar i supermercat");
        Map<Long, List<Category>> childrenByParent = new HashMap<>();
        childrenByParent.put(null, List.of(groceries));
        CategoryHierarchyService hierarchy = mock(CategoryHierarchyService.class);
        when(hierarchy.loadTree()).thenReturn(new CategoryHierarchyService.Tree(Map.of(1L, groceries), childrenByParent));

        AiEngineService talking = new AiEngineService(hierarchy, restTemplate);
        ReflectionTestUtils.setField(talking, "apiKey", "clau-de-prova");
        return talking;
    }

    private static String geminiAnswer(String text) {
        return "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":" + quoted(text) + "}]}}]}";
    }

    private static String quoted(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @Test
    @DisplayName("La clau viatja a la capçalera, mai a l'adreça")
    void apiKeyGoesInTheHeaderNotTheUrl() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer gemini = MockRestServiceServer.bindTo(restTemplate).build();
        gemini.expect(requestTo(AiEngineService.GEMINI_API_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(AiEngineService.API_KEY_HEADER, "clau-de-prova"))
                .andRespond(withSuccess(geminiAnswer(
                        "[{\"companyName\":\"Mercadona\",\"category\":\"Menjar i supermercat\",\"description_curta\":\"Compra\"}]"),
                        MediaType.APPLICATION_JSON));

        List<Transaction> result = serviceTalkingTo(restTemplate)
                .classifyTransactions(List.of(original("MERCADONA BARCELONA", "-45.30")));

        gemini.verify();
        assertThat(AiEngineService.GEMINI_API_URL).doesNotContain("key=");
        assertThat(result).singleElement().satisfies(transaction -> {
            assertThat(transaction.getCategoryName()).isEqualTo("Menjar i supermercat");
            assertThat(transaction.getAmount()).isEqualByComparingTo("-45.30");
        });
    }

    @Test
    @DisplayName("Si Gemini falla, la importació continua amb els moviments tal com eren")
    void geminiFailureKeepsTheImportGoing() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer gemini = MockRestServiceServer.bindTo(restTemplate).build();
        gemini.expect(requestTo(AiEngineService.GEMINI_API_URL)).andRespond(withServerError());
        Transaction movement = original("MERCADONA BARCELONA", "-45.30");

        List<Transaction> result = serviceTalkingTo(restTemplate).classifyTransactions(List.of(movement));

        gemini.verify();
        assertThat(result).containsExactly(movement);
        assertThat(movement.getCategoryName()).isNull();
    }

    @Test
    @DisplayName("Una resposta amb una forma inesperada no tomba la importació")
    void unexpectedAnswerShapeKeepsTheImportGoing() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer gemini = MockRestServiceServer.bindTo(restTemplate).build();
        gemini.expect(requestTo(AiEngineService.GEMINI_API_URL))
                .andRespond(withSuccess("{\"candidates\":[{\"content\":{\"parts\":[{}]}}]}", MediaType.APPLICATION_JSON));
        Transaction movement = original("MERCADONA BARCELONA", "-45.30");

        List<Transaction> result = serviceTalkingTo(restTemplate).classifyTransactions(List.of(movement));

        assertThat(result).containsExactly(movement);
    }
}
