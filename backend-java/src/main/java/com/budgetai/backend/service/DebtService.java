package com.budgetai.backend.service;

import com.budgetai.backend.model.Category;
import com.budgetai.backend.model.Debt;
import com.budgetai.backend.model.Debt.Installment;
import com.budgetai.backend.model.Transaction;
import com.budgetai.backend.repository.CategoryRepository;
import com.budgetai.backend.repository.DebtRepository;
import com.budgetai.backend.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deutes i préstecs: qui deu què, com s'ha de tornar i com es va.
 *
 * El que s'ha retornat surt dels moviments vinculats al deute, no d'un camp
 * que s'actualitzi a mà: així no hi ha dues xifres que puguin deixar de
 * quadrar. Una devolució d'un deute que dec és una sortida (EXPENSE); d'un que
 * em deuen, una entrada (INCOME). El moviment en sentit contrari és l'origen
 * del préstec i no descompta res.
 */
@Service
public class DebtService {

    private static final Set<String> DIRECTIONS = Set.of(Debt.I_OWE, Debt.OWED_TO_ME);
    private static final Set<String> PLANS = Set.of(Debt.PLAN_FREE, Debt.PLAN_SINGLE, Debt.PLAN_INSTALLMENTS);

    private final DebtRepository debtRepository;
    private final TransactionRepository transactionRepository;
    private final CategoryRepository categoryRepository;
    private final CategoryHierarchyService hierarchyService;

    public DebtService(DebtRepository debtRepository,
                       TransactionRepository transactionRepository,
                       CategoryRepository categoryRepository,
                       CategoryHierarchyService hierarchyService) {
        this.debtRepository = debtRepository;
        this.transactionRepository = transactionRepository;
        this.categoryRepository = categoryRepository;
        this.hierarchyService = hierarchyService;
    }

    /** Tots, amb els oberts primer: els saldats ja no demanen res. */
    @Transactional(readOnly = true)
    public List<Debt> list() {
        LocalDate today = LocalDate.now();
        List<Debt> debts = new ArrayList<>(debtRepository.findAllByOrderByDateDesc());
        debts.forEach(debt -> describe(debt, today));
        debts.sort(Comparator.comparing(Debt::isSettled));
        return debts;
    }

    @Transactional(readOnly = true)
    public Debt get(Long id) {
        return describe(find(id), LocalDate.now());
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
        return describe(debtRepository.save(debt), LocalDate.now());
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
        return describe(debtRepository.save(debt), LocalDate.now());
    }

    /**
     * Esborra el deute i deixa els moviments sense vincle.
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
     * Quotes dels deutes que dec que cauen dins del període, per categoria.
     *
     * És el que el pressupost ha de reservar aquell mes: uns diners que ja
     * estan compromesos no es poden repartir com si fossin lliures.
     *
     * Cada deute reserva com a molt el que li quedava per tornar quan va
     * començar el període. Si s'ha avançat feina i ja està saldat, deixa de
     * reservar, encara que el calendari original digués que quedaven quotes.
     */
    @Transactional(readOnly = true)
    public Map<Long, BigDecimal> installmentsDueByCategory(LocalDate from, LocalDate to) {
        Map<Long, List<Transaction>> movementsByDebt = new HashMap<>();
        for (Transaction transaction : transactionRepository.findByDebtIsNotNull()) {
            movementsByDebt.computeIfAbsent(transaction.getDebt().getId(), missingDebtId -> new ArrayList<>())
                    .add(transaction);
        }

        Map<Long, BigDecimal> reserved = new HashMap<>();
        for (Debt debt : debtRepository.findAll()) {
            if (!debt.isOwedByMe() || debt.getCategory() == null) continue;

            BigDecimal due = RepaymentSchedule.dueBetween(RepaymentSchedule.of(debt), from, to);
            if (due.signum() == 0) continue;

            BigDecimal repaidBefore = repaid(debt, movementsByDebt.getOrDefault(debt.getId(), List.of()), from);
            BigDecimal pendingAtStart = debt.getAmount().subtract(repaidBefore).max(BigDecimal.ZERO);
            BigDecimal reservation = due.min(pendingAtStart);
            if (reservation.signum() > 0) {
                reserved.merge(debt.getCategory().getId(), reservation, BigDecimal::add);
            }
        }
        return reserved;
    }

    /** Omple el que es calcula: retornat, calendari, endarrerit, pròxim pagament i moviments. */
    Debt describe(Debt debt, LocalDate today) {
        List<Transaction> movements = debt.getId() != null
                ? transactionRepository.findByDebtOrderedByDate(debt.getId())
                : List.of();
        BigDecimal repaid = repaid(debt, movements, null);
        List<Installment> payments = RepaymentSchedule.of(debt);

        debt.setMovements(movements);
        debt.setRepaid(repaid);
        debt.setSchedule(RepaymentSchedule.withStatus(payments, repaid, today));
        debt.setOverdue(RepaymentSchedule.overdue(payments, repaid, today));
        debt.setNextPayment(RepaymentSchedule.next(payments, repaid));
        return debt;
    }

    /**
     * El que s'ha retornat, comptant només els moviments en sentit de retorn.
     *
     * @param before si no és null, només els d'abans d'aquest dia
     */
    static BigDecimal repaid(Debt debt, List<Transaction> movements, LocalDate before) {
        String repaymentType = debt.isOwedByMe() ? "EXPENSE" : "INCOME";
        return movements.stream()
                .filter(movement -> repaymentType.equals(movement.getType()))
                .filter(movement -> before == null
                        || (movement.getDate() != null && movement.getDate().isBefore(before)))
                .map(movement -> movement.getAmount() != null ? movement.getAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private Debt find(Long id) {
        return debtRepository.findById(id)
                .orElseThrow(() -> new DebtNotFoundException(id));
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

    /** No és un error de l'usuari: el deute no hi és. El controlador en fa un 404. */
    public static class DebtNotFoundException extends RuntimeException {
        public DebtNotFoundException(Long id) {
            super("No existeix el deute " + id);
        }
    }
}
