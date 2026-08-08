package com.budgetai.backend.service;

import com.budgetai.backend.model.Budget;
import com.budgetai.backend.model.Category;
import com.budgetai.backend.model.RecurringTransaction;
import com.budgetai.backend.model.Transaction;
import com.budgetai.backend.repository.BudgetRepository;
import com.budgetai.backend.repository.RecurringTransactionRepository;
import com.budgetai.backend.repository.TransactionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

@Service
public class BudgetService {

    @Autowired
    private BudgetRepository budgetRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private RecurringTransactionRepository recurringTransactionRepository;

    @Autowired
    private CategoryHierarchyService hierarchyService;

    @Autowired
    private IncomeBaseService incomeBaseService;

    // El gasto acumulat es calcula sempre. Abans només s'omplia a
    // getActiveBudgetsForDate, de manera que el llistat general enviava
    // "gasto_actual" a null i la barra de progrés sortia sempre al 0%.
    public List<Budget> getAllBudgets() {
        return withCurrentSpent(budgetRepository.findAll());
    }

    public List<Budget> getActiveBudgets() {
        return withCurrentSpent(budgetRepository.findByActiveTrue());
    }

    private List<Budget> withCurrentSpent(List<Budget> budgets) {
        for (Budget budget : budgets) {
            budget.setCurrentSpent(calculateCurrentSpent(budget));
        }
        return budgets;
    }

    public List<Budget> getActiveBudgetsForDate(LocalDate date) {
        List<Budget> budgets = budgetRepository.findActiveBudgetsForDate(date);

        // Calcular el gasto actual para cada presupuesto
        for (Budget budget : budgets) {
            budget.setCurrentSpent(calculateCurrentSpent(budget));
        }

        return budgets;
    }

    public Optional<Budget> getBudgetById(Long id) {
        return budgetRepository.findById(id);
    }

    @Transactional
    public Budget createBudget(Budget budget) {
        return budgetRepository.save(budget);
    }

    @Transactional
    public Budget updateBudget(Long id, Budget updatedBudget) {
        return budgetRepository.findById(id)
                // Actualització parcial: un camp absent no ha de esborrar el valor desat.
                .map(budget -> {
                    if (updatedBudget.getCategory() != null) budget.setCategory(updatedBudget.getCategory());
                    if (updatedBudget.getLimitAmount() != null) budget.setLimitAmount(updatedBudget.getLimitAmount());
                    // Un percentatge negatiu vol dir "treu-me'l": és el conveni per
                    // distingir-ho de "no l'he enviat" en una actualització parcial.
                    if (updatedBudget.getPercentage() != null) {
                        budget.setPercentage(updatedBudget.getPercentage().signum() < 0
                                ? null : updatedBudget.getPercentage());
                    }
                    if (updatedBudget.getPeriodStart() != null) budget.setPeriodStart(updatedBudget.getPeriodStart());
                    if (updatedBudget.getPeriodEnd() != null) budget.setPeriodEnd(updatedBudget.getPeriodEnd());
                    if (updatedBudget.getActive() != null) budget.setActive(updatedBudget.getActive());
                    return budgetRepository.save(budget);
                })
                .orElseThrow(() -> new RuntimeException("Budget not found with id: " + id));
    }

    @Transactional
    public void deleteBudget(Long id) {
        budgetRepository.deleteById(id);
    }

    /**
     * Gasto acumulat del pressupost dins del seu període.
     *
     * Si el pressupost apunta a un grup, se sumen totes les fulles que en
     * pengen: el límit d'un grup és el sostre del conjunt. Si apunta a una
     * fulla, es comporta com abans, perquè leavesOf() d'una fulla retorna la
     * mateixa fulla.
     */
    private BigDecimal calculateCurrentSpent(Budget budget) {
        if (budget.getCategory() == null) return BigDecimal.ZERO;

        Set<Long> leafIds = hierarchyService.loadTree().leafIdsOf(budget.getCategory().getId());
        return spentIn(leafIds, budget.getPeriodStart(), budget.getPeriodEnd());
    }

    /** Suma dels moviments de despesa d'unes categories dins d'un rang de dates. */
    private BigDecimal spentIn(Set<Long> categoryIds, LocalDate from, LocalDate to) {
        if (categoryIds.isEmpty()) return BigDecimal.ZERO;

        return transactionRepository.findAll().stream()
                .filter(t -> "EXPENSE".equals(t.getType()))
                .filter(t -> t.getCategory() != null && categoryIds.contains(t.getCategory().getId()))
                .filter(t -> t.getDate() != null
                        && !t.getDate().isBefore(from) && !t.getDate().isAfter(to))
                .map(t -> t.getAmount() != null ? t.getAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ============ RESUM MENSUAL: COST DE VIDA I CAIXA ============
    //
    // Són dues preguntes diferents sobre el mateix mes:
    //
    //   COST DE VIDA  "quant em costa viure?"  Els fixos hi entren
    //                 prorratejats, tant si el càrrec cau aquest mes com si
    //                 no. Una assegurança de 600 € l'any hi suma 50 € cada
    //                 mes, sempre.
    //
    //   CAIXA         "quants diners han sortit del compte?"  Els moviments
    //                 reals del mes, tal com han passat. El mes que es cobra
    //                 l'assegurança, hi surten els 600 € de cop.
    //
    // La primera serveix per planificar; la segona, per quadrar el compte.

    /**
     * Resum del mes: capçalera amb el sou de referència i l'arbre de grups.
     *
     * Retorna un objecte i no una llista perquè el repartiment per
     * percentatges necessita dir sobre quin sou s'ha calculat: una llista de
     * grups amb sostres, sense la base, no es pot interpretar.
     */
    public Map<String, Object> getMonthlySummary(int year, int month) {
        YearMonth period = YearMonth.of(year, month);
        LocalDate from = period.atDay(1);
        LocalDate to = period.atEndOfMonth();

        IncomeBaseService.Base base = incomeBaseService.resolve(period);

        CategoryHierarchyService.Tree tree = hierarchyService.loadTree();
        Map<Long, BigDecimal> limitsByCategory = monthlyLimits(from, to, base);
        Map<Long, BigDecimal> percentagesByCategory = monthlyPercentages(from, to);
        Map<Long, List<RecurringTransaction>> recurringByCategory = activeRecurringByCategory();

        List<Map<String, Object>> groups = new ArrayList<>();
        for (Category root : tree.roots()) {
            groups.add(buildNode(root, tree, limitsByCategory, percentagesByCategory,
                    recurringByCategory, from, to));
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("periode", period.toString());
        summary.put("sou_base", base.amount());
        summary.put("sou_base_origen", base.origin().name());
        summary.put("ingressos_reals", realIncomeIn(from, to));
        summary.put("percentatge_assignat", percentagesByCategory.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        summary.put("grups", groups);
        return summary;
    }

    /** Ingressos realment importats del mes, per veure la desviació del sou previst. */
    private BigDecimal realIncomeIn(LocalDate from, LocalDate to) {
        return transactionRepository.findAll().stream()
                .filter(t -> "INCOME".equals(t.getType()))
                .filter(t -> t.getDate() != null
                        && !t.getDate().isBefore(from) && !t.getDate().isAfter(to))
                .map(t -> t.getAmount() != null ? t.getAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Percentatges vigents durant el mes, indexats per categoria. */
    private Map<Long, BigDecimal> monthlyPercentages(LocalDate from, LocalDate to) {
        Map<Long, BigDecimal> percentages = new HashMap<>();
        for (Budget budget : budgetRepository.findByActiveTrue()) {
            if (budget.getCategory() == null || budget.getPercentage() == null) continue;
            if (budget.getPeriodStart() == null || budget.getPeriodEnd() == null) continue;
            if (budget.getPeriodStart().isAfter(to) || budget.getPeriodEnd().isBefore(from)) continue;

            percentages.merge(budget.getCategory().getId(), budget.getPercentage(), BigDecimal::add);
        }
        return percentages;
    }

    private Map<String, Object> buildNode(Category category,
                                          CategoryHierarchyService.Tree tree,
                                          Map<Long, BigDecimal> limits,
                                          Map<Long, BigDecimal> percentages,
                                          Map<Long, List<RecurringTransaction>> recurring,
                                          LocalDate from, LocalDate to) {

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("categoria", category);
        node.put("es_grup", tree.isGroup(category.getId()));
        node.put("quantitat_limit", limits.get(category.getId()));
        node.put("percentatge", percentages.get(category.getId()));

        List<Category> children = tree.childrenByParent()
                .getOrDefault(category.getId(), List.of());

        if (children.isEmpty()) {
            // Fulla: aquí és on es mesura de debò.
            BigDecimal real = spentIn(Set.of(category.getId()), from, to);
            BigDecimal prorated = proratedFor(category, recurring);
            boolean fixed = category.isFixed();

            node.put("caixa_real", real);
            node.put("prorrateig_mensual", prorated);
            // Un fix compta pel prorrateig; un variable, pel que s'ha gastat.
            node.put("cost_vida_real", fixed ? prorated : real);
            // El pla d'un fix és el mateix prorrateig; el d'un variable, el
            // sostre que li hagi posat l'usuari, si n'hi ha.
            node.put("cost_vida_pla", fixed
                    ? prorated
                    : Optional.ofNullable(limits.get(category.getId())).orElse(BigDecimal.ZERO));
            // Serveix per entendre els pics de caixa: un fix anual que cau
            // aquest mes dispara la caixa sense que el cost de vida canviï.
            node.put("carrec_puntual_aquest_mes", fixed && real.signum() > 0);
            node.put("subcategories", List.of());
            return node;
        }

        // Grup: agrega els fills. No mesura res pel seu compte.
        List<Map<String, Object>> subNodes = new ArrayList<>();
        BigDecimal caixa = BigDecimal.ZERO;
        BigDecimal costReal = BigDecimal.ZERO;
        BigDecimal costPla = BigDecimal.ZERO;
        boolean anyOneOff = false;

        for (Category child : children) {
            Map<String, Object> sub = buildNode(child, tree, limits, percentages, recurring, from, to);
            subNodes.add(sub);
            caixa = caixa.add((BigDecimal) sub.get("caixa_real"));
            costReal = costReal.add((BigDecimal) sub.get("cost_vida_real"));
            costPla = costPla.add((BigDecimal) sub.get("cost_vida_pla"));
            anyOneOff |= Boolean.TRUE.equals(sub.get("carrec_puntual_aquest_mes"));
        }

        node.put("caixa_real", caixa);
        node.put("cost_vida_real", costReal);
        // Un límit posat directament al grup mana sobre la suma dels fills:
        // és el sostre que ha decidit l'usuari per al conjunt.
        node.put("cost_vida_pla", Optional.ofNullable(limits.get(category.getId())).orElse(costPla));
        node.put("prorrateig_mensual", null);
        node.put("carrec_puntual_aquest_mes", anyOneOff);
        node.put("subcategories", subNodes);
        return node;
    }

    /** Prorrateig mensual de les despeses fixes lligades a una fulla. */
    private BigDecimal proratedFor(Category leaf, Map<Long, List<RecurringTransaction>> recurring) {
        return recurring.getOrDefault(leaf.getId(), List.of()).stream()
                .filter(rt -> "EXPENSE".equals(rt.getType()))
                .map(RecurringTransaction::getMonthlyAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private Map<Long, List<RecurringTransaction>> activeRecurringByCategory() {
        Map<Long, List<RecurringTransaction>> byCategory = new HashMap<>();
        for (RecurringTransaction rt : recurringTransactionRepository.findByActiveTrue()) {
            if (rt.getCategory() == null) continue;
            byCategory.computeIfAbsent(rt.getCategory().getId(), k -> new ArrayList<>()).add(rt);
        }
        return byCategory;
    }

    /**
     * Límits vigents durant el mes, indexats per categoria.
     *
     * Es considera vigent qualsevol pressupost actiu el període del qual
     * encavalqui el mes, no només els que hi coincideixin exactament: així un
     * pressupost trimestral o anual també hi surt.
     */
    private Map<Long, BigDecimal> monthlyLimits(LocalDate from, LocalDate to,
                                                IncomeBaseService.Base base) {
        Map<Long, BigDecimal> limits = new HashMap<>();
        for (Budget budget : budgetRepository.findByActiveTrue()) {
            if (budget.getCategory() == null) continue;
            if (budget.getPeriodStart() == null || budget.getPeriodEnd() == null) continue;
            if (budget.getPeriodStart().isAfter(to) || budget.getPeriodEnd().isBefore(from)) continue;

            // Un percentatge mana sobre l'import fix: el sostre es recalcula a
            // partir del sou d'aquest mes. Si el sou no està definit, el
            // percentatge no dona cap xifra i s'ignora el pressupost, en comptes
            // d'ensenyar un sostre de zero euros que semblaria intencionat.
            BigDecimal limit = budget.getPercentage() != null
                    ? incomeBaseService.applyPercentage(base, budget.getPercentage())
                    : budget.getLimitAmount();

            if (limit == null) continue;
            limits.merge(budget.getCategory().getId(), limit, BigDecimal::add);
        }
        return limits;
    }

    public BigDecimal getBudgetUsagePercentage(Budget budget) {
        BigDecimal limit = budget.getLimitAmount();
        if (limit == null || limit.signum() == 0) return BigDecimal.ZERO;

        return calculateCurrentSpent(budget)
                .multiply(BigDecimal.valueOf(100))
                .divide(limit, 2, RoundingMode.HALF_UP);
    }
}
