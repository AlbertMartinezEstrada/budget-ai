package com.budgetai.backend.service;

import com.budgetai.backend.model.Category;
import com.budgetai.backend.model.RecurringTransaction;
import com.budgetai.backend.repository.CategoryRepository;
import com.budgetai.backend.repository.RecurringTransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Els costos fixos: la plantilla de la qual surt cada mes.
 *
 * No és una taula a part: són els recurrents de despesa de les fulles fixes.
 * El pressupost d'un mes ja pren el prorrateig d'aquests recurrents quan la
 * fulla no té import propi, així que definir-los aquí és definir-los per a
 * tots els mesos. Un import posat a mà en un mes només val per a aquell mes.
 *
 * Tots els canvis valen des d'un mes concret cap endavant. Si es modifiqués
 * la fila, els mesos passats canviarien de xifra: per això un canvi tanca la
 * versió vella el mes anterior i n'obre una de nova, i treure'n un el tanca
 * en comptes d'esborrar-lo.
 */
@Service
public class FixedCostService {

    private static final Set<String> FREQUENCIES = Set.of("DIARIA", "SETMANAL", "MENSUAL", "TRIMESTRAL", "ANUAL");

    private final RecurringTransactionRepository recurringRepository;
    private final CategoryRepository categoryRepository;
    private final CategoryHierarchyService hierarchyService;

    public FixedCostService(RecurringTransactionRepository recurringRepository,
                            CategoryRepository categoryRepository,
                            CategoryHierarchyService hierarchyService) {
        this.recurringRepository = recurringRepository;
        this.categoryRepository = categoryRepository;
        this.hierarchyService = hierarchyService;
    }

    /** Els costos fixos que compten en aquest mes. */
    public List<RecurringTransaction> listFor(YearMonth month) {
        return recurringRepository.findByActiveTrue().stream()
                .filter(this::isFixedCost)
                .filter(recurring -> recurring.isValidDuring(month.atDay(1), month.atEndOfMonth()))
                .sorted(Comparator.comparing((RecurringTransaction recurring) -> recurring.getCategory().getName())
                        .thenComparing(RecurringTransaction::getName))
                .toList();
    }

    /** Un cost fix nou, que compta a partir d'aquest mes. */
    @Transactional
    public RecurringTransaction create(YearMonth from, RecurringTransaction request) {
        RecurringTransaction fixedCost = new RecurringTransaction();
        fixedCost.setType("EXPENSE");
        fixedCost.setActive(true);
        fixedCost.setValidFrom(from.atDay(1));
        fixedCost.setNextDate(request.getNextDate() != null ? request.getNextDate() : from.atDay(1));
        fixedCost.setAccount(request.getAccount());
        fixedCost.setCompany(request.getCompany());
        fixedCost.setDescription(request.getDescription());
        applyEditableFields(fixedCost, request);
        requireComplete(fixedCost);
        return recurringRepository.save(fixedCost);
    }

    /**
     * Canvia un cost fix a partir d'aquest mes.
     *
     * Si ja comptava abans, la versió vella es tanca el mes anterior i se'n
     * crea una de nova: així els mesos passats continuen dient el que deien.
     * Si començava aquest mateix mes, no ha comptat mai abans i es pot
     * modificar directament.
     */
    @Transactional
    public RecurringTransaction update(Long id, YearMonth from, RecurringTransaction request) {
        RecurringTransaction current = findFixedCost(id);
        if (!startedBefore(current, from)) {
            applyEditableFields(current, request);
            requireComplete(current);
            return recurringRepository.save(current);
        }

        RecurringTransaction next = new RecurringTransaction();
        next.setType(current.getType());
        next.setActive(true);
        next.setAccount(current.getAccount());
        next.setCompany(current.getCompany());
        next.setDescription(current.getDescription());
        next.setName(current.getName());
        next.setCategory(current.getCategory());
        next.setAmount(current.getAmount());
        next.setFrequency(current.getFrequency());
        next.setValidFrom(from.atDay(1));
        next.setValidUntil(current.getValidUntil());
        // El calendari de càrrecs continua on era, però la versió nova només
        // genera els que cauen dins la seva vigència; els d'abans ja són de la
        // vella.
        next.setNextDate(firstChargeFrom(current.getNextDate(), current.getFrequency(), from.atDay(1)));
        applyEditableFields(next, request);
        requireComplete(next);

        current.setValidUntil(from.minusMonths(1).atEndOfMonth());
        recurringRepository.save(current);
        return recurringRepository.save(next);
    }

    /**
     * Treu un cost fix a partir d'aquest mes.
     *
     * Els mesos anteriors el continuen tenint: es tanca, no s'esborra. Només
     * s'esborra si començava aquest mateix mes, perquè llavors no ha comptat mai.
     */
    @Transactional
    public void remove(Long id, YearMonth from) {
        RecurringTransaction current = findFixedCost(id);
        if (!startedBefore(current, from)) {
            recurringRepository.delete(current);
            return;
        }
        current.setValidUntil(from.minusMonths(1).atEndOfMonth());
        recurringRepository.save(current);
    }

    private boolean isFixedCost(RecurringTransaction recurring) {
        Category category = recurring.getCategory();
        return "EXPENSE".equals(recurring.getType())
                && category != null
                && category.isFixed()
                && !hierarchyService.isGroup(category.getId());
    }

    private RecurringTransaction findFixedCost(Long id) {
        RecurringTransaction recurring = recurringRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("No existeix el cost fix " + id));
        if (!isFixedCost(recurring)) {
            throw new IllegalArgumentException("El recurrent " + id + " no és un cost fix");
        }
        return recurring;
    }

    private static boolean startedBefore(RecurringTransaction recurring, YearMonth month) {
        return recurring.getValidFrom() == null || recurring.getValidFrom().isBefore(month.atDay(1));
    }

    /** Nom, import, freqüència i categoria: el que es pot canviar des del menú. */
    private void applyEditableFields(RecurringTransaction target, RecurringTransaction request) {
        if (request.getName() != null && !request.getName().isBlank()) target.setName(request.getName().trim());
        if (request.getAmount() != null) target.setAmount(request.getAmount());
        if (request.getFrequency() != null) target.setFrequency(request.getFrequency().toUpperCase());
        if (request.getCategory() != null && request.getCategory().getId() != null) {
            Category category = categoryRepository.findById(request.getCategory().getId())
                    .orElseThrow(() -> new IllegalArgumentException("No existeix la categoria"));
            // Un grup no rep moviments i una fulla variable es mesura pel que
            // s'hi gasta: cap dels dos pot tenir un cost fix.
            if (!category.isFixed() || hierarchyService.isGroup(category.getId())) {
                throw new IllegalArgumentException("Un cost fix ha d'anar a una subcategoria fixa");
            }
            target.setCategory(category);
        }
    }

    private static void requireComplete(RecurringTransaction fixedCost) {
        if (fixedCost.getName() == null || fixedCost.getName().isBlank()) {
            throw new IllegalArgumentException("Falta el nom");
        }
        if (fixedCost.getCategory() == null) {
            throw new IllegalArgumentException("Falta la categoria");
        }
        if (fixedCost.getAmount() == null || fixedCost.getAmount().signum() < 0) {
            throw new IllegalArgumentException("L'import ha de ser positiu");
        }
        if (fixedCost.getFrequency() == null || !FREQUENCIES.contains(fixedCost.getFrequency())) {
            throw new IllegalArgumentException("Freqüència desconeguda");
        }
    }

    /** El primer càrrec del calendari que cau a partir d'una data. */
    private static LocalDate firstChargeFrom(LocalDate nextCharge, String frequency, LocalDate from) {
        if (nextCharge == null) return from;
        LocalDate charge = nextCharge;
        while (charge.isBefore(from)) {
            charge = switch (frequency) {
                case "DIARIA" -> charge.plusDays(1);
                case "SETMANAL" -> charge.plusWeeks(1);
                case "TRIMESTRAL" -> charge.plusMonths(3);
                case "ANUAL" -> charge.plusYears(1);
                default -> charge.plusMonths(1);
            };
        }
        return charge;
    }

    /** L'import que el menú ensenya com a "al mes", per no fer-ho al frontend. */
    public static BigDecimal monthlyTotal(List<RecurringTransaction> fixedCosts) {
        return fixedCosts.stream()
                .map(RecurringTransaction::getMonthlyAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
