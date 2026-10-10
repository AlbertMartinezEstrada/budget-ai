package com.budgetai.backend.integration;

import com.budgetai.backend.model.Account;
import com.budgetai.backend.model.Category;
import com.budgetai.backend.model.Transaction;
import com.budgetai.backend.repository.AccountRepository;
import com.budgetai.backend.repository.CategoryRepository;
import com.budgetai.backend.repository.TransactionRepository;
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
import java.time.LocalDate;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Què arriba al navegador quan alguna cosa falla.
 *
 * Abans, la pantalla deia "Not Found", "Bad Request" o "Error 404" sense
 * explicar res: el cos d'error per defecte de Spring no porta missatge, i
 * alguns controladors convertien qualsevol error en un 404 buit. Dividir un
 * moviment amb el backend sense reconstruir donava "Not Found" i prou.
 *
 * Es fa per HTTP, amb la sessió i els filtres de debò: el gestor d'errors
 * global només actua quan la petició passa per Spring MVC.
 */
@AutoConfigureMockMvc
class ApiErrorsIntegrationTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private CategoryRepository categoryRepository;

    private Cookie session;
    private Long transactionId;

    @BeforeEach
    void setUp() throws Exception {
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        if (categoryRepository.findByName("Altres").isEmpty()) {
            categoryRepository.save(new Category("Altres"));
        }
        Account account = new Account();
        account.setName("Compte Principal");
        account.setCurrentBalance(new BigDecimal("1000.00"));
        accountRepository.save(account);

        Transaction transaction = new Transaction();
        transaction.setType("EXPENSE");
        transaction.setAmount(new BigDecimal("500.00"));
        transaction.setDate(LocalDate.of(2026, 10, 1));
        transaction.setCategory(categoryRepository.findByName("Altres").orElseThrow());
        transactionId = transactionRepository.save(transaction).getId();

        session = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", "test-user", "password", "test-password"))))
                .andReturn().getResponse().getCookie("budget_session");
    }

    @Test
    @DisplayName("Una ruta que el backend no coneix diu que potser cal reconstruir-lo")
    void unknownRouteExplainsTheLikelyCause() throws Exception {
        mockMvc.perform(put("/gastos/" + transactionId + "/no-existeix").cookie(session)
                        .contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message", containsString("PUT /gastos/" + transactionId + "/no-existeix")))
                .andExpect(jsonPath("$.message", containsString("docker compose up -d --build backend")));
    }

    @Test
    @DisplayName("Dividir un moviment que no existeix diu quin moviment")
    void missingTransactionSaysWhich() throws Exception {
        mockMvc.perform(put("/gastos/999999/parts").cookie(session)
                        .contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", containsString("No existeix el moviment 999999")))
                .andExpect(jsonPath("$.path").value("/gastos/999999/parts"));
    }

    @Test
    @DisplayName("Unes parts que no quadren arriben com un 400 que diu quant falta")
    void partsThatDoNotAddUpAreABadRequest() throws Exception {
        String parts = "[{\"import\":300,\"category\":{\"id\":" + altresId() + "}},"
                + "{\"import\":150,\"category\":{\"id\":" + altresId() + "}}]";

        mockMvc.perform(put("/gastos/" + transactionId + "/parts").cookie(session)
                        .contentType(MediaType.APPLICATION_JSON).content(parts))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("en falten 50.00 €")));
    }

    @Test
    @DisplayName("Editar un compte que no existeix ja no és un 404 buit")
    void missingAccountSaysWhich() throws Exception {
        mockMvc.perform(put("/accounts/999999").cookie(session)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nom\":\"Revolut\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", containsString("No existeix el compte 999999")));
    }

    @Test
    @DisplayName("Un JSON mal escrit és un 400 amb un missatge que no ensenya classes de Java")
    void malformedJsonIsExplained() throws Exception {
        mockMvc.perform(post("/gastos").cookie(session)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"cost\": dotze"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("format")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("com.fasterxml"))));
    }

    @Test
    @DisplayName("Un paràmetre que no és del tipus esperat diu quin és")
    void wrongParameterSaysWhich() throws Exception {
        mockMvc.perform(get("/gastos").param("startDate", "ahir").cookie(session))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("startDate")));
    }

    @Test
    @DisplayName("Una validació que abans tornava un 400 buit ara diu què falla")
    void validationExplainsItself() throws Exception {
        mockMvc.perform(post("/goals/999999/add-amount").cookie(session)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("més gran que zero")));
    }

    @Test
    @DisplayName("Un extracte que no es pot llegir és un 400 que diu la línia, no un error intern")
    void unreadableStatementIsExplained() throws Exception {
        // Un text enganxat després de tancar unes cometes: el mateix error de
        // commons-csv que donava l'extracte de Trade Republic abans de
        // reconèixer-lo, i que arribava com "Error intern".
        MockMultipartFile broken = new MockMultipartFile(
                "file", "trencat.csv", "text/csv",
                "Fecha;Concepto;Importe\n01/07/2026;\"COMPRA\"x;-45.30\n".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/upload-csv").file(broken).cookie(session))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("a la línia 2")))
                .andExpect(jsonPath("$.error", containsString("Trade Republic")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Error intern"))));
    }

    @Test
    @DisplayName("L'extracte de Trade Republic es llegeix i arriba a la pantalla de revisió")
    void tradeRepublicStatementIsRead() throws Exception {
        String content = """
                "datetime","date","account_type","category","type","asset_class","name","symbol","shares","price","amount","fee","tax","currency","original_amount","original_currency","fx_rate","description","transaction_id","counterparty_name","counterparty_iban","payment_reference","mcc_code"
                "2026-10-01T03:24:22.922096Z","2026-10-01","DEFAULT","CASH","INTEREST_PAYMENT","","","","","","6.520000","","","EUR","","","","Interest payment for payout collection 01a0","id-1","","","",""
                "2026-10-06T15:24:18.913169Z","2026-10-06","DEFAULT","CASH","TRANSFER_INSTANT_INBOUND","","TITULAR DE PROVA","","","","1276.000000","","","EUR","","","","Incoming transfer from TITULAR DE PROVA","id-2","TITULAR DE PROVA","","",""
                """;
        MockMultipartFile statement = new MockMultipartFile(
                "file", "extractoTradeRepublic.csv", "text/csv", content.getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/upload-csv").file(statement).cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("review"))
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    private Long altresId() {
        return categoryRepository.findByName("Altres").orElseThrow().getId();
    }
}
