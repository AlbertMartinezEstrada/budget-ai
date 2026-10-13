package com.budgetai.backend.service;

import com.budgetai.backend.model.Category;
import com.budgetai.backend.model.Transaction;
import com.budgetai.backend.repository.TransactionPartRepository;
import com.budgetai.backend.repository.TransactionRepository;
import com.budgetai.backend.service.MoneyFlows.Flow;
import com.budgetai.backend.service.MoneyFlows.Kind;
import com.budgetai.backend.service.TransactionLines.Line;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.IntPredicate;

/**
 * La pestanya General de l'anàlisi: un període qualsevol (un mes, un
 * trimestre, un any) amb context.
 *
 * Què és cada línia ho decideix MoneyFlows, com a la resta de l'aplicació; el
 * resum del període és el mateix d'AnalyticsService.summaryOf. Aquí s'hi
 * afegeix el que fa falta per saber si el període va bé i on van els diners:
 *
 *   ANTERIOR      el període del mateix llarg just abans
 *   HABITUAL      el que és normal: la mitjana mensual dels sis mesos d'abans
 *                 (només els que tenen moviments), pel llarg del període
 *   ESTRUCTURA    de cada mes, quina part dels ingressos va a fixos, a
 *                 variables i a estalvi
 *   EVOLUCIÓ      els dotze mesos fins al final del període
 *   CATEGORIES    la despesa per blocs i subcategories, desplegable fins als
 *                 moviments (getMovements)
 *   COMERÇOS      on es gasta més i on es va més vegades
 *   FORMIGA       les despeses petites, que sumades pesen
 *   SENSE CLASSIFICAR  la despesa sense categoria o a «Altres»: si és molta,
 *                 la resta de xifres valen poc
 */
@Service
public class GeneralAnalysisService {

    static final int MAX_MONTHS = 36;
    static final int USUAL_MONTHS = 6;
    static final int EVOLUTION_MONTHS = 12;
    static final int TOP_MERCHANTS = 10;
    static final int TOP_SMALL_MERCHANTS = 5;
    /** Fins on una despesa és petita: el cafè, el pa, la màquina de vending. */
    static final BigDecimal SMALL_EXPENSE = new BigDecimal("10.00");
    /** On cau el que no té categoria quan es desa (TransactionController). */
    static final String UNCLASSIFIED_CATEGORY = "Altres";

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private TransactionPartRepository transactionPartRepository;

    @Autowired
    private CategoryHierarchyService hierarchyService;

    private List<Line> allLines() {
        return TransactionLines.expand(transactionRepository.findAll(), transactionPartRepository.findAll());
    }

    public Map<String, Object> general(YearMonth from, YearMonth to) {
        return general(from, to, YearMonth.now());
    }

    Map<String, Object> general(YearMonth from, YearMonth to, YearMonth current) {
        Range range = Range.of(from, to, current);
        CategoryHierarchyService.Tree tree = hierarchyService.loadTree();
        MoneyFlows flows = new MoneyFlows(tree);
        List<Line> lines = allLines();
        List<Line> inRange = lines.stream().filter(line -> line.isBetween(range.firstDay(), range.lastDay())).toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("des_de", range.from().toString());
        result.put("fins_a", range.to().toString());
        result.put("mesos", range.months());
        result.put("resum", AnalyticsService.summaryOf(flows, inRange, range.firstDay(), range.lastDay()));

        Range previous = range.previous();
        Map<String, Object> before = AnalyticsService.summaryOf(flows, lines, previous.firstDay(), previous.lastDay());
        before.put("des_de", previous.from().toString());
        before.put("fins_a", previous.to().toString());
        result.put("anterior", before);
        result.put("habitual", usual(flows, lines, range));

        result.put("estructura", structure(flows, tree, lines, range));
        result.put("evolucio", evolution(flows, lines, range.to()));

        List<Flow> expenseFlows = new ArrayList<>();
        List<Line> expenseLines = new ArrayList<>();
        for (Line line : inRange) {
            Flow flow = flows.of(line);
            if (flow.kind() != Kind.EXPENSE) continue;
            expenseLines.add(line);
            expenseFlows.add(flow);
        }
        BigDecimal totalExpense = expenseFlows.stream().map(Flow::amount).reduce(BigDecimal.ZERO, BigDecimal::add);

        result.put("categories", categoryTree(tree, expenseLines, expenseFlows, totalExpense));
        result.put("sense_classificar", unclassified(expenseLines, expenseFlows, totalExpense));
        result.put("comercos", merchants(expenseLines, expenseFlows));
        result.put("despesa_formiga", smallExpenses(tree, expenseLines, expenseFlows, totalExpense));
        return result;
    }

    // ============ EL PERÍODE ============

    /** Un rang de mesos sencers. */
    record Range(YearMonth from, YearMonth to) {

        /**
         * Sense to, el mes actual; sense from, el mateix mes que to. Un mes que
         * encara no ha arribat no té res: el rang s'acaba al mes actual.
         */
        static Range of(YearMonth from, YearMonth to, YearMonth current) {
            if (to == null || to.isAfter(current)) to = current;
            if (from == null) from = to;
            if (from.isAfter(to)) {
                throw new IllegalArgumentException("El primer mes (" + from + ") és posterior a l'últim (" + to + ").");
            }
            Range range = new Range(from, to);
            if (range.months() > MAX_MONTHS) {
                throw new IllegalArgumentException("Com a molt es poden mirar " + MAX_MONTHS
                        + " mesos, i se n'han demanat " + range.months() + ".");
            }
            return range;
        }

        int months() {
            return (int) from.until(to, ChronoUnit.MONTHS) + 1;
        }

        LocalDate firstDay() {
            return from.atDay(1);
        }

        LocalDate lastDay() {
            return to.atEndOfMonth();
        }

        /** El del mateix llarg just abans: d'un trimestre, el trimestre d'abans. */
        Range previous() {
            return new Range(from.minusMonths(months()), from.minusMonths(1));
        }
    }

    /**
     * El que és normal per a un període d'aquest llarg: la mitjana mensual dels
     * sis mesos d'abans, comptant només els que tenen moviments (els d'abans
     * de fer servir l'aplicació dirien que no es gasta res), per tants mesos
     * com té el període. Null si no n'hi ha cap.
     */
    private static Map<String, Object> usual(MoneyFlows flows, List<Line> lines, Range range) {
        BigDecimal income = BigDecimal.ZERO;
        BigDecimal expense = BigDecimal.ZERO;
        BigDecimal savings = BigDecimal.ZERO;
        BigDecimal unassigned = BigDecimal.ZERO;
        int counted = 0;
        for (int back = 1; back <= USUAL_MONTHS; back++) {
            YearMonth month = range.from().minusMonths(back);
            Map<String, Object> summary = AnalyticsService.summaryOf(flows, lines, month.atDay(1), month.atEndOfMonth());
            if (((Integer) summary.get("transaction_count")) == 0) continue;
            income = income.add((BigDecimal) summary.get("total_income"));
            expense = expense.add((BigDecimal) summary.get("total_expense"));
            savings = savings.add((BigDecimal) summary.get("total_savings"));
            unassigned = unassigned.add((BigDecimal) summary.get("unassigned"));
            counted++;
        }
        if (counted == 0) return null;

        BigDecimal scale = BigDecimal.valueOf(range.months());
        BigDecimal months = BigDecimal.valueOf(counted);
        Map<String, Object> usual = new LinkedHashMap<>();
        usual.put("mesos_base", counted);
        usual.put("total_income", income.multiply(scale).divide(months, 2, RoundingMode.HALF_UP));
        usual.put("total_expense", expense.multiply(scale).divide(months, 2, RoundingMode.HALF_UP));
        usual.put("total_savings", savings.multiply(scale).divide(months, 2, RoundingMode.HALF_UP));
        usual.put("unassigned", unassigned.multiply(scale).divide(months, 2, RoundingMode.HALF_UP));
        return usual;
    }

    // ============ ESTRUCTURA I EVOLUCIÓ ============

    /**
     * De cada mes del període, on han anat els ingressos. La despesa es
     * reparteix per la secció del bloc de la categoria; la d'Estalvi i
     * inversió és la pagada des d'un compte d'estalvi, i va a part perquè surt
     * del que ja s'havia apartat.
     */
    private static List<Map<String, Object>> structure(MoneyFlows flows, CategoryHierarchyService.Tree tree,
                                                       List<Line> lines, Range range) {
        List<Map<String, Object>> months = new ArrayList<>();
        for (YearMonth month = range.from(); !month.isAfter(range.to()); month = month.plusMonths(1)) {
            LocalDate first = month.atDay(1);
            LocalDate last = month.atEndOfMonth();
            BigDecimal fixed = BigDecimal.ZERO;
            BigDecimal variable = BigDecimal.ZERO;
            BigDecimal fromFund = BigDecimal.ZERO;
            for (Line line : lines) {
                if (!line.isBetween(first, last)) continue;
                Flow flow = flows.of(line);
                if (flow.kind() != Kind.EXPENSE) continue;
                String section = line.category() != null ? tree.sectionOf(line.category().getId()) : Category.VARIABLE;
                if (Category.FIXED.equals(section)) fixed = fixed.add(flow.amount());
                else if (Category.SAVINGS.equals(section)) fromFund = fromFund.add(flow.amount());
                else variable = variable.add(flow.amount());
            }

            Map<String, Object> view = new LinkedHashMap<>();
            view.put("periode", month.toString());
            view.put("ingressos", flows.sum(lines, Kind.INCOME, first, last));
            view.put("fixos", fixed);
            view.put("variables", variable);
            view.put("estalvi", flows.sum(lines, Kind.SAVING, first, last));
            view.put("gastat_del_fons", fromFund);
            view.put("sense_destinar", flows.unassigned(lines, first, last));
            months.add(view);
        }
        return months;
    }

    /** Els dotze mesos fins a l'últim del període: el context de qualsevol període. */
    private static List<Map<String, Object>> evolution(MoneyFlows flows, List<Line> lines, YearMonth last) {
        List<Map<String, Object>> months = new ArrayList<>();
        for (YearMonth month = last.minusMonths(EVOLUTION_MONTHS - 1L); !month.isAfter(last); month = month.plusMonths(1)) {
            Map<String, Object> summary = AnalyticsService.summaryOf(flows, lines, month.atDay(1), month.atEndOfMonth());
            summary.put("period", month.toString());
            months.add(summary);
        }
        return months;
    }

    // ============ ON VAN ELS DINERS ============

    /**
     * La despesa per blocs i subcategories, de més a menys. Un node on ha
     * tornat tant com s'hi ha gastat no hi surt. El que no té categoria va en
     * un bloc a part, sense id.
     */
    private static List<Map<String, Object>> categoryTree(CategoryHierarchyService.Tree tree, List<Line> lines,
                                                          List<Flow> expenseFlows, BigDecimal totalExpense) {
        Map<Long, BigDecimal> byLeaf = new HashMap<>();
        Map<Long, Integer> countByLeaf = new HashMap<>();
        BigDecimal withoutCategory = BigDecimal.ZERO;
        int withoutCategoryCount = 0;
        for (int index = 0; index < lines.size(); index++) {
            Line line = lines.get(index);
            BigDecimal amount = expenseFlows.get(index).amount();
            if (line.category() == null || !tree.byId().containsKey(line.category().getId())) {
                withoutCategory = withoutCategory.add(amount);
                withoutCategoryCount++;
                continue;
            }
            byLeaf.merge(line.category().getId(), amount, BigDecimal::add);
            countByLeaf.merge(line.category().getId(), 1, Integer::sum);
        }

        List<Map<String, Object>> roots = new ArrayList<>();
        for (Category root : tree.roots()) {
            Map<String, Object> node = categoryNode(tree, root, byLeaf, countByLeaf, totalExpense);
            if (node != null) {
                node.put("seccio", tree.sectionOfRoot(root));
                roots.add(node);
            }
        }
        if (withoutCategory.signum() > 0) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("categoria_id", null);
            node.put("nom", "Sin categoría");
            node.put("total", withoutCategory);
            node.put("percentatge", shareOf(withoutCategory, totalExpense));
            node.put("moviments", withoutCategoryCount);
            node.put("subcategories", List.of());
            node.put("seccio", Category.VARIABLE);
            roots.add(node);
        }
        roots.sort(byTotalDescending());
        return roots;
    }

    private static Map<String, Object> categoryNode(CategoryHierarchyService.Tree tree, Category category,
                                                    Map<Long, BigDecimal> byLeaf, Map<Long, Integer> countByLeaf,
                                                    BigDecimal totalExpense) {
        List<Map<String, Object>> children = new ArrayList<>();
        // Una categoria amb fills també pot tenir moviments propis, d'abans de
        // tenir-ne: compten al node, no a cap fill.
        BigDecimal total = byLeaf.getOrDefault(category.getId(), BigDecimal.ZERO);
        int count = countByLeaf.getOrDefault(category.getId(), 0);
        for (Category child : tree.childrenByParent().getOrDefault(category.getId(), List.of())) {
            Map<String, Object> childNode = categoryNode(tree, child, byLeaf, countByLeaf, totalExpense);
            if (childNode == null) continue;
            children.add(childNode);
            total = total.add((BigDecimal) childNode.get("total"));
            count += (Integer) childNode.get("moviments");
        }
        if (total.signum() <= 0) return null;
        children.sort(byTotalDescending());

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("categoria_id", category.getId());
        node.put("nom", category.getName());
        node.put("total", total);
        node.put("percentatge", shareOf(total, totalExpense));
        node.put("moviments", count);
        node.put("subcategories", children);
        return node;
    }

    /** La despesa sense categoria o a «Altres», on cau el que no s'ha classificat. */
    private static Map<String, Object> unclassified(List<Line> lines, List<Flow> expenseFlows, BigDecimal totalExpense) {
        BigDecimal total = BigDecimal.ZERO;
        int count = 0;
        for (int index = 0; index < lines.size(); index++) {
            Category category = lines.get(index).category();
            if (category != null && !UNCLASSIFIED_CATEGORY.equalsIgnoreCase(category.getName())) continue;
            total = total.add(expenseFlows.get(index).amount());
            count++;
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("total", total);
        view.put("moviments", count);
        view.put("percentatge", shareOf(total, totalExpense));
        return view;
    }

    /**
     * Els comerços amb més despesa i els que es repeteixen més. El comerç és el
     * del moviment (empresa); d'un moviment dividit, totes les parts són del
     * mateix comerç, però és una sola visita.
     */
    private static Map<String, Object> merchants(List<Line> lines, List<Flow> expenseFlows) {
        Map<String, Merchant> byName = merchantTotals(lines, expenseFlows, index -> true);
        List<Merchant> spending = byName.values().stream()
                .filter(merchant -> merchant.total.signum() > 0)
                .toList();

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("per_import", spending.stream()
                .sorted(Comparator.comparing((Merchant merchant) -> merchant.total).reversed())
                .limit(TOP_MERCHANTS).map(Merchant::toMap).toList());
        view.put("per_visites", spending.stream()
                .sorted(Comparator.comparingInt((Merchant merchant) -> merchant.visits()).reversed()
                        .thenComparing(Comparator.comparing((Merchant merchant) -> merchant.total).reversed()))
                .limit(TOP_MERCHANTS).map(Merchant::toMap).toList());
        return view;
    }

    /**
     * Les despeses petites del dia a dia: fins a 10 €, fora dels fixos (una
     * subscripció d'1 € no és una despesa formiga). Amb els comerços on se'n
     * fan més d'una, que és on s'acumulen.
     */
    private static Map<String, Object> smallExpenses(CategoryHierarchyService.Tree tree, List<Line> lines,
                                                     List<Flow> expenseFlows, BigDecimal totalExpense) {
        IntPredicate small = index -> {
            BigDecimal amount = expenseFlows.get(index).amount();
            if (amount.signum() <= 0 || amount.compareTo(SMALL_EXPENSE) > 0) return false;
            Category category = lines.get(index).category();
            return category == null || !Category.FIXED.equals(tree.sectionOf(category.getId()));
        };

        BigDecimal total = BigDecimal.ZERO;
        int count = 0;
        for (int index = 0; index < lines.size(); index++) {
            if (!small.test(index)) continue;
            total = total.add(expenseFlows.get(index).amount());
            count++;
        }

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("llindar", SMALL_EXPENSE);
        view.put("total", total);
        view.put("moviments", count);
        view.put("percentatge", shareOf(total, totalExpense));
        view.put("comercos", merchantTotals(lines, expenseFlows, small).values().stream()
                .filter(merchant -> merchant.visits() > 1)
                .sorted(Comparator.comparing((Merchant merchant) -> merchant.total).reversed())
                .limit(TOP_SMALL_MERCHANTS).map(Merchant::toMap).toList());
        return view;
    }

    /** Un comerç: el nom com es va veure primer, el total i les visites (moviments diferents). */
    private static final class Merchant {
        private final String name;
        private BigDecimal total = BigDecimal.ZERO;
        private final Set<Transaction> transactions = Collections.newSetFromMap(new IdentityHashMap<>());

        private Merchant(String name) {
            this.name = name;
        }

        private int visits() {
            return transactions.size();
        }

        private Map<String, Object> toMap() {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("nom", name);
            view.put("total", total);
            view.put("moviments", visits());
            return view;
        }
    }

    private static Map<String, Merchant> merchantTotals(List<Line> lines, List<Flow> expenseFlows,
                                                        IntPredicate include) {
        Map<String, Merchant> byName = new LinkedHashMap<>();
        for (int index = 0; index < lines.size(); index++) {
            if (!include.test(index)) continue;
            Transaction transaction = lines.get(index).transaction();
            String name = transaction.getEmpresa().trim();
            Merchant merchant = byName.computeIfAbsent(name.toLowerCase(Locale.ROOT), key -> new Merchant(name));
            merchant.total = merchant.total.add(expenseFlows.get(index).amount());
            merchant.transactions.add(transaction);
        }
        return byName;
    }

    // ============ ELS MOVIMENTS D'UNA CATEGORIA ============

    /**
     * Les línies de despesa d'una categoria (i de totes les seves fulles si és
     * un bloc) dins del període, de la més nova a la més vella: el que es veu
     * en desplegar una categoria. Sense categoria, les que no en tenen.
     *
     * Les mateixes línies que sumen a categories, amb el mateix import: d'un
     * moviment dividit, només la part d'aquesta categoria.
     */
    public List<Map<String, Object>> movements(Long categoryId, YearMonth from, YearMonth to) {
        return movements(categoryId, from, to, YearMonth.now());
    }

    List<Map<String, Object>> movements(Long categoryId, YearMonth from, YearMonth to, YearMonth current) {
        Range range = Range.of(from, to, current);
        CategoryHierarchyService.Tree tree = hierarchyService.loadTree();
        if (categoryId != null && !tree.byId().containsKey(categoryId)) {
            throw new NotFoundException("la categoria", categoryId);
        }
        Set<Long> ids = categoryId != null ? withDescendants(tree, categoryId) : Set.of();
        MoneyFlows flows = new MoneyFlows(tree);

        List<Map<String, Object>> movements = new ArrayList<>();
        for (Line line : allLines()) {
            if (!line.isBetween(range.firstDay(), range.lastDay())) continue;
            boolean known = line.category() != null && tree.byId().containsKey(line.category().getId());
            if (categoryId == null ? known : !(known && ids.contains(line.category().getId()))) continue;
            Flow flow = flows.of(line);
            if (flow.kind() != Kind.EXPENSE) continue;

            Map<String, Object> view = new LinkedHashMap<>();
            view.put("transaccio_id", line.transaction().getId());
            view.put("data", line.date());
            view.put("empresa", line.transaction().getEmpresa());
            view.put("descripcio", line.description());
            view.put("categoria", line.category() != null ? line.category().getName() : null);
            view.put("import", flow.amount());
            view.put("es_part", line.isPart());
            movements.add(view);
        }
        movements.sort(Comparator.comparing((Map<String, Object> view) -> (LocalDate) view.get("data")).reversed());
        return movements;
    }

    // ============ AJUDES ============

    /** La categoria i tot el que en penja, a qualsevol profunditat. */
    private static Set<Long> withDescendants(CategoryHierarchyService.Tree tree, Long categoryId) {
        Set<Long> ids = new HashSet<>();
        Deque<Long> pending = new ArrayDeque<>(List.of(categoryId));
        while (!pending.isEmpty()) {
            Long id = pending.pop();
            // Un parent_id mal informat podria formar un cicle.
            if (!ids.add(id)) continue;
            for (Category child : tree.childrenByParent().getOrDefault(id, List.of())) pending.push(child.getId());
        }
        return ids;
    }

    private static Comparator<Map<String, Object>> byTotalDescending() {
        return Comparator.comparing((Map<String, Object> node) -> (BigDecimal) node.get("total")).reversed();
    }

    /** El percentatge d'un import sobre el total; null sense total. */
    private static BigDecimal shareOf(BigDecimal part, BigDecimal total) {
        if (total == null || total.signum() <= 0) return null;
        return part.multiply(BigDecimal.valueOf(100)).divide(total, 1, RoundingMode.HALF_UP);
    }
}
