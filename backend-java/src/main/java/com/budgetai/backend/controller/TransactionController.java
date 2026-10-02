package com.budgetai.backend.controller;

import com.budgetai.backend.model.Account;
import com.budgetai.backend.model.Category;
import com.budgetai.backend.model.Company;
import com.budgetai.backend.model.Debt;
import com.budgetai.backend.model.Transaction;
import com.budgetai.backend.repository.AccountRepository;
import com.budgetai.backend.repository.CategoryRepository;
import com.budgetai.backend.repository.CompanyRepository;
import com.budgetai.backend.repository.TransactionRepository;
import com.budgetai.backend.service.AccountService;
import com.budgetai.backend.service.AiEngineService;
import com.budgetai.backend.service.BankReaderService;
import com.budgetai.backend.service.CategoryHierarchyService;
import com.budgetai.backend.service.DebtService;
import com.budgetai.backend.service.ImportRuleService;
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

    @Autowired
    private ImportRuleService importRuleService;

    @Autowired
    private DebtService debtService;

    @GetMapping("/")
    public Map<String, String> readRoot() {
        return Map.of("status", "API Budget AI (Java) con BBDD profesional funcionant correctament", "version", "2.0");
    }

    @PostMapping("/upload-csv")
    public ResponseEntity<?> uploadCsv(@RequestParam("file") MultipartFile file,
                                      @RequestParam(required = false) Long accountId) {
        try {
            // 1. Read and clean CSV
            List<Transaction> initialTransactions = bankReaderService.readBankCsv(file);

            if (initialTransactions.isEmpty()) {
                return ResponseEntity.badRequest().body("No s'han trobat moviments al CSV");
            }

            // 2. Es descarta el que ja s'ha importat en aquest mateix compte.
            //
            // El compte hi entra perquè un traspàs deixa el mateix import el
            // mateix dia als extractes dels dos comptes. Mirant només el hash,
            // la segona pota es filtrava aquí i l'usuari no arribava ni a
            // veure-la a la pantalla de revisió.
            List<Transaction> newTransactions = initialTransactions.stream()
                    .filter(transaction -> transactionRepository.findByVerificationHash(transaction.getVerificationHash())
                            .map(existing -> {
                                Long existingAccount = existing.getAccount() != null
                                        ? existing.getAccount().getId() : null;
                                return !Objects.equals(existingAccount, accountId);
                            })
                            .orElse(true))
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

            // 4. I al damunt, les regles de l'usuari. Van després de la IA a
            //    posta: una regla és una decisió explícita seva i ha de manar
            //    sobre el que endevini el model.
            importRuleService.apply(classifiedTransactions);

            return ResponseEntity.ok(Map.of(
                    "status", "review",
                    "message", "Revisa els nous moviments trobats (" + classifiedTransactions.size() + ")",
                    "data", classifiedTransactions
            ));

        } catch (Exception exception) {
            // Un CSV il·legible és culpa del fitxer (400) i el motiu ha
            // d'arribar; qualsevol altra cosa és nostra (500) i va al log.
            HttpStatus status = ClientErrors.isForTheUser(exception)
                    ? HttpStatus.BAD_REQUEST
                    : HttpStatus.INTERNAL_SERVER_ERROR;
            return ResponseEntity.status(status).body(Map.of("error",
                    "Error processant el fitxer: " + ClientErrors.messageFor(exception, "Pujar extracte")));
        }
    }

    /**
     * Identitat d'un moviment importat: la seva línia d'extracte i el compte.
     *
     * El hash sol no basta des que hi ha diversos comptes. Un traspàs produeix
     * el mateix import el mateix dia a dos extractes diferents, i mirant només
     * el hash el segon es prenia per un duplicat i es descartava en silenci
     * —just el moviment que l'usuari vol veure a l'altre compte—.
     */
    private static String identityOf(Transaction transaction) {
        Long accountId = transaction.getAccount() != null ? transaction.getAccount().getId() : null;
        return transaction.getVerificationHash() + "@" + accountId;
    }

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
    private void linkAndApplyToBalance(Transaction transaction, Account defaultAccount) {
        // Category (Sempre n'ha d'haver una de les oficials)
        String categoryName = transaction.getCategoryName();
        if (categoryName == null || categoryName.isEmpty()) categoryName = "Altres";

        final String finalCategoryName = categoryName;
        Category category = categoryRepository.findByName(finalCategoryName)
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
        transaction.setCategory(category);

        // Company (Si no existeix la creem)
        String companyName = transaction.getCompanyName();
        if (companyName == null || companyName.isEmpty()) companyName = "Desconegut";

        final String finalCompName = companyName;
        Company company = companyRepository.findByName(finalCompName)
                .orElseGet(() -> companyRepository.save(new Company(finalCompName)));
        transaction.setCompany(company);

        // Assegurar que el tipus es manté (INCOME/EXPENSE)
        if (transaction.getType() == null) {
            transaction.setType("EXPENSE");
        }

        // Asignar cuenta si no tiene
        if (transaction.getAccount() == null && defaultAccount != null) {
            transaction.setAccount(defaultAccount);
        }

        // Actualizar el saldo de la cuenta
        if (transaction.getAccount() != null) {
            if ("EXPENSE".equals(transaction.getType())) {
                accountService.updateAccountBalance(transaction.getAccount().getId(), transaction.getAmount(), "SUBTRACT");
            } else if ("INCOME".equals(transaction.getType())) {
                accountService.updateAccountBalance(transaction.getAccount().getId(), transaction.getAmount(), "ADD");
            }
        }
    }

    /**
     * Desfà el que un moviment va fer al saldo del seu compte.
     *
     * A l'inrevés que en desar-lo: una despesa va restar, així que ara suma.
     * Ho comparteixen esborrar i editar —editar és desfer i tornar a aplicar—,
     * i el compte que es toca és el que tenia abans, no el nou.
     */
    private void revertFromBalance(Transaction transaction) {
        if (transaction.getAccount() == null || transaction.getAmount() == null) return;

        if ("EXPENSE".equals(transaction.getType())) {
            accountService.updateAccountBalance(
                    transaction.getAccount().getId(), transaction.getAmount(), "ADD");
        } else if ("INCOME".equals(transaction.getType())) {
            accountService.updateAccountBalance(
                    transaction.getAccount().getId(), transaction.getAmount(), "SUBTRACT");
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

        // L'import es desa sempre en positiu i el signe viu al tipus, igual que
        // als moviments del CSV. Un negatiu no es normalitza amb abs(): es
        // rebutja a la validació de dalt. Girar-li el signe en silenci seria
        // endevinar què volia dir qui escriu "-12,50" en un ingrés, i pot ser
        // tant "és una despesa" com "m'he equivocat de camp".
        transaction.setVerificationHash(null);

        // El saldo resultant només té sentit quan ve de l'extracte: allà és el
        // que deia el banc en aquell moment. Inventar-lo aquí faria que les
        // dues fonts diguessin coses diferents amb el mateix nom.
        transaction.setBalance(null);

        // Abans de tocar cap saldo: si el deute no existeix, no s'ha mogut res
        // i es pot respondre sense haver de desfer.
        try {
            transaction.setDebt(debtService.resolveForLink(transaction.getDebt()));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error",
                    "message", exception.getMessage()));
        }

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
     * Edita un moviment ja desat.
     *
     * Editar és **desfer i tornar a aplicar**: primer es reverteix el que el
     * moviment havia fet al saldo del seu compte d'abans, i després s'aplica
     * el nou. Sense la reversió, canviar l'import de 40 a 50 restaria 50 més
     * en comptes de 10, i canviar-lo de compte deixaria el saldo mogut als
     * dos.
     *
     * El hash NO es toca. Identifica de quina línia d'extracte ve el moviment,
     * i corregir-ne l'empresa o la categoria no el converteix en una altra
     * línia: recalcular-lo faria que tornar a importar el mateix fitxer el
     * dupliqués, que és justament el que el hash evita.
     *
     * Actualització parcial: un camp absent no s'ha de tocar.
     *
     * @Transactional i sense capturar l'excepció, com la resta del que mou
     * diners.
     */
    @PutMapping("/gastos/{id}")
    @Transactional
    public ResponseEntity<?> updateTransaction(@PathVariable Long id,
                                               @RequestBody Transaction changes) {
        Transaction existing = transactionRepository.findById(id).orElse(null);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }

        if (changes.getAmount() != null && changes.getAmount().signum() <= 0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error",
                    "message", "L'import ha de ser més gran que zero."));
        }

        // El deute es resol abans de desfer el saldo, pel mateix motiu que a
        // l'alta. Null vol dir que no l'han enviat; un negatiu, que el volen
        // desvincular, i resolveForLink el torna com a null.
        Debt debt = existing.getDebt();
        if (changes.getDebt() != null) {
            try {
                debt = debtService.resolveForLink(changes.getDebt());
            } catch (IllegalArgumentException exception) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "error",
                        "message", exception.getMessage()));
            }
        }

        revertFromBalance(existing);

        existing.setDebt(debt);
        if (changes.getDate() != null) existing.setDate(changes.getDate());
        if (changes.getAmount() != null) existing.setAmount(changes.getAmount());
        if (changes.getType() != null) existing.setType(changes.getType());
        if (changes.getShortDescription() != null) existing.setShortDescription(changes.getShortDescription());
        if (changes.getCategoryName() != null) existing.setCategoryName(changes.getCategoryName());
        if (changes.getCompanyName() != null) existing.setCompanyName(changes.getCompanyName());
        if (changes.getExcludedFromBudget() != null) {
            existing.setExcludedFromBudget(changes.getExcludedFromBudget());
        }

        if (changes.getAccount() != null && changes.getAccount().getId() != null) {
            accountRepository.findById(changes.getAccount().getId()).ifPresent(existing::setAccount);
        }

        // Torna a lligar categoria i empresa —poden haver canviat de nom— i
        // aplica el saldo nou al compte que toqui ara.
        linkAndApplyToBalance(existing, existing.getAccount());
        transactionRepository.save(existing);

        return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Moviment actualitzat"));
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

        revertFromBalance(transaction);
        transactionRepository.delete(transaction);

        return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Moviment esborrat"));
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

            // El hash es torna a calcular aquí, no arriba del client.
            //
            // Porta @JsonIgnore a propòsit —no ha de sortir mai cap al
            // navegador—, i per tant tampoc pot tornar-ne. Mentre es llegia del
            // cos de la petició era sempre null: la comprovació de duplicats no
            // descartava mai res, els moviments es desaven amb el hash a null i
            // la columna única no ho impedia perquè PostgreSQL admet tants
            // nulls com vulguis. Confirmar dues vegades el mateix lot —un doble
            // clic, un reintent després d'un error— el desava repetit.
            // El compte s'assigna abans de comparar, perquè forma part de la
            // identitat del moviment.
            for (Transaction transaction : confirmedTransactions) {
                if (transaction.getAccount() == null && defaultAccount != null) {
                    transaction.setAccount(defaultAccount);
                }
                transaction.setVerificationHash(transactionHasher.hash(transaction));
                // La pantalla de revisió no vincula deutes, però deute_id s'ha
                // de llegir igual per les tres portes d'entrada: sense resoldre'l,
                // un -1 arribaria a la base de dades com a clau forana.
                transaction.setDebt(debtService.resolveForLink(transaction.getDebt()));
            }

            Set<String> incomingHashes = confirmedTransactions.stream()
                    .map(Transaction::getVerificationHash)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());

            Set<String> alreadyStored = incomingHashes.isEmpty()
                    ? Set.of()
                    : transactionRepository.findByVerificationHashIn(incomingHashes).stream()
                            .map(TransactionController::identityOf)
                            .collect(Collectors.toSet());

            // Un lot pot portar dues vegades la mateixa fila si el fitxer la
            // duplica; sense el segon filtre passarien totes dues i la columna
            // única rebentaria la transacció sencera.
            Set<String> seen = new HashSet<>();
            List<Transaction> toPersist = confirmedTransactions.stream()
                    .filter(transaction -> !alreadyStored.contains(identityOf(transaction)))
                    .filter(transaction -> seen.add(identityOf(transaction)))
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

            for (Transaction transaction : toPersist) {
                linkAndApplyToBalance(transaction, defaultAccount);
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
        } catch (ConfirmUploadException exception) {
            // Ja porta un missatge pensat per a l'usuari (una categoria que és
            // un grup): es rellança tal qual.
            throw exception;
        } catch (Exception exception) {
            // Es rellança perquè la transacció faci rollback: capturar-la i
            // retornar un ResponseEntity deixaria els saldos ja modificats.
            throw new ConfirmUploadException(
                    "Error guardant: " + ClientErrors.messageFor(exception, "Confirmar importació"), exception);
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
    public ResponseEntity<Map<String, String>> handleConfirmUploadError(ConfirmUploadException exception) {
        return ResponseEntity.internalServerError().body(Map.of(
                "status", "error",
                "message", exception.getMessage() != null ? exception.getMessage() : "Error desconegut"));
    }

    @GetMapping("/gastos")
    public List<Transaction> getTransactions(
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long companyId,
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La data 'des de' no pot ser posterior a la data 'fins a'");
        }

        if (categoryId != null || companyId != null || accountId != null
                || (type != null && !type.isBlank()) || startDate != null || endDate != null) {
            Specification<Transaction> specification = Specification.unrestricted();

            if (categoryId != null) {
                specification = specification.and((root, query, criteriaBuilder) ->
                        criteriaBuilder.equal(root.get("category").get("id"), categoryId));
            }

            if (companyId != null) {
                specification = specification.and((root, query, criteriaBuilder) ->
                        criteriaBuilder.equal(root.get("company").get("id"), companyId));
            }

            // L'import es desa en positiu i el signe viu al tipus, així que
            // separar despeses d'ingressos només es pot fer per aquí.
            if (type != null && !type.isBlank()) {
                specification = specification.and((root, query, criteriaBuilder) ->
                        criteriaBuilder.equal(root.get("type"), type));
            }

            // Amb diversos comptes, mirar-los per separat és el que permet
            // quadrar el saldo de cadascun amb el seu extracte.
            if (accountId != null) {
                specification = specification.and((root, query, criteriaBuilder) ->
                        criteriaBuilder.equal(root.get("account").get("id"), accountId));
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
