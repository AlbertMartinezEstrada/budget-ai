package com.budgetai.backend.service;

import com.budgetai.backend.model.Transaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
        service = new AiEngineService();
    }

    private Transaction original(String concept, String amount) {
        Transaction t = new Transaction();
        t.setOriginalConcept(concept);
        t.setAmount(new BigDecimal(amount));
        t.setDate(LocalDate.of(2026, 2, 15));
        t.setType("EXPENSE");
        t.setVerificationHash("hash-" + concept);
        return t;
    }

    @Test
    @DisplayName("Sense clau d'API es retornen els moviments originals intactes")
    void withoutApiKeyReturnsOriginals() {
        ReflectionTestUtils.setField(service, "apiKey", "");

        Transaction t = original("CONDIS", "45.30");
        List<Transaction> result = service.classifyTransactions(List.of(t));

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
        assertThat(result).allSatisfy(t -> {
            assertThat(t.getVerificationHash()).isNotNull();
            assertThat(t.getType()).isEqualTo("EXPENSE");
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
        Transaction t = result.get(0);

        // El que sí que pot decidir:
        assertThat(t.getCompanyName()).isEqualTo("Condis");
        assertThat(t.getCategoryName()).isEqualTo("Menjar i supermercat");
        assertThat(t.getShortDescription()).isEqualTo("Compra setmanal");

        // El que no ha de poder tocar mai:
        assertThat(t.getAmount()).isEqualByComparingTo("45.30");
        assertThat(t.getDate()).isEqualTo(LocalDate.of(2026, 2, 15));
        assertThat(t.getType()).isEqualTo("EXPENSE");
        assertThat(t.getVerificationHash()).isEqualTo("hash-CONDIS");
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
}
