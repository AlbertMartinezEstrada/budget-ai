package com.budgetai.backend.controller;

import com.budgetai.backend.model.Account;
import com.budgetai.backend.model.Category;
import com.budgetai.backend.model.Company;
import com.budgetai.backend.model.Debt;
import com.budgetai.backend.model.Transaction;
import com.budgetai.backend.model.TransactionPart;
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
import com.budgetai.backend.service.InternalTransferService;
import com.budgetai.backend.service.TransactionHasher;
import com.budgetai.backend.service.TransactionPartService;
import com.budgetai.backend.service.NotFoundException;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
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
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
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

    @Autowired
    private TransactionPartService transactionPartService;

    @Autowired
    private InternalTransferService internalTransfers;

    /** El compte on va un moviment que no en diu cap. */
    private Account defaultAccount() {
        return accountRepository.findByName("Compte Principal")
                .orElseGet(() -> accountRepository.findAll().stream().findFirst().orElse(null));
    }

    /**
     * El compte d'un moviment, sencer. De la petició només n'arriba l'id, i per
     * saber si un traspàs compta cal el tipus: un compte a mig carregar
     * semblaria sempre del dia a dia.
     */
    private Account ownAccountOf(Transaction transaction, Account fallback) {
        if (transaction.getAccount() == null || transaction.getAccount().getId() == null) return fallback;
        return accountRepository.findById(transaction.getAccount().getId()).orElse(fallback);
    }

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

            // 5. Els traspassos: els que ha marcat una regla, i les línies que
            //    són l'altra pota d'un traspàs ja desat des de l'altre compte.
            Account importAccount = accountId != null
                    ? accountRepository.findById(accountId).orElse(null)
                    : defaultAccount();
            internalTransfers.prepareForReview(classifiedTransactions, importAccount);

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
            // El nom del fitxer i el compte, perquè la línia del log digui
            // quin extracte ha fallat sense haver de demanar-lo.
            String context = "Pujar extracte «" + file.getOriginalFilename() + "» ("
                    + (file.getSize() / 1024) + " KB, compte " + (accountId != null ? accountId : "per defecte") + ")";
            return ResponseEntity.status(status).body(Map.of("error",
                    "Error processant el fitxer: " + ClientErrors.messageFor(exception, context)));
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
                            + "tria'n una de concreta.", null, HttpStatus.BAD_REQUEST);
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

        // Un traspàs mou també el saldo de l'altre compte: el que surt d'aquí
        // hi entra. És el que fa que Trade Republic pugi sense importar-ne
        // l'extracte.
        internalTransfers.applyToCounterpart(transaction);
    }

    /**
     * Desfà el que un moviment va fer al saldo del seu compte.
     *
     * A l'inrevés que en desar-lo: una despesa va restar, així que ara suma.
     * Ho comparteixen esborrar i editar —editar és desfer i tornar a aplicar—,
     * i el compte que es toca és el que tenia abans, no el nou.
     */
    private void revertFromBalance(Transaction transaction) {
        internalTransfers.revertFromCounterpart(transaction);
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

        Account defaultAccount = defaultAccount();
        Account own = ownAccountOf(transaction, defaultAccount);
        transaction.setAccount(own);

        // Abans de tocar cap saldo: si el deute no existeix, les parts no
        // quadren o el traspàs va al mateix compte, no s'ha mogut res i es pot
        // respondre sense haver de desfer.
        List<TransactionPart> parts;
        try {
            transaction.setDebt(debtService.resolveForLink(transaction.getDebt()));
            // Un rebut sense deute no vol dir res.
            if (transaction.getDebt() == null) transaction.setDebtReceipt(null);
            internalTransfers.link(transaction, own,
                    internalTransfers.resolve(transaction.getCounterpartAccount(), own));
            parts = splitBeforeSaving(transaction);
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error",
                    "message", exception.getMessage()));
        }

        linkAndApplyToBalance(transaction, defaultAccount);
        Transaction saved = transactionRepository.save(transaction);
        if (!parts.isEmpty()) transactionPartService.store(saved, parts);
        Transaction joined = absorbOtherLeg(saved);

        return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Moviment afegit correctament" + joinedNote(joined),
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
        // Abans de tocar cap saldo: no hi ha res a desfer.
        Transaction existing = transactionRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("el moviment", id));

        if (changes.getAmount() != null && changes.getAmount().signum() <= 0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error",
                    "message", "L'import ha de ser més gran que zero."));
        }

        // Les parts tenen el seu camí, que comprova que sumin el total. Si
        // arribessin aquí, s'ignorarien sense dir res.
        if (changes.getParts() != null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error",
                    "message", "Les parts d'un moviment es canvien amb el botó de dividir, no editant-lo."));
        }

        // Un moviment dividit no pot canviar d'import sense que les parts
        // deixin de sumar-lo, ni portar el deute al moviment: el vincle és de
        // la part. Es comprova abans de tocar el saldo, com la resta.
        if (transactionPartService.isSplit(id)) {
            if (changes.getAmount() != null && changes.getAmount().compareTo(existing.getAmount()) != 0) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "error",
                        "message", "Aquest moviment està dividit en parts. Canvia-les perquè sumin el nou "
                                + "import, o treu la divisió abans de canviar-lo."));
            }
            if (changes.getDebt() != null && changes.getDebt().getId() != null && changes.getDebt().getId() > 0) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "error",
                        "message", "Aquest moviment està dividit en parts: vincula el deute a la part que toca."));
            }
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

        // El traspàs també: amb el compte que tindrà després, que pot ser
        // precisament el de l'altre costat.
        Account ownAfter = changes.getAccount() != null && changes.getAccount().getId() != null
                ? accountRepository.findById(changes.getAccount().getId()).orElse(existing.getAccount())
                : existing.getAccount();
        boolean counterpartSent = changes.getCounterpartAccount() != null;
        Account counterpartAfter;
        try {
            counterpartAfter = internalTransfers.resolve(
                    counterpartSent ? changes.getCounterpartAccount() : existing.getCounterpartAccount(), ownAfter);
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error",
                    "message", exception.getMessage()));
        }
        boolean wasTransfer = existing.isTransfer();
        Long counterpartBefore = existing.getCounterpartAccountId();

        revertFromBalance(existing);

        existing.setDebt(debt);
        // El rebut va amb el deute: qui envia el deute diu també quin rebut
        // paga, i null és el que toca. Sense deute, no en paga cap.
        if (changes.getDebt() != null || changes.getDebtReceipt() != null) {
            existing.setDebtReceipt(debt != null ? changes.getDebtReceipt() : null);
        }
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

        // Si canvia l'altre compte, la marca d'exclòs l'havia posada el traspàs
        // d'abans: es torna a decidir amb el nou, tret que en la mateixa edició
        // diguin què volen. Sense això, passar de Revolut a Trade Republic
        // deixava l'estalvi marcat com a no comptat.
        if (wasTransfer && counterpartSent && changes.getExcludedFromBudget() == null) {
            existing.setExcludedFromBudget(false);
        }
        internalTransfers.link(existing, existing.getAccount(), counterpartAfter);

        // Torna a lligar categoria i empresa —poden haver canviat de nom— i
        // aplica el saldo nou al compte que toqui ara, i al de l'altre costat.
        linkAndApplyToBalance(existing, existing.getAccount());
        transactionRepository.save(existing);

        // Només si ara és traspàs a un altre compte que abans: si ja ho era,
        // l'altra pota ja es va ajuntar o reconèixer, i un moviment igual que
        // hagi arribat després és un altre.
        Transaction joined = Objects.equals(counterpartBefore, existing.getCounterpartAccountId())
                ? null
                : absorbOtherLeg(existing);

        return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Moviment actualitzat" + joinedNote(joined)));
    }

    /**
     * Si el traspàs té l'altra pota desada com un moviment normal, la treu.
     *
     * És el mateix diner: el traspàs ja mou el saldo de l'altre compte, i
     * l'altra pota el mouria un segon cop i sortiria a l'anàlisi com un
     * ingrés o una despesa que no han existit. Es desfà el seu saldo i
     * s'esborra; tornar a importar aquell extracte la reconeixerà com l'altra
     * pota i no la tornarà a desar.
     *
     * @return el moviment tret, o null si no n'hi havia cap
     */
    private Transaction absorbOtherLeg(Transaction transfer) {
        if (!transfer.isTransfer()) return null;
        return internalTransfers.findUnmarkedOtherLeg(transfer)
                .map(otherLeg -> {
                    revertFromBalance(otherLeg);
                    transactionRepository.delete(otherLeg);
                    return otherLeg;
                })
                .orElse(null);
    }

    private static String joinedNote(Transaction joined) {
        if (joined == null) return "";
        return ". Era el mateix diner que el moviment de " + joined.getAccount().getName() + " del "
                + joined.getDate() + ": s'han ajuntat en un sol traspàs";
    }

    /**
     * Els traspassos que no estan marcats: una despesa en un compte i un
     * ingrés del mateix import en un altre, a menys de tres dies. Compten
     * tots dos a l'anàlisi, com si fossin una despesa i un ingrés de debò.
     */
    @GetMapping("/traspassos/sense-marcar")
    public List<Map<String, Object>> unmarkedTransfers() {
        return internalTransfers.unmarkedPairs().stream()
                .map(pair -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("sortida", describeLeg(pair.outgoing()));
                    item.put("entrada", describeLeg(pair.incoming()));
                    item.put("import", pair.outgoing().getAmount());
                    return item;
                })
                .toList();
    }

    private static Map<String, Object> describeLeg(Transaction leg) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", leg.getId());
        item.put("data", leg.getDate() != null ? leg.getDate().toString() : null);
        item.put("compte", leg.getAccount().getName());
        item.put("compte_id", leg.getAccount().getId());
        item.put("descripcio", firstNonBlank(leg.getShortDescription(), leg.getOriginalConcept(), leg.getEmpresa()));
        item.put("categoria", leg.getCategory() != null ? leg.getCategory().getName() : null);
        return item;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    public record TransferJoin(
            @JsonProperty("sortida_id") Long outgoingId,
            @JsonProperty("entrada_id") Long incomingId) {
    }

    /**
     * Ajunta les dues potes d'un traspàs desades com a moviments normals.
     *
     * Es queda la sortida, que passa a ser el traspàs (com si s'hagués marcat
     * en importar-la), i l'entrada s'esborra. Els saldos no canvien: l'entrada
     * desfà el seu i el traspàs posa el mateix a l'altre compte.
     *
     * Compta al pressupost com qualsevol traspàs: entre comptes del dia a dia
     * no, i cap a un compte d'estalvi sí, a la categoria de la sortida.
     */
    @PostMapping("/traspassos/ajuntar")
    @Transactional
    public ResponseEntity<?> joinTransfer(@RequestBody TransferJoin request) {
        if (request.outgoingId() == null || request.incomingId() == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error",
                    "message", "Falten els dos moviments del traspàs."));
        }
        Transaction outgoing = transactionRepository.findById(request.outgoingId())
                .orElseThrow(() -> new NotFoundException("el moviment", request.outgoingId()));
        Transaction incoming = transactionRepository.findById(request.incomingId())
                .orElseThrow(() -> new NotFoundException("el moviment", request.incomingId()));
        try {
            internalTransfers.requireJoinable(outgoing, incoming);
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error",
                    "message", exception.getMessage()));
        }

        revertFromBalance(incoming);
        transactionRepository.delete(incoming);

        internalTransfers.link(outgoing, outgoing.getAccount(), incoming.getAccount());
        internalTransfers.applyToCounterpart(outgoing);
        transactionRepository.save(outgoing);

        return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Traspàs de " + outgoing.getAccount().getName() + " a " + incoming.getAccount().getName()
                        + ": ja no compta com a despesa ni com a ingrés. Els saldos no canvien.",
                "id", outgoing.getId()));
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
        Transaction transaction = transactionRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("el moviment", id));

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
            Account defaultAccount = defaultAccount();

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
                transaction.setAccount(ownAccountOf(transaction, defaultAccount));
                transaction.setVerificationHash(transactionHasher.hash(transaction));
                // La pantalla de revisió no vincula deutes, però deute_id s'ha
                // de llegir igual per les tres portes d'entrada: sense resoldre'l,
                // un -1 arribaria a la base de dades com a clau forana.
                transaction.setDebt(debtService.resolveForLink(transaction.getDebt()));
                if (transaction.getDebt() == null) transaction.setDebtReceipt(null);
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

            // Les parts de totes les files es comproven abans de moure cap
            // saldo: si una no quadra, no s'ha d'importar res, i el missatge ha
            // de dir quina és.
            Map<Transaction, List<TransactionPart>> partsByTransaction = new IdentityHashMap<>();
            for (Transaction transaction : toPersist) {
                List<TransactionPart> parts;
                try {
                    internalTransfers.link(transaction, transaction.getAccount(),
                            internalTransfers.resolve(transaction.getCounterpartAccount(), transaction.getAccount()));
                    parts = splitBeforeSaving(transaction);
                } catch (IllegalArgumentException exception) {
                    throw new IllegalArgumentException(describe(transaction) + ": " + exception.getMessage(), exception);
                }
                if (!parts.isEmpty()) partsByTransaction.put(transaction, parts);
            }

            for (Transaction transaction : toPersist) {
                linkAndApplyToBalance(transaction, defaultAccount);
            }

            transactionRepository.saveAll(toPersist);
            partsByTransaction.forEach(transactionPartService::store);

            // Una regla pot marcar com a traspàs una línia l'altra pota de la
            // qual ja s'havia importat de l'altre extracte, com un moviment
            // normal: es treu, perquè el traspàs ja mou aquell saldo.
            long joined = toPersist.stream()
                    .filter(Transaction::isTransfer)
                    .map(this::absorbOtherLeg)
                    .filter(Objects::nonNull)
                    .count();

            String message = (skipped > 0
                    ? toPersist.size() + " moviments guardats (" + skipped + " ja existien)"
                    : toPersist.size() + " moviments guardats correctament")
                    + (joined > 0 ? ". " + joined + " traspassos s'han ajuntat amb l'altra pota, que ja hi era" : "");

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
            // Una validació és culpa de les dades (400); la resta, nostra (500).
            throw new ConfirmUploadException(
                    "No s'ha importat res. " + ClientErrors.messageFor(exception, "Confirmar importació"),
                    exception,
                    ClientErrors.isForTheUser(exception) ? HttpStatus.BAD_REQUEST : HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Les parts que porta un moviment que encara no s'ha desat, validades.
     *
     * Un moviment dividit agafa com a categoria la de la primera part: la
     * seva ja no compta, però si es treu la divisió és la que tornarà a valer,
     * i la que hagués proposat la IA podria ser un grup i fer fallar el desat.
     */
    private List<TransactionPart> splitBeforeSaving(Transaction transaction) {
        List<TransactionPart> parts = transactionPartService.validate(transaction, transaction.getParts());
        if (!parts.isEmpty()) {
            transaction.setCategoryName(parts.get(0).getCategory().getName());
            transaction.setDebt(null);
            transaction.setDebtReceipt(null);
        }
        return parts;
    }

    /** "Moviment del 2026-10-01, Trade Republic, 500.00 €": per dir quina fila falla. */
    private static String describe(Transaction transaction) {
        return "Moviment del " + transaction.getDate() + ", " + transaction.getEmpresa() + ", "
                + (transaction.getAmount() != null ? transaction.getAmount().toPlainString() : "?") + " €";
    }

    /** Un error en importar, amb el codi que toca: 400 si és de les dades, 500 si és nostre. */
    private static class ConfirmUploadException extends RuntimeException {
        private final HttpStatus status;

        ConfirmUploadException(String message, Throwable cause, HttpStatus status) {
            super(message, cause);
            this.status = status;
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
        return ResponseEntity.status(exception.status).body(Map.of(
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

            // D'un moviment dividit manen les parts: surt si alguna és de la
            // categoria, i no per la categoria que encara porta el moviment.
            if (categoryId != null) {
                specification = specification.and((root, query, criteriaBuilder) -> {
                    Subquery<Long> anyPart = query.subquery(Long.class);
                    Root<TransactionPart> part = anyPart.from(TransactionPart.class);
                    anyPart.select(part.get("id")).where(criteriaBuilder.equal(part.get("transaction"), root));

                    Subquery<Long> partInCategory = query.subquery(Long.class);
                    Root<TransactionPart> categoryPart = partInCategory.from(TransactionPart.class);
                    partInCategory.select(categoryPart.get("id")).where(
                            criteriaBuilder.equal(categoryPart.get("transaction"), root),
                            criteriaBuilder.equal(categoryPart.get("category").get("id"), categoryId));

                    return criteriaBuilder.or(
                            criteriaBuilder.and(
                                    criteriaBuilder.equal(root.get("category").get("id"), categoryId),
                                    criteriaBuilder.not(criteriaBuilder.exists(anyPart))),
                            criteriaBuilder.exists(partInCategory));
                });
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
            // Un traspàs és del compte d'on surt i també del de l'altre costat:
            // Trade Republic té els seus moviments encara que no se n'importi
            // l'extracte.
            if (accountId != null) {
                specification = specification.and((root, query, criteriaBuilder) -> criteriaBuilder.or(
                        criteriaBuilder.equal(root.get("account").get("id"), accountId),
                        // LEFT: la majoria de moviments no en tenen, i un join normal els deixaria fora.
                        criteriaBuilder.equal(root.join("counterpartAccount", JoinType.LEFT).get("id"), accountId)));
            }

            if (startDate != null) {
                specification = specification.and((root, query, criteriaBuilder) ->
                        criteriaBuilder.greaterThanOrEqualTo(root.get("date"), startDate));
            }

            if (endDate != null) {
                specification = specification.and((root, query, criteriaBuilder) ->
                        criteriaBuilder.lessThanOrEqualTo(root.get("date"), endDate));
            }

            return transactionPartService.withParts(
                    transactionRepository.findAll(specification, Sort.by(Sort.Direction.DESC, "date")));
        }
        return transactionPartService.withParts(transactionRepository.findAllByOrderByDateDesc());
    }

    /**
     * Divideix un moviment en parts, o treu la divisió amb una llista buida.
     *
     * No toca cap saldo: el moviment ja el va moure pel total, i les parts
     * només diuen com es reparteix. Per això no va pel camí de linkAndApplyToBalance.
     */
    @PutMapping("/gastos/{id}/parts")
    public ResponseEntity<?> replaceParts(@PathVariable Long id, @RequestBody List<TransactionPart> parts) {
        // Un moviment que no existeix o unes parts que no quadren arriben amb
        // el seu missatge pel gestor d'errors global.
        return ResponseEntity.ok(transactionPartService.replace(id, parts));
    }

    @GetMapping("/companies")
    public List<Company> getCompanies() {
        return companyRepository.findAll();
    }
}
