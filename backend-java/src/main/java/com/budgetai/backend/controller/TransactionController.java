package com.budgetai.backend.controller;

import com.budgetai.backend.model.Account;
import com.budgetai.backend.model.Category;
import com.budgetai.backend.model.Company;
import com.budgetai.backend.model.Transaction;
import com.budgetai.backend.repository.AccountRepository;
import com.budgetai.backend.repository.CategoryRepository;
import com.budgetai.backend.repository.CompanyRepository;
import com.budgetai.backend.repository.TransactionRepository;
import com.budgetai.backend.service.AccountService;
import com.budgetai.backend.service.AiEngineService;
import com.budgetai.backend.service.BankReaderService;
import com.budgetai.backend.service.CategoryHierarchyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/")
public class TransactionController {

    @Autowired
    private BankReaderService bankReaderService;

    @Autowired
    private AiEngineService aiEngineService;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AccountService accountService;

    @Autowired
    private CategoryHierarchyService categoryHierarchyService;

    @GetMapping("/")
    public Map<String, String> readRoot() {
        return Map.of("status", "API Budget AI (Java) con BBDD profesional funcionant correctament", "version", "2.0");
    }

    @PostMapping("/upload-csv")
    public ResponseEntity<?> uploadCsv(@RequestParam("file") MultipartFile file) {
        try {
            // 1. Read and clean CSV
            List<Transaction> initialTransactions = bankReaderService.readBankCsv(file);

            if (initialTransactions.isEmpty()) {
                return ResponseEntity.badRequest().body("No s'han trobat moviments al CSV");
            }

            // 2. Filter out already existing transactions by hash
            List<Transaction> newTransactions = initialTransactions.stream()
                    .filter(t -> transactionRepository.findByVerificationHash(t.getVerificationHash()).isEmpty())
                    .collect(Collectors.toList());

            if (newTransactions.isEmpty()) {
                return ResponseEntity.ok(Map.of(
                        "status", "info",
                        "message", "Tots els moviments d'aquest fitxer ja existeixen a la BBDD",
                        "data", new ArrayList<>()
                ));
            }

            // 3. Classify with IA
            List<Transaction> classifiedTransactions = aiEngineService.classifyTransactions(newTransactions);

            return ResponseEntity.ok(Map.of(
                    "status", "review",
                    "message", "Revisa els nous moviments trobats (" + classifiedTransactions.size() + ")",
                    "data", classifiedTransactions
            ));

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body("Error processant el fitxer: " + e.getMessage());
        }
    }

    // @Transactional: desar els moviments i ajustar els saldos ha de ser una
    // sola operació. Abans els saldos es tocaven dins del bucle i el saveAll
    // era l'últim pas, així que un error deixava saldos moguts sense moviments.
    @PostMapping("/confirm-upload")
    @Transactional
    public ResponseEntity<?> confirmUpload(@RequestBody List<Transaction> confirmedTransactions) {
        try {
            // Obtener la cuenta principal por defecto
            Account defaultAccount = accountRepository.findByName("Compte Principal")
                    .orElseGet(() -> accountRepository.findAll().stream().findFirst().orElse(null));

            // El hash només es comprovava en pujar el fitxer. Entre la pujada i
            // la confirmació el mateix lot es pot enviar dues vegades (doble
            // clic, reintent), així que cal tornar-ho a mirar aquí.
            Set<String> incomingHashes = confirmedTransactions.stream()
                    .map(Transaction::getVerificationHash)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());

            Set<String> alreadyStored = incomingHashes.isEmpty()
                    ? Set.of()
                    : transactionRepository.findByVerificationHashIn(incomingHashes).stream()
                            .map(Transaction::getVerificationHash)
                            .collect(Collectors.toSet());

            List<Transaction> toPersist = confirmedTransactions.stream()
                    .filter(t -> t.getVerificationHash() == null
                            || !alreadyStored.contains(t.getVerificationHash()))
                    .collect(Collectors.toList());

            int skipped = confirmedTransactions.size() - toPersist.size();

            if (toPersist.isEmpty()) {
                return ResponseEntity.ok(Map.of(
                        "status", "info",
                        "message", "Tots els moviments ja existien a la BBDD",
                        "saved", 0,
                        "skipped", skipped
                ));
            }

            for (Transaction t : toPersist) {
                // Category (Sempre n'ha d'haver una de les oficials)
                String catName = t.getCategoryName();
                if (catName == null || catName.isEmpty()) catName = "Altres";

                final String finalCatName = catName;
                Category category = categoryRepository.findByName(finalCatName)
                        .orElseGet(() -> categoryRepository.findByName("Altres").get());

                // Les transaccions només s'assignen a fulles: un grup existeix
                // per agregar els seus fills, no per rebre moviments. Si la
                // categoria triada és un grup, el moviment aniria a parar a un
                // node que després tornaria a sumar-lo pels fills i es
                // comptaria dues vegades.
                if (categoryHierarchyService.isGroup(category.getId())) {
                    throw new ConfirmUploadException(
                            "La categoria \"" + category.getName() + "\" és un grup: "
                                    + "tria'n una de concreta.", null);
                }
                t.setCategory(category);

                // Company (Si no existeix la creem)
                String compName = t.getCompanyName();
                if (compName == null || compName.isEmpty()) compName = "Desconegut";

                final String finalCompName = compName;
                Company company = companyRepository.findByName(finalCompName)
                        .orElseGet(() -> companyRepository.save(new Company(finalCompName)));
                t.setCompany(company);

                // Assegurar que el tipus es manté (INCOME/EXPENSE)
                if (t.getType() == null) {
                    t.setType("EXPENSE");
                }

                // Asignar cuenta si no tiene
                if (t.getAccount() == null && defaultAccount != null) {
                    t.setAccount(defaultAccount);
                }

                // Actualizar el saldo de la cuenta
                if (t.getAccount() != null) {
                    if ("EXPENSE".equals(t.getType())) {
                        accountService.updateAccountBalance(t.getAccount().getId(), t.getAmount(), "SUBTRACT");
                    } else if ("INCOME".equals(t.getType())) {
                        accountService.updateAccountBalance(t.getAccount().getId(), t.getAmount(), "ADD");
                    }
                }
            }

            transactionRepository.saveAll(toPersist);

            String message = skipped > 0
                    ? toPersist.size() + " moviments guardats (" + skipped + " ja existien)"
                    : toPersist.size() + " moviments guardats correctament";

            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "message", message,
                    "saved", toPersist.size(),
                    "skipped", skipped
            ));
        } catch (Exception e) {
            // Es rellança perquè la transacció faci rollback: capturar-la i
            // retornar un ResponseEntity deixaria els saldos ja modificats.
            throw new ConfirmUploadException("Error guardant: " + e.getMessage(), e);
        }
    }

    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    private static class ConfirmUploadException extends RuntimeException {
        ConfirmUploadException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    @GetMapping("/gastos")
    public List<Transaction> getTransactions(
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long companyId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La data 'des de' no pot ser posterior a la data 'fins a'");
        }

        if (categoryId != null || companyId != null || startDate != null || endDate != null) {
            Specification<Transaction> specification = Specification.where(null);

            if (categoryId != null) {
                specification = specification.and((root, query, criteriaBuilder) ->
                        criteriaBuilder.equal(root.get("category").get("id"), categoryId));
            }

            if (companyId != null) {
                specification = specification.and((root, query, criteriaBuilder) ->
                        criteriaBuilder.equal(root.get("company").get("id"), companyId));
            }

            if (startDate != null) {
                specification = specification.and((root, query, criteriaBuilder) ->
                        criteriaBuilder.greaterThanOrEqualTo(root.get("date"), startDate));
            }

            if (endDate != null) {
                specification = specification.and((root, query, criteriaBuilder) ->
                        criteriaBuilder.lessThanOrEqualTo(root.get("date"), endDate));
            }

            return transactionRepository.findAll(specification, Sort.by(Sort.Direction.DESC, "date"));
        }
        return transactionRepository.findAllByOrderByDateDesc();
    }

    @GetMapping("/companies")
    public List<Company> getCompanies() {
        return companyRepository.findAll();
    }
}
