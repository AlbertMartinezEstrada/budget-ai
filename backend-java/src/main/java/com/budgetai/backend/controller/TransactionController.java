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
import com.budgetai.backend.service.TransactionHasher;
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
import java.util.HashSet;
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
    private TransactionHasher transactionHasher;

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
    /**
     * Lliga un moviment amb la seva categoria, empresa i compte, i li aplica
     * el saldo.
     *
     * Ho comparteixen la confirmació d'una importació i l'alta manual. És el
     * codi que mou diners, i tenir-ne dues còpies voldria dir que un arranjament
     * al saldo o a la regla de les fulles s'aplicaria només a una de les dues
     * portes d'entrada.
     *
     * Qui la cridi ha de ser @Transactional i deixar passar les excepcions:
     * si peta a la meitat, el saldo ja s'ha mogut i cal el rollback.
     */
    private void linkAndApplyToBalance(Transaction t, Account defaultAccount) {
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

    /**
     * Alta manual d'un moviment: efectiu, un préstec entre amics, qualsevol
     * cosa que no surti de l'extracte.
     *
     * NO se li calcula hash de verificació, i és a posta. El hash vol dir
     * "aquesta és una línia concreta d'un extracte i la sabré reconèixer".
     * Una alta manual no en té cap, i posar-n'hi un faria que dos cafès de
     * 2,50 € el mateix dia al mateix lloc es prenguessin per un duplicat i el
     * segon es descartés en silenci.
     *
     * @Transactional i sense capturar l'excepció, com la resta del que mou
     * diners: si el desat falla després d'haver tocat el saldo, cal el rollback.
     */
    @PostMapping("/gastos")
    @Transactional
    public ResponseEntity<?> createTransaction(@RequestBody Transaction transaction) {
        if (transaction.getAmount() == null || transaction.getAmount().signum() <= 0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error",
                    "message", "L'import ha de ser més gran que zero."));
        }
        if (transaction.getDate() == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error",
                    "message", "Falta la data del moviment."));
        }

        // L'import es desa sempre en positiu; el signe viu al tipus, igual que
        // als moviments que arriben del CSV.
        transaction.setAmount(transaction.getAmount().abs());
        transaction.setVerificationHash(null);

        // El saldo resultant només té sentit quan ve de l'extracte: allà és el
        // que deia el banc en aquell moment. Inventar-lo aquí faria que les
        // dues fonts diguessin coses diferents amb el mateix nom.
        transaction.setBalance(null);

        Account defaultAccount = accountRepository.findByName("Compte Principal")
                .orElseGet(() -> accountRepository.findAll().stream().findFirst().orElse(null));

        linkAndApplyToBalance(transaction, defaultAccount);
        Transaction saved = transactionRepository.save(transaction);

        return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Moviment afegit correctament",
                "id", saved.getId()));
    }

    /**
     * Esborra un moviment i **desfà** el que va fer al saldo.
     *
     * No n'hi ha prou d'esborrar la fila: el saldo del compte es va moure en
     * desar-lo i quedaria descompensat per sempre. És el mateix criteri que
     * amb les transferències.
     *
     * @Transactional i sense capturar l'excepció: si l'esborrat falla després
     * de tocar el saldo, cal el rollback.
     */
    @DeleteMapping("/gastos/{id}")
    @Transactional
    public ResponseEntity<?> deleteTransaction(@PathVariable Long id) {
        Transaction transaction = transactionRepository.findById(id).orElse(null);
        if (transaction == null) {
            return ResponseEntity.notFound().build();
        }

        // A l'inrevés que en desar: una despesa va restar, així que ara suma.
        if (transaction.getAccount() != null && transaction.getAmount() != null) {
            if ("EXPENSE".equals(transaction.getType())) {
                accountService.updateAccountBalance(
                        transaction.getAccount().getId(), transaction.getAmount(), "ADD");
            } else if ("INCOME".equals(transaction.getType())) {
                accountService.updateAccountBalance(
                        transaction.getAccount().getId(), transaction.getAmount(), "SUBTRACT");
            }
        }

        transactionRepository.delete(transaction);

        return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Moviment esborrat"));
    }

    @PostMapping("/confirm-upload")
    @Transactional
    public ResponseEntity<?> confirmUpload(@RequestBody List<Transaction> confirmedTransactions) {
        try {
            // Obtener la cuenta principal por defecto
            Account defaultAccount = accountRepository.findByName("Compte Principal")
                    .orElseGet(() -> accountRepository.findAll().stream().findFirst().orElse(null));

            // El hash es torna a calcular aquí, no arriba del client.
            //
            // Porta @JsonIgnore a propòsit —no ha de sortir mai cap al
            // navegador—, i per tant tampoc pot tornar-ne. Mentre es llegia del
            // cos de la petició era sempre null: la comprovació de duplicats no
            // descartava mai res, els moviments es desaven amb el hash a null i
            // la columna única no ho impedia perquè PostgreSQL admet tants
            // nulls com vulguis. Confirmar dues vegades el mateix lot —un doble
            // clic, un reintent després d'un error— el desava repetit.
            for (Transaction t : confirmedTransactions) {
                t.setVerificationHash(transactionHasher.hash(t));
            }

            Set<String> incomingHashes = confirmedTransactions.stream()
                    .map(Transaction::getVerificationHash)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());

            Set<String> alreadyStored = incomingHashes.isEmpty()
                    ? Set.of()
                    : transactionRepository.findByVerificationHashIn(incomingHashes).stream()
                            .map(Transaction::getVerificationHash)
                            .collect(Collectors.toSet());

            // Un lot pot portar dues vegades la mateixa fila si el fitxer la
            // duplica; sense el segon filtre passarien totes dues i la columna
            // única rebentaria la transacció sencera.
            Set<String> seen = new HashSet<>();
            List<Transaction> toPersist = confirmedTransactions.stream()
                    .filter(t -> !alreadyStored.contains(t.getVerificationHash()))
                    .filter(t -> seen.add(t.getVerificationHash()))
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
                linkAndApplyToBalance(t, defaultAccount);
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

    /**
     * Deixa arribar el motiu de l'error al client.
     *
     * Sense això la resposta era el cos d'error per defecte de Spring, que
     * porta el missatge buit si no s'activa server.error.include-message. El
     * frontend ensenyava "Error 500" i l'usuari no sabia què havia passat, per
     * exemple que la categoria triada era un grup.
     *
     * Capturar-la aquí no impedeix el rollback, a diferència de fer-ho amb un
     * try/catch dins del mètode: quan arriba en aquest punt, la transacció ja
     * s'ha desfet perquè l'excepció ha travessat el límit de @Transactional.
     */
    @ExceptionHandler(ConfirmUploadException.class)
    public ResponseEntity<Map<String, String>> handleConfirmUploadError(ConfirmUploadException e) {
        return ResponseEntity.internalServerError().body(Map.of(
                "status", "error",
                "message", e.getMessage() != null ? e.getMessage() : "Error desconegut"));
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
