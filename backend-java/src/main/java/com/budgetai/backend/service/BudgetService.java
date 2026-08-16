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
     * Duplica al mes indicat les assignacions vigents del mes anterior.
     *
     * Les categories que ja tenen assignació al mes destí no es toquen: la
     * còpia no ha de trepitjar el que l'usuari ja hagi decidit per aquest mes.
     * Per això es pot cridar dues vegades sense duplicar res.
     *
     * Es copia el percentatge tal com estava; l'import es recalcula sol sobre
     * el bot del mes nou, que és tot el sentit de repartir per percentatges.
     */
    @Transactional
    public Map<String, Object> copyFromPreviousMonth(int year, int month) {
        YearMonth target = YearMonth.of(year, month);
        YearMonth source = target.minusMonths(1);

        Set<Long> alreadySet = new HashSet<>();
        for (Budget budget : activeBudgetsOverlapping(target.atDay(1), target.atEndOfMonth())) {
            alreadySet.add(budget.getCategory().getId());
        }

        int copied = 0;
        for (Budget origin : activeBudgetsOverlapping(source.atDay(1), source.atEndOfMonth())) {
            if (!alreadySet.add(origin.getCategory().getId())) continue;

            Budget copy = new Budget();
            copy.setCategory(origin.getCategory());
            copy.setLimitAmount(origin.getLimitAmount());
            copy.setPercentage(origin.getPercentage());
            copy.setPeriodStart(target.atDay(1));
            copy.setPeriodEnd(target.atEndOfMonth());
            copy.setActive(true);
            budgetRepository.save(copy);
            copied++;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("origen", source.toString());
        result.put("desti", target.toString());
        result.put("copiats", copied);
        return result;
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

    // ============ EL REPARTIMENT ÉS EN CASCADA ============
    //
    // El sou es reparteix per nivells, i cada nivell es reparteix dins del que
    // li ha tocat al de sobre:
    //
    //   sou  ──> FIXOS      import exacte de cada bloc
    //        └─> VARIABLES  el que queda: sou − fixos
    //                       └─> blocs        % del bot de variables
    //                                        └─> subseccions  % del bloc
    //
    // Per això un percentatge **no** és sempre del sou: és del bot del nivell
    // de sobre. Dir "30% de despeses variables" i "30% del sou" són coses
    // diferents, i abans no es podien distingir.
    //
    // La base de cada node és, en aquest ordre: el que té assignat el seu pare
    // si el pare té una assignació pròpia; si no, la base del pare. Sense
    // aquesta segona regla hi hauria una pescadilla: el bot d'un grup sense
    // assignació surt de sumar els fills, i els fills no poden agafar un
    // percentatge d'una xifra que encara no existeix.

    private static final String SECTION_FIXED = "FIXED";
    private static final String SECTION_VARIABLE = "VARIABLE";
    /**
     * Els ingressos no són una tercera manera de gastar: són d'on surten els
     * diners. Van en una secció a part perquè, barrejats amb els blocs de
     * despesa, semblaven un bloc variable amb zero euros assignats i el que
     * mesuraven no tenia res a veure amb el que mesuraven els seus veïns.
     */
    private static final String SECTION_INCOME = "INCOME";

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
        BigDecimal salary = base.isDefined() ? base.amount() : null;

        CategoryHierarchyService.Tree tree = hierarchyService.loadTree();
        Map<Long, BigDecimal> amountsByCategory = monthlyAmounts(from, to);
        Map<Long, BigDecimal> percentagesByCategory = monthlyPercentages(from, to);
        Map<Long, List<RecurringTransaction>> recurringByCategory = activeRecurringByCategory();

        Context context = new Context(tree, amountsByCategory, percentagesByCategory,
                recurringByCategory, from, to, salary);

        BigDecimal realIncome = realIncomeIn(from, to);
        // El que hi ha per repartir surt de la secció d'ingressos: la nòmina hi
        // és un bloc més, al costat dels regals i de qualsevol altra entrada.
        Income income = incomeAvailable(tree, amountsByCategory, from, to);
        // Amb la secció d'ingressos buida no hi hauria res a repartir i la
        // pantalla es quedaria morta, així que s'hi aplica el sou de referència.
        boolean fromIncomeSection = income.total().signum() > 0;
        BigDecimal available = fromIncomeSection ? income.total() : salary;

        // Els fixos es reparteixen primer perquè el bot dels variables és
        // justament el que en queda. Es mesuren contra tot el disponible: són
        // la primera mossegada, no hi ha cap bot per sobre seu.
        List<Map<String, Object>> fixedNodes = new ArrayList<>();
        List<Map<String, Object>> incomeNodes = new ArrayList<>();
        List<Category> variableRoots = new ArrayList<>();
        for (Category root : tree.roots()) {
            switch (sectionOf(root, tree)) {
                case SECTION_FIXED -> fixedNodes.add(context.buildNode(root, available, SECTION_FIXED));
                // Els ingressos no es reparteixen: no tenen bot del qual penjar.
                case SECTION_INCOME -> incomeNodes.add(context.buildNode(root, null, SECTION_INCOME));
                default -> variableRoots.add(root);
            }
        }

        BigDecimal fixedTotal = sumPlans(fixedNodes);
        // Si no hi ha sou definit no hi ha bot de variables, i els percentatges
        // no donen cap xifra: val més no ensenyar-ne cap que ensenyar-ne una
        // d'inventada.
        BigDecimal variablePot = available == null
                ? null
                : available.subtract(fixedTotal).max(BigDecimal.ZERO);

        List<Map<String, Object>> variableNodes = new ArrayList<>();
        for (Category root : variableRoots) {
            variableNodes.add(context.buildNode(root, variablePot, SECTION_VARIABLE));
        }
        BigDecimal variableTotal = sumPlans(variableNodes);
        BigDecimal assignedTotal = fixedTotal.add(variableTotal);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("periode", period.toString());
        summary.put("sou_base", base.amount());
        summary.put("sou_base_origen", base.origin().name());
        summary.put("ingressos_reals", realIncome);
        summary.put("ingressos_previstos", income.forecast());
        // El que hi ha de debò per repartir aquest mes: la suma dels ingressos.
        summary.put("total_disponible", available);
        summary.put("total_disponible_origen", fromIncomeSection ? "INGRESSOS" : "SOU");
        summary.put("total_assignat", assignedTotal);
        summary.put("percentatge_assignat", shareOf(assignedTotal, available));
        // Els ingressos van primer: és d'on surt tot el que reparteixen les
        // altres dues seccions, i llegit de dalt a baix explica el mes sencer.
        summary.put("seccions", List.of(
                // No reparteixen res, així que no tenen bot ni percentatge.
                section(SECTION_INCOME, null, sumPlans(incomeNodes), null, incomeNodes),
                section(SECTION_FIXED, available, fixedTotal, available, fixedNodes),
                section(SECTION_VARIABLE, variablePot, variableTotal, available, variableNodes)));
        // L'arbre pla de primer nivell es manté: hi ha consultes que només
        // volen els grups i no els importa a quina secció cauen.
        List<Map<String, Object>> allRoots = new ArrayList<>(fixedNodes);
        allRoots.addAll(variableNodes);
        allRoots.addAll(incomeNodes);
        summary.put("grups", allRoots);
        return summary;
    }

    /**
     * A quina de les dues grans seccions cau un bloc de primer nivell.
     *
     * Són dues preguntes diferents i per això es responen en dos llocs:
     *
     *   ON VIU EL BLOC    el declara el bloc amb el seu tipus_cost
     *   COM ES MESURA     el declara cada fulla amb el seu
     *
     * "Llar" és una despesa fixa —el sostre no es negocia cada mes— però la
     * llum i l'aigua de dins es mesuren pel consum real. Sense separar-ho, dues
     * fulles variables se n'enduien el bloc sencer, lloguer inclòs, a la secció
     * de variables.
     *
     * Un bloc que no declara res es dedueix de les fulles: és fix quan tot el
     * que hi penja ho és. Un bloc que barreja compta com a variable, perquè
     * partir-lo pel mig deixaria el mateix bloc a les dues seccions i no es
     * podria repartir ni en un lloc ni en l'altre.
     */
    private String sectionOf(Category root, CategoryHierarchyService.Tree tree) {
        if (SECTION_FIXED.equals(root.getCostType())) return SECTION_FIXED;
        if (SECTION_VARIABLE.equals(root.getCostType())) return SECTION_VARIABLE;
        if (SECTION_INCOME.equals(root.getCostType())) return SECTION_INCOME;

        List<Category> leaves = tree.leavesOf(root.getId());
        if (leaves.isEmpty()) return SECTION_VARIABLE;
        return leaves.stream().allMatch(Category::isFixed) ? SECTION_FIXED : SECTION_VARIABLE;
    }

    private Map<String, Object> section(String type, BigDecimal pot, BigDecimal assigned,
                                        BigDecimal available, List<Map<String, Object>> nodes) {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("tipus", type);
        // El bot dels fixos és tot el disponible; el dels variables, el que en
        // queda. Els ingressos no en tenen: no reparteixen res.
        section.put("base", pot);
        section.put("assignat", assigned);
        // Als ingressos, el "real" és el que ha entrat de debò; a la resta, el
        // que ha costat viure. Serveix per comparar el pla amb el que ha passat
        // sense haver de sumar els nodes a mà.
        section.put("real", nodes.stream()
                .map(node -> (BigDecimal) node.get("cost_vida_real"))
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        section.put("percentatge_del_sou", shareOf(assigned, available));
        section.put("restant", pot == null ? null : pot.subtract(assigned));
        section.put("grups", nodes);
        return section;
    }

    private BigDecimal sumPlans(List<Map<String, Object>> nodes) {
        return nodes.stream()
                .map(node -> (BigDecimal) node.get("cost_vida_pla"))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Quin tant per cent d'una base representa un import. Null si no hi ha base. */
    private BigDecimal shareOf(BigDecimal amount, BigDecimal base) {
        if (amount == null || base == null || base.signum() == 0) return null;
        return amount.multiply(BigDecimal.valueOf(100))
                .divide(base, 2, RoundingMode.HALF_UP);
    }

    /** Ingressos realment importats del mes, per veure la desviació del sou previst. */
    private BigDecimal realIncomeIn(LocalDate from, LocalDate to) {
        return incomeIn(null, from, to);
    }

    /** @param total el que hi ha per repartir; forecast, la part que era previsible. */
    private record Income(BigDecimal total, BigDecimal forecast) {}

    /**
     * El que hi ha per repartir aquest mes: la suma de la secció d'ingressos.
     *
     * La nòmina no és la base del pressupost, és un bloc d'ingrés més. Al
     * costat hi poden anar un regal, un premi o una feina puntual, i tots
     * eixamplen el que es pot repartir exactament igual.
     *
     * Cada fulla aporta **el que sigui més gran entre la previsió i el que ha
     * entrat de debò**:
     *
     *   PREVIST 3.300, REBUT 0      aporta 3.300. La nòmina encara no s'ha
     *                               importat, però se sap que arribarà: sense
     *                               això, un mes sense extracte no tindria res
     *                               a repartir.
     *   PREVIST 3.300, REBUT 3.400  aporta 3.400. El que ha passat mana sobre
     *                               el que es comptava.
     *   PREVIST 0,     REBUT 500    aporta 500. Un regal no estava previst.
     *
     * Prendre el màxim i no la suma és el que impedeix comptar dues vegades la
     * mateixa nòmina: la previsió i el moviment real són la mateixa cosa vista
     * dos cops, no dos ingressos.
     */
    private Income incomeAvailable(CategoryHierarchyService.Tree tree,
                                   Map<Long, BigDecimal> forecasts,
                                   LocalDate from, LocalDate to) {
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal forecastTotal = BigDecimal.ZERO;

        for (Category root : tree.roots()) {
            if (!SECTION_INCOME.equals(sectionOf(root, tree))) continue;

            for (Category leaf : tree.leavesOf(root.getId())) {
                BigDecimal received = receivedIn(leaf.getId(), from, to);
                BigDecimal forecast = forecasts.getOrDefault(leaf.getId(), BigDecimal.ZERO);
                total = total.add(received.max(forecast));
                forecastTotal = forecastTotal.add(forecast);
            }
        }
        return new Income(total, forecastTotal);
    }

    /** El mateix, però d'una sola categoria. */
    private BigDecimal receivedIn(Long categoryId, LocalDate from, LocalDate to) {
        return incomeIn(categoryId, from, to);
    }

    /** @param categoryId null per sumar-los tots, sigui quina sigui la categoria. */
    private BigDecimal incomeIn(Long categoryId, LocalDate from, LocalDate to) {
        return transactionRepository.findAll().stream()
                .filter(t -> "INCOME".equals(t.getType()))
                .filter(t -> categoryId == null
                        || (t.getCategory() != null && categoryId.equals(t.getCategory().getId())))
                .filter(t -> t.getDate() != null
                        && !t.getDate().isBefore(from) && !t.getDate().isAfter(to))
                .map(t -> t.getAmount() != null ? t.getAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Percentatges vigents durant el mes, indexats per categoria. */
    private Map<Long, BigDecimal> monthlyPercentages(LocalDate from, LocalDate to) {
        Map<Long, BigDecimal> percentages = new HashMap<>();
        for (Budget budget : activeBudgetsOverlapping(from, to)) {
            if (budget.getPercentage() == null) continue;
            percentages.merge(budget.getCategory().getId(), budget.getPercentage(), BigDecimal::add);
        }
        return percentages;
    }

    /**
     * Tot el que un recorregut de l'arbre necessita i no canvia entre nodes.
     *
     * Va en un objecte perquè buildNode ja arrossegava set paràmetres i l'únic
     * que varia de debò a cada crida és el node i la seva base.
     */
    private final class Context {
        private final CategoryHierarchyService.Tree tree;
        private final Map<Long, BigDecimal> amounts;
        private final Map<Long, BigDecimal> percentages;
        private final Map<Long, List<RecurringTransaction>> recurring;
        private final LocalDate from;
        private final LocalDate to;
        private final BigDecimal salary;

        private Context(CategoryHierarchyService.Tree tree,
                        Map<Long, BigDecimal> amounts,
                        Map<Long, BigDecimal> percentages,
                        Map<Long, List<RecurringTransaction>> recurring,
                        LocalDate from, LocalDate to, BigDecimal salary) {
            this.tree = tree;
            this.amounts = amounts;
            this.percentages = percentages;
            this.recurring = recurring;
            this.from = from;
            this.to = to;
            this.salary = salary;
        }

        /**
         * @param base    bot del nivell de sobre, sobre el qual es mesuren els
         *                percentatges d'aquest node. Null si no es pot saber
         *                perquè no hi ha sou definit.
         * @param section secció on viu el bloc del qual penja aquest node. Cal
         *                arrossegar-la fins a les fulles perquè decideix què es
         *                mesura: als ingressos, els moviments d'entrada; a la
         *                resta, els de despesa.
         */
        private Map<String, Object> buildNode(Category category, BigDecimal base, String section) {
            Long id = category.getId();

            // Un percentatge mana sobre l'import: el bot es recalcula cada mes
            // a partir de la base, així que si el sou canvia, s'hi ajusta sol.
            // Sense base, un percentatge no dona cap xifra i el node es queda
            // sense assignació pròpia, en comptes de caure a l'import antic.
            BigDecimal declaredPercent = percentages.get(id);
            BigDecimal assigned = declaredPercent != null
                    ? applyPercent(base, declaredPercent)
                    : amounts.get(id);

            Map<String, Object> node = new LinkedHashMap<>();
            node.put("categoria", category);
            node.put("es_grup", tree.isGroup(id));
            node.put("quantitat_limit", amounts.get(id));
            node.put("percentatge", declaredPercent);
            node.put("base_assignacio", base);

            List<Category> children = tree.childrenByParent().getOrDefault(id, List.of());

            // Els fills es reparteixen el que li ha tocat al pare. Si el pare
            // no té assignació pròpia, hereten la seva base: el seu bot sortirà
            // de sumar-los, i no poden ser alhora causa i conseqüència.
            BigDecimal childBase = assigned != null ? assigned : base;
            List<Map<String, Object>> subNodes = new ArrayList<>();
            for (Category child : children) {
                subNodes.add(buildNode(child, childBase, section));
            }

            BigDecimal plan = children.isEmpty()
                    ? fillLeaf(node, category, assigned, section)
                    : fillGroup(node, subNodes, assigned);

            node.put("percentatge_efectiu", shareOf(plan, base));
            node.put("percentatge_del_sou", shareOf(plan, salary));
            // El que un grup té assignat i encara no ha repartit entre els seus
            // fills. Només té sentit si el bot el marca ell: si surt de sumar
            // els fills, per definició no en sobra res.
            node.put("restant", assigned != null && !children.isEmpty()
                    ? assigned.subtract(sumPlans(subNodes))
                    : null);
            node.put("subcategories", subNodes);
            return node;
        }

        /** Fulla: aquí és on es mesura de debò. Retorna el pla. */
        private BigDecimal fillLeaf(Map<String, Object> node, Category category,
                                    BigDecimal assigned, String section) {

            // Una categoria d'ingrés no gasta res: el que s'hi ha de veure és
            // el que hi ha entrat. Sense això, la nòmina sortia sempre a zero
            // perquè només es miraven els moviments de despesa.
            if (SECTION_INCOME.equals(section)) {
                BigDecimal received = receivedIn(category.getId(), from, to);
                node.put("caixa_real", received);
                node.put("prorrateig_mensual", null);
                node.put("cost_vida_real", received);
                node.put("carrec_puntual_aquest_mes", false);

                // Aquí el "pla" és el que s'esperava cobrar, si s'ha dit.
                BigDecimal expected = assigned != null ? assigned : BigDecimal.ZERO;
                node.put("cost_vida_pla", expected);
                // El que aquesta categoria posa al total a repartir: el que ha
                // entrat o el que s'esperava, el que sigui més gran.
                node.put("aporta_al_disponible", received.max(expected));
                return expected;
            }

            BigDecimal real = spentIn(Set.of(category.getId()), from, to);
            BigDecimal prorated = proratedFor(category, recurring);
            boolean fixed = category.isFixed();

            node.put("caixa_real", real);
            node.put("prorrateig_mensual", prorated);
            // Un fix compta pel prorrateig; un variable, pel que s'ha gastat.
            node.put("cost_vida_real", fixed ? prorated : real);
            // Serveix per entendre els pics de caixa: un fix anual que cau
            // aquest mes dispara la caixa sense que el cost de vida canviï.
            node.put("carrec_puntual_aquest_mes", fixed && real.signum() > 0);

            // El que l'usuari hagi assignat mana. Si no ha assignat res, un fix
            // val el seu prorrateig —el rebut ja diu quant costa— i un variable
            // es queda sense pla.
            BigDecimal plan = assigned != null
                    ? assigned
                    : (fixed ? prorated : BigDecimal.ZERO);
            node.put("cost_vida_pla", plan);
            return plan;
        }

        /** Grup: agrega els fills. No mesura res pel seu compte. Retorna el pla. */
        private BigDecimal fillGroup(Map<String, Object> node, List<Map<String, Object>> subNodes,
                                     BigDecimal assigned) {
            BigDecimal caixa = BigDecimal.ZERO;
            BigDecimal costReal = BigDecimal.ZERO;
            boolean anyOneOff = false;

            for (Map<String, Object> sub : subNodes) {
                caixa = caixa.add((BigDecimal) sub.get("caixa_real"));
                costReal = costReal.add((BigDecimal) sub.get("cost_vida_real"));
                anyOneOff |= Boolean.TRUE.equals(sub.get("carrec_puntual_aquest_mes"));
            }

            node.put("caixa_real", caixa);
            node.put("cost_vida_real", costReal);
            node.put("prorrateig_mensual", null);
            node.put("carrec_puntual_aquest_mes", anyOneOff);

            // Una assignació posada directament al grup mana sobre la suma dels
            // fills: és el sostre que ha decidit l'usuari per al conjunt.
            BigDecimal plan = assigned != null ? assigned : sumPlans(subNodes);
            node.put("cost_vida_pla", plan);
            return plan;
        }

        private BigDecimal applyPercent(BigDecimal base, BigDecimal percent) {
            if (base == null) return null;
            return base.multiply(percent).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        }
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
     * Imports exactos vigents durant el mes, indexats per categoria.
     *
     * Els pressupostos per percentatge no hi surten: el seu import depèn del
     * bot del nivell de sobre, que encara no es coneix quan es llegeix la
     * taula. Es resolen al recorregut de l'arbre, on ja hi ha la base.
     */
    private Map<Long, BigDecimal> monthlyAmounts(LocalDate from, LocalDate to) {
        Map<Long, BigDecimal> amounts = new HashMap<>();
        for (Budget budget : activeBudgetsOverlapping(from, to)) {
            if (budget.getPercentage() != null || budget.getLimitAmount() == null) continue;
            amounts.merge(budget.getCategory().getId(), budget.getLimitAmount(), BigDecimal::add);
        }
        return amounts;
    }

    /**
     * Pressupostos actius que toquen el mes.
     *
     * Es considera vigent qualsevol pressupost el període del qual encavalqui
     * el mes, no només els que hi coincideixin exactament: així un pressupost
     * trimestral o anual també hi surt.
     */
    private List<Budget> activeBudgetsOverlapping(LocalDate from, LocalDate to) {
        List<Budget> current = new ArrayList<>();
        for (Budget budget : budgetRepository.findByActiveTrue()) {
            if (budget.getCategory() == null) continue;
            if (budget.getPeriodStart() == null || budget.getPeriodEnd() == null) continue;
            if (budget.getPeriodStart().isAfter(to) || budget.getPeriodEnd().isBefore(from)) continue;
            current.add(budget);
        }
        return current;
    }

    public BigDecimal getBudgetUsagePercentage(Budget budget) {
        BigDecimal limit = budget.getLimitAmount();
        if (limit == null || limit.signum() == 0) return BigDecimal.ZERO;

        return calculateCurrentSpent(budget)
                .multiply(BigDecimal.valueOf(100))
                .divide(limit, 2, RoundingMode.HALF_UP);
    }
}
