package com.budgetai.backend.service;

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

    public List<Transaction> classifyTransactions(List<Transaction> transactions) {
        if (transactions == null || transactions.isEmpty()) {
            return new ArrayList<>();
        }

        // 1. Prepare prompt
        String transactionsListString = formatTransactionsForPrompt(transactions);
        String prompt = createPrompt(transactionsListString);

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
            sb.append(String.format("%s, %s, %.2f\n", 
                t.getOriginalConcept().replace(",", " "), 
                t.getDate().toString(), 
                t.getAmount()));
        }
        return sb.toString();
    }

    private String createPrompt(String transactionsList) {
        String categoriesText = """
        1. Menjar i supermercat, 2. Bars i restaurants, 3. Transport, 4. Allotjament, 
        5. Compres i roba, 6. Higiene i bellesa, 7. Salut i farmàcia, 8. Gimnàs i esport, 
        9. Cultura, oci i entreteniment, 10. Jocs de taula i videojocs, 11. Festa i alcohol, 
        12. Tecnologia, 13. Regals i detalls, 14. Casa i mobiliari, 15. Mascotes, 
        16. Altres, 17. Educació, 18. Inversions, 19. Nòmina, 20. Ingressos Altres.
        """;

        return String.format("""
        Ets un assistent financer. Classifica aquests moviments bancaris segons aquestes categories:
        %s

        MOVIMENTS A CLASSIFICAR:
        %s

        INSTRUCCIONS:
        - Respon EXCLUSIVAMENT en format JSON (una llista d'objectes).
        - Camps obligatoris: "companyName" (nom net de l'empresa), "category" (una de les 20 categories), "description_curta" (descripció breu), "cost" (import numèric), "date" (data original).
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
                List<Transaction> classifiedTransactions = new ArrayList<>();
                
                for (JsonNode node : rootNode) {
                    Transaction t = new Transaction();
                    t.setCompanyName(node.has("companyName") ? node.get("companyName").asText() : "Desconegut");
                    t.setCategoryName(node.has("category") ? node.get("category").asText() : "Altres");
                    t.setShortDescription(node.has("description_curta") ? node.get("description_curta").asText() : "");
                    t.setAmount(node.has("cost") ? node.get("cost").asDouble() : 0.0);
                    
                    if (node.has("date")) {
                        try {
                           t.setDate(LocalDate.parse(node.get("date").asText()));
                        } catch (Exception ex) { /* fall back a original si falla */ }
                    }
                    classifiedTransactions.add(t);
                }
                
                // Merging original data back if sizes match
                if (classifiedTransactions.size() == originalTransactions.size()) {
                    for (int i = 0; i < classifiedTransactions.size(); i++) {
                        Transaction classified = classifiedTransactions.get(i);
                        Transaction original = originalTransactions.get(i);
                        
                        classified.setOriginalConcept(original.getOriginalConcept());
                        classified.setDate(original.getDate());
                        classified.setAmount(original.getAmount());
                        classified.setBalance(original.getBalance());
                        classified.setType(original.getType());
                        classified.setVerificationHash(original.getVerificationHash());
                    }
                }
                return classifiedTransactions;
            }
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }
        return originalTransactions;
    }
}
