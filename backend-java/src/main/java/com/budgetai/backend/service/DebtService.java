package com.budgetai.backend.service;

import com.budgetai.backend.model.Category;
import com.budgetai.backend.model.Debt;
import com.budgetai.backend.model.Debt.Installment;
import com.budgetai.backend.model.DebtRemovedReceipt;
import com.budgetai.backend.model.Transaction;
import com.budgetai.backend.model.TransactionPart;
import com.budgetai.backend.repository.CategoryRepository;
import com.budgetai.backend.repository.DebtRemovedReceiptRepository;
import com.budgetai.backend.repository.DebtRepository;
import com.budgetai.backend.repository.TransactionPartRepository;
import com.budgetai.backend.repository.TransactionRepository;
import com.budgetai.backend.service.RepaymentSchedule.Payment;
import com.budgetai.backend.service.RepaymentSchedule.Plan;
import com.budgetai.backend.service.RepaymentSchedule.Removal;
import com.budgetai.backend.service.TransactionLines.Line;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Deutes i préstecs: qui deu què, com s'ha de tornar i com es va.
 *
 * El que s'ha retornat surt dels moviments vinculats al deute, no d'un camp
 * que s'actualitzi a mà: així no hi ha dues xifres que puguin deixar de
 * quadrar. Una devolució d'un deute que dec és una sortida (EXPENSE); d'un que
 * em deuen, una entrada (INCOME). El moviment en sentit contrari és l'origen
 * del préstec i no descompta res.
 *
 * Es compta per línies (TransactionLines), no per moviments: una part d'un
 * moviment dividit també pot ser una devolució, i només per l'import de la
 * part.
 *
 * Com es reparteix el retornat entre els rebuts ho decideix RepaymentSchedule:
 * cada pagament paga el rebut triat o el que toca, i el que sobra escurça el
 * final.
 */
@Service
public class DebtService {

    private static final Set<String> DIRECTIONS = Set.of(Debt.I_OWE, Debt.OWED_TO_ME);
    private static final Set<String> PLANS = Set.of(Debt.PLAN_FREE, Debt.PLAN_SINGLE, Debt.PLAN_INSTALLMENTS);

    private final DebtRepository debtRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionPartRepository partRepository;
    private final TransactionLines transactionLines;
    private final CategoryRepository categoryRepository;
    private final CategoryHierarchyService hierarchyService;
    private final DebtRemovedReceiptRepository removedReceiptRepository;

    public DebtService(DebtRepository debtRepository,
                       TransactionRepository transactionRepository,
                       TransactionPartRepository partRepository,
                       TransactionLines transactionLines,
                       CategoryRepository categoryRepository,
                       CategoryHierarchyService hierarchyService,
                       DebtRemovedReceiptRepository removedReceiptRepository) {
        this.debtRepository = debtRepository;
        this.transactionRepository = transactionRepository;
        this.partRepository = partRepository;
        this.transactionLines = transactionLines;
        this.categoryRepository = categoryRepository;
        this.hierarchyService = hierarchyService;
        this.removedReceiptRepository = removedReceiptRepository;
    }

    /** Tots, amb els oberts primer: els saldats ja no demanen res. */
    @Transactional(readOnly = true)
    public List<Debt> list() {
        LocalDate today = LocalDate.now();
        List<Line> lines = transactionLines.all();
        Map<Long, List<Removal>> removals = removalsByDebt();
        List<Debt> debts = new ArrayList<>(debtRepository.findAllByOrderByDateDesc());
        debts.forEach(debt -> describe(debt, today, lines, removals.getOrDefault(debt.getId(), List.of())));
        debts.sort(Comparator.comparing(Debt::isSettled));
        return debts;
    }

    @Transactional(readOnly = true)
    public Debt get(Long id) {
        return describe(find(id), LocalDate.now(), transactionLines.all());
    }

    @Transactional
    public Debt create(Debt request) {
        Debt debt = new Debt();
        debt.setName(request.getName());
        debt.setDirection(request.getDirection());
        debt.setAmount(request.getAmount());
        debt.setDate(request.getDate());
        debt.setRepaymentPlan(request.getRepaymentPlan() != null ? request.getRepaymentPlan() : Debt.PLAN_FREE);
        debt.setInstallment(request.getInstallment());
        debt.setFrequency(request.getFrequency());
        debt.setFirstPaymentDate(request.getFirstPaymentDate());
        debt.setNotes(request.getNotes());
        debt.setCategory(resolveCategory(request.getCategory()));

        validateAndNormalize(debt);
        return describe(debtRepository.save(debt), LocalDate.now(), transactionLines.all());
    }

    /**
     * Actualització parcial: un camp absent no es toca.
     *
     * Canviar la forma de retorn sí que buida els camps que la nova ja no fa
     * servir: passar de quotes a "lliure" amb la quota encara desada deixaria
     * un calendari fantasma.
     */
    @Transactional
    public Debt update(Long id, Debt changes) {
        Debt debt = find(id);
        if (changes.getName() != null) debt.setName(changes.getName());
        if (changes.getDirection() != null) debt.setDirection(changes.getDirection());
        if (changes.getAmount() != null) debt.setAmount(changes.getAmount());
        if (changes.getDate() != null) debt.setDate(changes.getDate());
        if (changes.getRepaymentPlan() != null) debt.setRepaymentPlan(changes.getRepaymentPlan());
        if (changes.getInstallment() != null) debt.setInstallment(changes.getInstallment());
        if (changes.getFrequency() != null) debt.setFrequency(changes.getFrequency());
        if (changes.getFirstPaymentDate() != null) debt.setFirstPaymentDate(changes.getFirstPaymentDate());
        if (changes.getNotes() != null) debt.setNotes(changes.getNotes());
        if (changes.getCategory() != null) debt.setCategory(resolveCategory(changes.getCategory()));

        validateAndNormalize(debt);
        return describe(debtRepository.save(debt), LocalDate.now(), transactionLines.all());
    }

    /**
     * Esborra el deute i deixa els moviments i les parts sense vincle.
     *
     * Els moviments no s'esborren: van passar de debò i el saldo del compte en
     * depèn. Només deixen de dir de quin deute són.
     */
    @Transactional
    public void delete(Long id) {
        Debt debt = find(id);
        for (Transaction transaction : transactionRepository.findByDebtOrderedByDate(id)) {
            transaction.setDebt(null);
            transactionRepository.save(transaction);
        }
        for (TransactionPart part : partRepository.findByDebt(id)) {
            part.setDebt(null);
            partRepository.save(part);
        }
        debtRepository.delete(debt);
    }

    /**
     * El deute al qual s'ha de vincular un moviment, a partir del que ha
     * arribat a la petició.
     *
     * Sense @Transactional propi: el crida l'alta o l'edició d'un moviment, que
     * ja en porten una d'escriptura i hi ha de participar tal qual.
     *
     * @return null si cal desvincular-lo (identificador negatiu)
     */
    public Debt resolveForLink(Debt reference) {
        if (reference == null || reference.getId() == null || reference.getId() < 0) return null;
        return debtRepository.findById(reference.getId())
                .orElseThrow(() -> new IllegalArgumentException("El deute triat ja no existeix."));
    }

    /**
     * Treu un rebut del calendari d'un deute a quotes.
     *
     * @param date     el dia del rebut, o qualsevol dia del seu període
     * @param discount true per restar-ne l'import del deute (una rebaixa);
     *                 false per saltar-lo, i el que es deu passa al final
     */
    @Transactional
    public Debt removeReceipt(Long id, LocalDate date, boolean discount) {
        Debt debt = find(id);
        if (!Debt.PLAN_INSTALLMENTS.equals(debt.getRepaymentPlan())) {
            throw new IllegalArgumentException("Només es poden treure rebuts d'un deute a quotes.");
        }
        if (date == null) {
            throw new IllegalArgumentException("Falta quin rebut.");
        }

        Installment receipt = describe(debt, LocalDate.now(), transactionLines.all()).getSchedule().stream()
                .filter(RepaymentSchedule::isActive)
                .filter(candidate -> RepaymentSchedule.samePeriod(candidate.date(), date, debt.getFrequency()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Aquest rebut no és al calendari."));
        // Un pagament que l'ha triat a ell no té on anar: el que toca podria
        // ser un altre mes que ja està pagat. Que el canviïn ells, sabent-ho.
        if (!receipt.removable()) {
            throw new IllegalArgumentException("Hi ha pagaments que paguen precisament aquest rebut. "
                    + "Des de Transaccions, canvia'ls de rebut abans de treure'l.");
        }

        DebtRemovedReceipt removal = new DebtRemovedReceipt();
        removal.setDebt(debt);
        removal.setDate(receipt.date());
        removal.setDiscount(discount ? receipt.amount() : BigDecimal.ZERO);
        removedReceiptRepository.save(removal);
        return describe(debt, LocalDate.now(), transactionLines.all());
    }

    /** Torna al calendari un rebut tret, saltat o descomptat. */
    @Transactional
    public Debt restoreReceipt(Long id, LocalDate date) {
        Debt debt = find(id);
        List<DebtRemovedReceipt> matching = removedReceiptRepository.findByDebt(id).stream()
                .filter(removal -> removal.getDate().equals(date))
                .toList();
        if (matching.isEmpty()) {
            throw new IllegalArgumentException("Aquest rebut no està tret del calendari.");
        }
        removedReceiptRepository.deleteAll(matching);
        return describe(debt, LocalDate.now(), transactionLines.all());
    }

    /**
     * Quotes dels deutes que dec que cauen dins del període, per categoria.
     *
     * És el que el pressupost ha de reservar aquell mes: uns diners que ja
     * estan compromesos no es poden repartir com si fossin lliures.
     *
     * Es reserva el que val el rebut d'aquell mes segons el calendari de debò,
     * el que ja té en compte el que s'ha pagat: si s'ha avançat feina, l'últim
     * rebut és més petit o el deute ja està saldat i no en queda cap, i un mes
     * saltat no reserva res.
     */
    @Transactional(readOnly = true)
    public Map<Long, BigDecimal> installmentsDueByCategory(LocalDate from, LocalDate to) {
        Map<Long, List<Line>> linesByDebt = new HashMap<>();
        for (Line line : transactionLines.all()) {
            if (line.debt() == null) continue;
            linesByDebt.computeIfAbsent(line.debt().getId(), missingDebtId -> new ArrayList<>()).add(line);
        }
        Map<Long, List<Removal>> removals = removalsByDebt();

        Map<Long, BigDecimal> reserved = new HashMap<>();
        for (Debt debt : debtRepository.findAll()) {
            if (!debt.isOwedByMe() || debt.getCategory() == null) continue;

            List<Line> lines = linesByDebt.getOrDefault(debt.getId(), List.of());
            Plan plan = RepaymentSchedule.plan(debt, removals.getOrDefault(debt.getId(), List.of()),
                    payments(debt, lines), to);
            BigDecimal reservation = plan.dueBetween(from, to);
            if (reservation.signum() > 0) {
                reserved.merge(debt.getCategory().getId(), reservation, BigDecimal::add);
            }
        }
        return reserved;
    }

    /**
     * Omple el que es calcula: retornat, calendari, endarrerit, pròxim pagament i moviments.
     *
     * @param allLines les línies de tots els moviments; se'n queda les del deute
     */
    Debt describe(Debt debt, LocalDate today, List<Line> allLines) {
        List<Removal> removals = debt.getId() != null
                ? removedReceiptRepository.findByDebt(debt.getId()).stream().map(DebtService::toRemoval).toList()
                : List.of();
        return describe(debt, today, allLines, removals);
    }

    private Debt describe(Debt debt, LocalDate today, List<Line> allLines, List<Removal> removals) {
        List<Line> lines = allLines.stream()
                .filter(line -> line.belongsTo(debt))
                .sorted(Comparator.comparing(Line::date, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        List<Line> repayments = lines.stream().filter(line -> isRepayment(debt, line)).toList();
        Plan plan = RepaymentSchedule.plan(debt, removals, payments(debt, lines), today);

        // El rebut de cada moviment, per ensenyar-lo al costat: els pagaments
        // del pla van en el mateix ordre que les devolucions.
        // Per identitat: dues línies iguals (dos pagaments de 50 el mateix dia)
        // són dos pagaments.
        Map<Line, Integer> paymentIndex = new IdentityHashMap<>();
        for (int index = 0; index < repayments.size(); index++) paymentIndex.put(repayments.get(index), index);

        debt.setMovements(lines.stream()
                .map(line -> {
                    Integer index = paymentIndex.get(line);
                    return new Debt.Movement(line.transaction().getId(), line.date(), line.type(),
                            line.amount(), line.transaction().getEmpresa(), line.description(), line.isPart(),
                            index != null ? plan.receiptOfPayment().get(index) : null,
                            index != null && plan.chosen().get(index));
                })
                .toList());
        debt.setRepaid(repaid(debt, lines, null));
        debt.setDiscounted(plan.discounted());
        debt.setSchedule(plan.receipts());
        debt.setOverdue(plan.overdue());
        debt.setNextPayment(plan.next());
        return debt;
    }

    /** Les devolucions com a pagaments del pla, en el mateix ordre que les línies. */
    private static List<Payment> payments(Debt debt, List<Line> lines) {
        return lines.stream()
                .filter(line -> isRepayment(debt, line))
                .map(line -> new Payment(line.date(), line.amount(), line.debtReceipt()))
                .toList();
    }

    /** Si la línia torna el deute: una sortida si el dec, una entrada si me'l deuen. */
    private static boolean isRepayment(Debt debt, Line line) {
        return (debt.isOwedByMe() ? "EXPENSE" : "INCOME").equals(line.type());
    }

    /**
     * El que s'ha retornat, comptant només les línies en sentit de retorn.
     *
     * @param lines les del deute
     * @param before si no és null, només les d'abans d'aquest dia
     */
    static BigDecimal repaid(Debt debt, List<Line> lines, LocalDate before) {
        return lines.stream()
                .filter(line -> isRepayment(debt, line))
                .filter(line -> before == null || (line.date() != null && line.date().isBefore(before)))
                .map(Line::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private Map<Long, List<Removal>> removalsByDebt() {
        return removedReceiptRepository.findAll().stream()
                .filter(removal -> removal.getDebt() != null)
                .sorted(Comparator.comparing(DebtRemovedReceipt::getDate))
                .collect(Collectors.groupingBy(removal -> removal.getDebt().getId(),
                        Collectors.mapping(DebtService::toRemoval, Collectors.toList())));
    }

    private static Removal toRemoval(DebtRemovedReceipt removal) {
        return new Removal(removal.getDate(), removal.getDiscount() != null ? removal.getDiscount() : BigDecimal.ZERO);
    }

    private Debt find(Long id) {
        return debtRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("el deute", id));
    }

    /**
     * La categoria on es reserva la quota. Un identificador negatiu la treu:
     * en una actualització parcial, null vol dir "no me l'han enviat".
     *
     * Ha de ser una fulla, com qualsevol lloc on van moviments: una reserva
     * penjada d'un grup es tornaria a sumar pels seus fills.
     */
    private Category resolveCategory(Category reference) {
        if (reference == null || reference.getId() == null || reference.getId() < 0) return null;
        Category category = categoryRepository.findById(reference.getId())
                .orElseThrow(() -> new IllegalArgumentException("La categoria triada ja no existeix."));
        if (hierarchyService.isGroup(category.getId())) {
            throw new IllegalArgumentException("La categoria \"" + category.getName()
                    + "\" és un grup: tria'n una de concreta.");
        }
        return category;
    }

    private static void validateAndNormalize(Debt debt) {
        if (debt.getName() == null || debt.getName().isBlank()) {
            throw new IllegalArgumentException("Posa-li un nom: a qui o per a què.");
        }
        debt.setName(debt.getName().trim());
        if (debt.getDirection() == null || !DIRECTIONS.contains(debt.getDirection())) {
            throw new IllegalArgumentException("Digues si el deus tu o te'l deuen.");
        }
        if (debt.getAmount() == null || debt.getAmount().signum() <= 0) {
            throw new IllegalArgumentException("L'import ha de ser més gran que zero.");
        }
        if (debt.getDate() == null) {
            throw new IllegalArgumentException("Falta la data del préstec.");
        }
        if (debt.getRepaymentPlan() == null || !PLANS.contains(debt.getRepaymentPlan())) {
            throw new IllegalArgumentException("La forma de retorn ha de ser lliure, tot de cop o a quotes.");
        }

        switch (debt.getRepaymentPlan()) {
            case Debt.PLAN_FREE -> {
                debt.setInstallment(null);
                debt.setFrequency(null);
                debt.setFirstPaymentDate(null);
            }
            case Debt.PLAN_SINGLE -> {
                debt.setInstallment(null);
                debt.setFrequency(null);
                requireFirstPaymentAfterLoan(debt, "Falta el dia que es torna.");
            }
            default -> {
                if (debt.getInstallment() == null || debt.getInstallment().signum() <= 0) {
                    throw new IllegalArgumentException("La quota ha de ser més gran que zero.");
                }
                if (debt.getFrequency() == null) debt.setFrequency(RepaymentSchedule.MONTHLY);
                if (!RepaymentSchedule.FREQUENCIES.contains(debt.getFrequency())) {
                    throw new IllegalArgumentException("La freqüència ha de ser setmanal, mensual o trimestral.");
                }
                requireFirstPaymentAfterLoan(debt, "Falta el dia de la primera quota.");
                RepaymentSchedule.requireReasonableCount(debt.getAmount(), debt.getInstallment());
            }
        }
    }

    private static void requireFirstPaymentAfterLoan(Debt debt, String missingMessage) {
        if (debt.getFirstPaymentDate() == null) {
            throw new IllegalArgumentException(missingMessage);
        }
        if (debt.getFirstPaymentDate().isBefore(debt.getDate())) {
            throw new IllegalArgumentException("No es pot començar a tornar abans del préstec.");
        }
    }
}
