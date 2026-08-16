package com.budgetai.backend.service;

import com.budgetai.backend.model.Category;
import com.budgetai.backend.model.Transaction;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

@Service
public class AiEngineService {

    @Value("${gemini.api.key}")
    private String apiKey;

    private static final String GEMINI_API_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=";

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final CategoryHierarchyService hierarchyService;

    public AiEngineService(CategoryHierarchyService hierarchyService) {
        this.hierarchyService = hierarchyService;
    }

    public List<Transaction> classifyTransactions(List<Transaction> transactions) {
        if (transactions == null || transactions.isEmpty()) {
            return new ArrayList<>();
        }

        // Sense clau no es pot classificar, però la importació ha de continuar:
        // l'usuari sempre pot assignar les categories a mà a la pantalla de revisió.
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("AiEngineService: falta GEMINI_API_KEY; s'omet la classificació automàtica.");
            return transactions;
        }

        // Sense categories no hi ha res entre què triar, i demanar-li-ho igualment
        // faria que se les inventés.
        List<String> categories = leafCategoryNames();
        if (categories.isEmpty()) {
            System.err.println("AiEngineService: no hi ha categories; s'omet la classificació automàtica.");
            return transactions;
        }

        // 1. Prepare prompt
        String transactionsListString = formatTransactionsForPrompt(transactions);
        String prompt = createPrompt(categories, transactionsListString);

        // 2. Call Gemini API
        String responseJson = callGeminiApi(prompt);

        if (responseJson == null) {
            return transactions; // Return original if AI fails
        }

        // 3. Parse response and update transactions
        return parseAndMergeTransactions(responseJson, transactions);
    }

    private String formatTransactionsForPrompt(List<Transaction> transactions) {
        StringBuilder sb = new StringBuilder();
        // Header
        sb.append("concepte_original, data, Cost\n");
        for (Transaction t : transactions) {
            String concept = t.getOriginalConcept() != null
                    ? t.getOriginalConcept().replace(",", " ")
                    : "";
            sb.append(String.format("%s, %s, %s\n",
                concept,
                t.getDate(),
                t.getAmount() != null ? t.getAmount().toPlainString() : "0.00"));
        }
        return sb.toString();
    }

    /**
     * Categories que la IA pot triar: les fulles de l'arbre, tal com són ara.
     *
     * Abans la llista anava escrita a mà al prompt. Cada categoria nova quedava
     * fora fins que algú se'n recordava d'editar aquest fitxer, i la IA seguia
     * proposant noms que ja no existien —que la pantalla de revisió marca com a
     * desconeguts i obliguen a triar-los un per un—.
     *
     * Només fulles: una transacció assignada a un grup es comptaria dues
     * vegades i el backend ho rebutja en confirmar.
     */
    private List<String> leafCategoryNames() {
        CategoryHierarchyService.Tree tree = hierarchyService.loadTree();

        List<String> names = new ArrayList<>();
        for (Category root : tree.roots()) {
            for (Category leaf : tree.leavesOf(root.getId())) {
                names.add(leaf.getName());
            }
        }
        return names;
    }

    private String createPrompt(List<String> categories, String transactionsList) {
        StringBuilder categoriesText = new StringBuilder();
        for (int i = 0; i < categories.size(); i++) {
            categoriesText.append(i + 1).append(". ").append(categories.get(i)).append('\n');
        }

        return String.format("""
        Ets un assistent financer. Classifica aquests moviments bancaris segons aquestes categories:
        %s

        MOVIMENTS A CLASSIFICAR:
        %s

        INSTRUCCIONS:
        - Respon EXCLUSIVAMENT en format JSON (una llista d'objectes).
        - Camps obligatoris: "companyName" (nom net de l'empresa), "category" (una de les categories de la llista, copiada exactament), "description_curta" (descripció breu), "cost" (import numèric), "date" (data original).
        - Si dubtes, marca el camp "dubte": true.
        - NO afegeixis text fora del JSON.
        """, categoriesText, transactionsList);
    }

    private String callGeminiApi(String promptText) {
        try {
            // Construct request body
            Map<String, Object> contentPart = new HashMap<>();
            contentPart.put("text", promptText);

            Map<String, Object> content = new HashMap<>();
            content.put("parts", List.of(contentPart));

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("contents", List.of(content));

            // Execute request
            String url = GEMINI_API_URL + apiKey;
            JsonNode response = restTemplate.postForObject(url, requestBody, JsonNode.class);

            if (response != null && response.has("candidates") && response.get("candidates").isArray()) {
                JsonNode candidate = response.get("candidates").get(0);
                if (candidate.has("content") && candidate.get("content").has("parts")) {
                    JsonNode parts = candidate.get("content").get("parts");
                    if (parts.isArray() && parts.size() > 0) {
                        return parts.get(0).get("text").asText();
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private List<Transaction> parseAndMergeTransactions(String jsonResponse, List<Transaction> originalTransactions) {
        try {
            // Clean markdown code blocks if present
            String cleanJson = jsonResponse.replace("```json", "").replace("```", "").strip();
            JsonNode rootNode = objectMapper.readTree(cleanJson);
            
            if (rootNode.isArray()) {
                // Si la IA no retorna exactament una fila per moviment, l'aparellament
                // per posició deixa de ser fiable. Abans, en aquest cas es retornaven
                // les transaccions de la IA sense hash, sense tipus i amb l'import que
                // ella hagués dit: es desaven dades corruptes i es perdia la protecció
                // contra duplicats. Ara es descarta la classificació i es conserven
                // els moviments originals, que són els bons.
                if (rootNode.size() != originalTransactions.size()) {
                    System.err.println("AiEngineService: la IA ha retornat " + rootNode.size()
                            + " files per a " + originalTransactions.size()
                            + " moviments; es descarta la classificació.");
                    return originalTransactions;
                }

                List<Transaction> classifiedTransactions = new ArrayList<>();

                for (int i = 0; i < originalTransactions.size(); i++) {
                    JsonNode node = rootNode.get(i);
                    Transaction original = originalTransactions.get(i);

                    // Es parteix de l'original i només s'hi apliquen els camps
                    // que la IA pot decidir: empresa, categoria i descripció.
                    // L'import, la data, el tipus i el hash no es toquen mai.
                    Transaction t = original;
                    t.setCompanyName(node.has("companyName") ? node.get("companyName").asText() : "Desconegut");
                    t.setCategoryName(node.has("category") ? node.get("category").asText() : "Altres");
                    t.setShortDescription(node.has("description_curta") ? node.get("description_curta").asText() : "");

                    classifiedTransactions.add(t);
                }

                return classifiedTransactions;
            }
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }
        return originalTransactions;
    }
}
