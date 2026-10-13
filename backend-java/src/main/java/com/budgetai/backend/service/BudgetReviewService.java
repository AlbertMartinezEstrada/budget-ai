package com.budgetai.backend.service;

import com.budgetai.backend.model.Category;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * El pla contra el real, mes a mes: la pestanya Pressupost de l'anàlisi.
 *
 * **No calcula res de nou.** Llegeix el resum mensual del pressupost
 * (BudgetService.getMonthlySummary) de cada mes i el compara: si tingués la
 * seva fórmula, tard o d'hora diria una cosa diferent de la pantalla de
 * Pressupostos. El pressupost de cada mes es desa a part (es copia d'un mes a
 * l'altre), així que el pla d'un mes passat és el que es va fer aquell mes.
 *
 * **El real és el que ha passat de debò** (caixa_real): el que ha sortit a
 * les despeses, el que s'ha apartat a l'estalvi i el que ha entrat als
 * ingressos. No el cost de vida, que en un fix és el mateix prorrateig que el
 * pla i sempre quadraria.
 *
 * **Cada mes té el seu semàfor** (Status), i cada bloc, l'historial: en
 * quants mesos tancats s'ha passat i de quant de mitjana. És el que fa falta
 * per decidir si el pla no és realista o si cal retallar.
 */
@Service
public class BudgetReviewService {

    /** Més enrere, el resum d'un mes per cada mes es fa llarg i no ajuda a decidir. */
    static final int MAX_MONTHS = 24;
    static final int DEFAULT_MONTHS = 6;

    /** Fins on una desviació és un avís i no un desviament: el 10 % del pla, i com a mínim 5 €. */
    static final BigDecimal TOLERANCE_SHARE = new BigDecimal("0.10");
    static final BigDecimal TOLERANCE_MINIMUM = new BigDecimal("5.00");

    private static final String INCOME = "INCOME";
    private static final String SAVINGS = "SAVINGS";

    /**
     * El semàfor d'un node en un mes.
     *
     * BE              dins del pla: a les despeses, no s'hi ha arribat; a
     *                 l'estalvi i als ingressos, s'hi ha arribat.
     * AVIS            fora del pla per poc: fins al 10 % (mínim 5 €).
     * DESVIAT         fora del pla per més.
     * SENSE_PLA       hi ha moviments però no cap pla.
     * CARREC_PUNTUAL  fora del pla, però és un recurrent trimestral o anual:
     *                 el mes que cau, el real passa del prorrateig. S'ha de
     *                 mirar en l'acumulat, no en el mes.
     * EN_CURS         el mes encara no s'ha acabat i a l'estalvi o als
     *                 ingressos encara no s'hi ha arribat. Una despesa que ja
     *                 s'ha passat sí que surt com a passada.
     */
    public enum Status { BE, AVIS, DESVIAT, SENSE_PLA, CARREC_PUNTUAL, EN_CURS }

    @Autowired
    private BudgetService budgetService;

    /** Els mesos de from a to (inclosos), fins al mes actual. */
    public Map<String, Object> review(YearMonth from, YearMonth to) {
        return review(from, to, YearMonth.now());
    }

    Map<String, Object> review(YearMonth from, YearMonth to, YearMonth current) {
        if (to == null) to = current;
        if (from == null) from = to.minusMonths(DEFAULT_MONTHS - 1L);
        // Un mes que encara no ha arribat no té res a comparar.
        if (to.isAfter(current)) to = current;
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("El primer mes (" + from + ") és posterior a l'últim (" + to + ").");
        }
        long months = from.until(to, ChronoUnit.MONTHS) + 1;
        if (months > MAX_MONTHS) {
            throw new IllegalArgumentException("Com a molt es poden comparar " + MAX_MONTHS
                    + " mesos, i se n'han demanat " + months + ".");
        }

        List<Map<String, Object>> monthViews = new ArrayList<>();
        // Per bloc, en l'ordre en què surten: el de la pantalla de Pressupostos.
        Map<Long, BlockHistory> histories = new LinkedHashMap<>();
        for (YearMonth month = from; !month.isAfter(to); month = month.plusMonths(1)) {
            boolean inProgress = month.equals(current);
            Map<String, Object> view = monthView(month, inProgress);
            monthViews.add(view);
            collectHistory(histories, view, month, inProgress);
        }

        Map<String, Object> review = new LinkedHashMap<>();
        review.put("des_de", from.toString());
        review.put("fins_a", to.toString());
        review.put("mesos", monthViews);
        review.put("blocs", histories.values().stream().map(history -> history.toMap(monthViews)).toList());
        return review;
    }

    // ============ UN MES ============

    @SuppressWarnings("unchecked")
    private Map<String, Object> monthView(YearMonth month, boolean inProgress) {
        Map<String, Object> summary = budgetService.getMonthlySummary(month.getYear(), month.getMonthValue());

        List<Map<String, Object>> sections = new ArrayList<>();
        boolean hasPlan = false;
        for (Map<String, Object> section : (List<Map<String, Object>>) summary.get("seccions")) {
            String type = (String) section.get("tipus");
            List<Map<String, Object>> blocks = new ArrayList<>();
            for (Map<String, Object> group : (List<Map<String, Object>>) section.get("grups")) {
                Map<String, Object> block = nodeView(group, type, inProgress);
                if (block != null) blocks.add(block);
            }

            BigDecimal plan = sum(blocks, "pla");
            BigDecimal real = sum(blocks, "real");
            hasPlan |= plan.signum() > 0;
            // La secció surt com a puntual si el que la treu del pla és un
            // càrrec trimestral o anual d'algun dels seus blocs.
            boolean uneven = blocks.stream().anyMatch(block -> block.get("estat") == Status.CARREC_PUNTUAL);

            Map<String, Object> view = new LinkedHashMap<>();
            view.put("tipus", type);
            view.put("pla", plan);
            view.put("real", real);
            view.put("desviacio", real.subtract(plan));
            view.put("desviacio_percent", percentOf(real.subtract(plan), plan));
            view.put("estat", statusOf(type, plan, real, uneven, inProgress));
            if (SAVINGS.equals(type)) {
                view.put("gastat_del_fons", Objects.requireNonNullElse((BigDecimal) section.get("gastat_del_fons"), BigDecimal.ZERO));
            }
            view.put("blocs", blocks);
            sections.add(view);
        }

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("periode", month.toString());
        view.put("mes_en_curs", inProgress);
        // Un mes sense cap pla no es pot comparar: no és que s'hi hagi fet bé.
        view.put("te_pla", hasPlan);
        view.put("seccions", sections);
        return view;
    }

    /**
     * Un node del resum, comparat. Null si no té ni pla ni moviments: un
     * bloc buit no diu res i ompliria la taula.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> nodeView(Map<String, Object> node, String section, boolean inProgress) {
        BigDecimal plan = Objects.requireNonNullElse((BigDecimal) node.get("cost_vida_pla"), BigDecimal.ZERO);
        BigDecimal real = Objects.requireNonNullElse((BigDecimal) node.get("caixa_real"), BigDecimal.ZERO);

        List<Map<String, Object>> children = new ArrayList<>();
        for (Map<String, Object> child : (List<Map<String, Object>>) node.getOrDefault("subcategories", List.of())) {
            Map<String, Object> childView = nodeView(child, section, inProgress);
            if (childView != null) children.add(childView);
        }
        if (plan.signum() == 0 && real.signum() == 0 && children.isEmpty()) return null;

        Category category = (Category) node.get("categoria");
        boolean uneven = Boolean.TRUE.equals(node.get("carrec_irregular"));

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("categoria_id", category.getId());
        view.put("nom", category.getName());
        view.put("es_grup", Boolean.TRUE.equals(node.get("es_grup")));
        view.put("pla", plan);
        view.put("real", real);
        view.put("desviacio", real.subtract(plan));
        view.put("desviacio_percent", percentOf(real.subtract(plan), plan));
        view.put("estat", statusOf(section, plan, real, uneven, inProgress));
        view.put("carrec_irregular", uneven);
        if (node.containsKey("gastat_del_fons")) {
            view.put("gastat_del_fons", Objects.requireNonNullElse((BigDecimal) node.get("gastat_del_fons"), BigDecimal.ZERO));
        }
        view.put("subcategories", children);
        return view;
    }

    /**
     * El semàfor. Null si no hi ha res a jutjar (ni pla ni real).
     *
     * A les despeses, el que és dolent és passar-se'n; a l'estalvi i als
     * ingressos, quedar-se'n curt.
     */
    static Status statusOf(String section, BigDecimal plan, BigDecimal real, boolean uneven, boolean inProgress) {
        if (plan == null || plan.signum() <= 0) {
            return real == null || real.signum() == 0 ? null : Status.SENSE_PLA;
        }
        boolean moreIsBetter = INCOME.equals(section) || SAVINGS.equals(section);
        // Positiu, el que va malament: la despesa de més o l'estalvi de menys.
        BigDecimal gap = moreIsBetter ? plan.subtract(real) : real.subtract(plan);
        if (gap.signum() <= 0) return Status.BE;
        if (uneven) return Status.CARREC_PUNTUAL;
        if (moreIsBetter && inProgress) return Status.EN_CURS;
        BigDecimal tolerance = plan.multiply(TOLERANCE_SHARE).max(TOLERANCE_MINIMUM);
        return gap.compareTo(tolerance) <= 0 ? Status.AVIS : Status.DESVIAT;
    }

    // ============ L'HISTORIAL DE CADA BLOC ============

    @SuppressWarnings("unchecked")
    private void collectHistory(Map<Long, BlockHistory> histories, Map<String, Object> monthView,
                                YearMonth month, boolean inProgress) {
        for (Map<String, Object> section : (List<Map<String, Object>>) monthView.get("seccions")) {
            for (Map<String, Object> block : (List<Map<String, Object>>) section.get("blocs")) {
                Long id = (Long) block.get("categoria_id");
                histories.computeIfAbsent(id, missing -> new BlockHistory(id, (String) block.get("nom"),
                                (String) section.get("tipus")))
                        .add(month, block, inProgress);
            }
        }
    }

    /**
     * Un bloc al llarg dels mesos.
     *
     * El resum només compta els mesos tancats amb pla: el mes en curs encara
     * pot canviar, i un mes sense pla no es pot haver fet ni bé ni malament.
     */
    private static final class BlockHistory {
        private final Long categoryId;
        private final String name;
        private final String section;
        private final Map<String, Map<String, Object>> byMonth = new HashMap<>();
        private final EnumMap<Status, Integer> closedCounts = new EnumMap<>(Status.class);
        private BigDecimal closedPlan = BigDecimal.ZERO;
        private BigDecimal closedReal = BigDecimal.ZERO;
        private int closedMonthsWithPlan = 0;
        private boolean uneven = false;

        private BlockHistory(Long categoryId, String name, String section) {
            this.categoryId = categoryId;
            this.name = name;
            this.section = section;
        }

        private void add(YearMonth month, Map<String, Object> block, boolean inProgress) {
            Map<String, Object> cell = new LinkedHashMap<>();
            cell.put("periode", month.toString());
            cell.put("pla", block.get("pla"));
            cell.put("real", block.get("real"));
            cell.put("desviacio", block.get("desviacio"));
            cell.put("estat", block.get("estat"));
            byMonth.put(month.toString(), cell);
            uneven |= Boolean.TRUE.equals(block.get("carrec_irregular"));

            BigDecimal plan = (BigDecimal) block.get("pla");
            if (inProgress || plan.signum() <= 0) return;
            closedMonthsWithPlan++;
            closedPlan = closedPlan.add(plan);
            closedReal = closedReal.add((BigDecimal) block.get("real"));
            Status status = (Status) block.get("estat");
            if (status != null) closedCounts.merge(status, 1, Integer::sum);
        }

        /** @param months tots els del rang: un mes sense el bloc hi surt buit, perquè les columnes quadrin. */
        @SuppressWarnings("unchecked")
        private Map<String, Object> toMap(List<Map<String, Object>> months) {
            List<Map<String, Object>> cells = new ArrayList<>();
            for (Map<String, Object> month : months) {
                String period = (String) month.get("periode");
                cells.add(byMonth.getOrDefault(period, Map.of("periode", period)));
            }

            Map<String, Object> view = new LinkedHashMap<>();
            view.put("categoria_id", categoryId);
            view.put("nom", name);
            view.put("seccio", section);
            view.put("mesos", cells);
            view.put("mesos_tancats_amb_pla", closedMonthsWithPlan);
            view.put("mesos_be", closedCounts.getOrDefault(Status.BE, 0));
            view.put("mesos_avis", closedCounts.getOrDefault(Status.AVIS, 0));
            view.put("mesos_desviats", closedCounts.getOrDefault(Status.DESVIAT, 0));
            view.put("mesos_puntuals", closedCounts.getOrDefault(Status.CARREC_PUNTUAL, 0));
            view.put("pla_total", closedPlan);
            view.put("real_total", closedReal);
            // Amb signe: real menys pla. A les despeses, positiu és passar-se'n.
            view.put("desviacio_mitjana", closedMonthsWithPlan == 0 ? null
                    : closedReal.subtract(closedPlan).divide(BigDecimal.valueOf(closedMonthsWithPlan), 2, RoundingMode.HALF_UP));
            // L'acumulat també té semàfor: és el que compta per als càrrecs
            // trimestrals i anuals, que en un mes sol semblen un desviament.
            // Amb menys d'un any, ni l'acumulat els fa justícia: un càrrec
            // anual en sis mesos de pla també surt passat.
            view.put("estat_acumulat", closedMonthsWithPlan == 0 ? null
                    : statusOf(section, closedPlan, closedReal, uneven && closedMonthsWithPlan < 12, false));
            return view;
        }
    }

    // ============ AJUDES ============

    private static BigDecimal sum(List<Map<String, Object>> nodes, String key) {
        return nodes.stream()
                .map(node -> (BigDecimal) node.get(key))
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** La desviació en % del pla; null sense pla. */
    private static BigDecimal percentOf(BigDecimal deviation, BigDecimal plan) {
        if (plan == null || plan.signum() <= 0) return null;
        return deviation.multiply(BigDecimal.valueOf(100)).divide(plan, 1, RoundingMode.HALF_UP);
    }
}
