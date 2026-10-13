package com.budgetai.backend.service;

import com.budgetai.backend.model.Category;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/**
 * Què fer amb el pressupost: com acabarà el mes en curs i què canviar el mes
 * que ve. La segona part de la pestanya Pressupost de l'anàlisi.
 *
 * Com BudgetReviewService, no té fórmula pròpia per al pla ni per al real:
 * llegeix el resum mensual del pressupost (BudgetService.getMonthlySummary).
 *
 * **La previsió** és dels blocs variables, els únics que depenen de com es
 * gasti: els fixos ja tenen el seu prorrateig. És el que s'ha gastat fins
 * avui més el que se sol gastar en els dies que queden (la mitjana dels
 * últims mesos, en proporció). No és el ritme d'aquest mes estirat fins al
 * final: una compra grossa el dia 2 diria que el mes acabarà en mil euros.
 *
 * **Les propostes** miren els últims mesos tancats (fins a tres amb
 * moviments) contra el pla d'ara, i només dels blocs variables que tenen un
 * pla posat a mà: el que surt d'un recurrent es canvia a Recurrents.
 *
 *   PUJAR   s'hi ha passat almenys dues vegades i de mitjana gasta més del
 *           pla: el pla no és realista. Proposta: la mitjana, arrodonida a 5 €
 *           cap amunt.
 *   BAIXAR  en tots aquests mesos s'hi ha gastat menys del 85 % del pla: en
 *           sobra sempre. Proposta: el màxim que s'hi ha gastat, arrodonit a
 *           5 € cap amunt, perquè amb el pla nou cap d'aquests mesos s'hauria
 *           passat. El que s'allibera pot anar a estalvi.
 *
 * Un mes sense cap moviment no compta: són els mesos d'abans de començar a
 * fer servir l'aplicació, i dirien que no es gasta res en res.
 */
@Service
public class BudgetAdviceService {

    /** Quants mesos tancats es miren enrere per trobar-ne prou amb moviments. */
    static final int LOOKBACK_MONTHS = 6;
    /** Quants mesos amb moviments es fan servir. */
    static final int SAMPLE_MONTHS = 3;
    /** Amb menys, no es proposa res: un sol mes pot ser l'excepció. */
    static final int MIN_SAMPLE_MONTHS = 2;
    /** Per sota d'aquesta part del pla en tots els mesos, en sobra. */
    static final BigDecimal SURPLUS_SHARE = new BigDecimal("0.85");
    /** Les propostes s'arrodoneixen a aquest pas, cap amunt. */
    static final BigDecimal STEP = new BigDecimal("5");

    private static final String VARIABLE = "VARIABLE";

    public enum Kind { PUJAR, BAIXAR }

    @Autowired
    private BudgetService budgetService;

    public Map<String, Object> advice() {
        return advice(LocalDate.now());
    }

    Map<String, Object> advice(LocalDate today) {
        YearMonth current = YearMonth.from(today);
        Map<String, Object> currentSummary = summaryOf(current);

        // Els mesos tancats més recents amb moviments, del més nou al més vell.
        List<MonthReals> sample = new ArrayList<>();
        for (int back = 1; back <= LOOKBACK_MONTHS && sample.size() < SAMPLE_MONTHS; back++) {
            MonthReals month = MonthReals.of(current.minusMonths(back), summaryOf(current.minusMonths(back)));
            if (month.hasMovements()) sample.add(month);
        }

        List<Map<String, Object>> variableBlocks = variableBlocksOf(currentSummary);

        List<Map<String, Object>> forecast = new ArrayList<>();
        Forecaster forecaster = new Forecaster(today, sample);
        for (Map<String, Object> block : variableBlocks) {
            Map<String, Object> view = forecaster.forecast(block);
            if (view != null) forecast.add(view);
        }

        List<Map<String, Object>> recommendations = new ArrayList<>();
        if (sample.size() >= MIN_SAMPLE_MONTHS) {
            for (Map<String, Object> block : variableBlocks) collectRecommendations(block, sample, recommendations);
        }

        Map<String, Object> advice = new LinkedHashMap<>();
        advice.put("periode", current.toString());
        advice.put("dia", today.getDayOfMonth());
        advice.put("dies_mes", current.lengthOfMonth());
        advice.put("previsio", forecast);
        advice.put("mes_desti", current.plusMonths(1).toString());
        advice.put("mesos_mostra", sample.stream().map(month -> month.period().toString()).toList());
        advice.put("recomanacions", recommendations);
        advice.put("efecte_net", recommendations.stream()
                .map(recommendation -> (BigDecimal) recommendation.get("diferencia"))
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        return advice;
    }

    private Map<String, Object> summaryOf(YearMonth month) {
        return budgetService.getMonthlySummary(month.getYear(), month.getMonthValue());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> variableBlocksOf(Map<String, Object> summary) {
        for (Map<String, Object> section : (List<Map<String, Object>>) summary.get("seccions")) {
            if (VARIABLE.equals(section.get("tipus"))) return (List<Map<String, Object>>) section.get("grups");
        }
        return List.of();
    }

    // ============ LA PREVISIÓ DEL MES EN CURS ============

    /**
     * La previsió d'un node: el que s'ha gastat fins avui més el que se sol
     * gastar en els dies que queden.
     *
     * Del que se sol gastar, per ordre: la mitjana dels últims mesos
     * (HISTORIC); si no n'hi ha, el pla (PLA); si tampoc, el ritme d'aquest
     * mes (RITME).
     */
    private static final class Forecaster {
        private final LocalDate today;
        private final List<MonthReals> sample;
        private final int daysInMonth;
        private final int daysAfterToday;
        private final BigDecimal remainingShare;

        private Forecaster(LocalDate today, List<MonthReals> sample) {
            this.today = today;
            this.sample = sample;
            this.daysInMonth = today.lengthOfMonth();
            this.daysAfterToday = daysInMonth - today.getDayOfMonth();
            this.remainingShare = BigDecimal.valueOf(daysAfterToday)
                    .divide(BigDecimal.valueOf(daysInMonth), 6, RoundingMode.HALF_UP);
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> forecast(Map<String, Object> node) {
            Category category = (Category) node.get("categoria");
            BigDecimal plan = amount(node.get("cost_vida_pla"));
            BigDecimal real = amount(node.get("caixa_real"));
            BigDecimal usual = averageOf(sample, category.getId());

            String basis;
            BigDecimal rest;
            if (usual.signum() > 0) {
                basis = "HISTORIC";
                rest = usual.multiply(remainingShare);
            } else if (plan.signum() > 0) {
                basis = "PLA";
                rest = plan.subtract(real).max(BigDecimal.ZERO).min(plan.multiply(remainingShare));
            } else {
                basis = "RITME";
                rest = real.multiply(BigDecimal.valueOf(daysAfterToday))
                        .divide(BigDecimal.valueOf(today.getDayOfMonth()), 6, RoundingMode.HALF_UP);
            }
            BigDecimal projected = real.add(rest).setScale(2, RoundingMode.HALF_UP);

            List<Map<String, Object>> children = new ArrayList<>();
            for (Map<String, Object> child : (List<Map<String, Object>>) node.getOrDefault("subcategories", List.of())) {
                Map<String, Object> childView = forecast(child);
                if (childView != null) children.add(childView);
            }
            if (plan.signum() == 0 && projected.signum() == 0 && children.isEmpty()) return null;

            Map<String, Object> view = new LinkedHashMap<>();
            view.put("categoria_id", category.getId());
            view.put("nom", category.getName());
            view.put("es_grup", Boolean.TRUE.equals(node.get("es_grup")));
            view.put("pla", plan);
            view.put("real", real);
            view.put("previsio", projected);
            view.put("base_previsio", basis);
            view.put("estat_previst", BudgetReviewService.statusOf(VARIABLE, plan, projected, false, false));
            // El que es pot gastar cada dia, avui inclòs, sense passar-se del pla.
            view.put("disponible_per_dia", plan.compareTo(real) > 0
                    ? plan.subtract(real).divide(BigDecimal.valueOf(daysAfterToday + 1L), 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO);
            view.put("subcategories", children);
            return view;
        }
    }

    // ============ LES PROPOSTES PER AL MES QUE VE ============

    @SuppressWarnings("unchecked")
    private void collectRecommendations(Map<String, Object> node, List<MonthReals> sample,
                                        List<Map<String, Object>> recommendations) {
        Map<String, Object> recommendation = recommendationFor(node, sample);
        if (recommendation != null) recommendations.add(recommendation);
        for (Map<String, Object> child : (List<Map<String, Object>>) node.getOrDefault("subcategories", List.of())) {
            collectRecommendations(child, sample, recommendations);
        }
    }

    /** Null si el node no té pla posat a mà o si el pla ja s'ajusta al que es gasta. */
    static Map<String, Object> recommendationFor(Map<String, Object> node, List<MonthReals> sample) {
        BigDecimal percent = (BigDecimal) node.get("percentatge");
        BigDecimal fixedAmount = (BigDecimal) node.get("quantitat_limit");
        // Sense pla propi (el d'un grup que suma els fills, el d'un recurrent)
        // no hi ha res a canviar aquí.
        if (percent == null && fixedAmount == null) return null;
        BigDecimal plan = amount(node.get("cost_vida_pla"));
        if (plan.signum() <= 0) return null;

        Category category = (Category) node.get("categoria");
        List<BigDecimal> reals = sample.stream().map(month -> month.realOf(category.getId())).toList();
        if (reals.size() < MIN_SAMPLE_MONTHS) return null;

        BigDecimal average = reals.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(reals.size()), 2, RoundingMode.HALF_UP);
        BigDecimal maximum = reals.stream().max(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
        long monthsOver = reals.stream()
                .map(real -> BudgetReviewService.statusOf(VARIABLE, plan, real, false, false))
                .filter(status -> status == BudgetReviewService.Status.AVIS || status == BudgetReviewService.Status.DESVIAT)
                .count();

        // El perquè, en paraules, el posa el navegador amb aquestes xifres.
        Kind kind;
        BigDecimal proposal;
        if (monthsOver >= 2 && average.compareTo(plan) > 0) {
            kind = Kind.PUJAR;
            proposal = roundUp(average);
        } else if (reals.stream().allMatch(real -> real.compareTo(plan.multiply(SURPLUS_SHARE)) <= 0)) {
            kind = Kind.BAIXAR;
            proposal = roundUp(maximum);
        } else {
            return null;
        }
        // Un canvi que no arriba a un pas no val la pena.
        if (proposal.subtract(plan).abs().compareTo(STEP) < 0) return null;

        Map<String, Object> recommendation = new LinkedHashMap<>();
        recommendation.put("categoria_id", category.getId());
        recommendation.put("nom", category.getName());
        recommendation.put("es_grup", Boolean.TRUE.equals(node.get("es_grup")));
        recommendation.put("tipus", kind);
        recommendation.put("pla_actual", plan);
        recommendation.put("proposta", proposal);
        recommendation.put("diferencia", proposal.subtract(plan));
        recommendation.put("mitjana_real", average);
        recommendation.put("maxim_real", maximum);
        recommendation.put("mesos_passats", monthsOver);
        List<Map<String, Object>> months = new ArrayList<>();
        for (int index = 0; index < sample.size(); index++) {
            months.add(Map.of("periode", sample.get(index).period().toString(), "real", reals.get(index)));
        }
        recommendation.put("mesos", months);
        // Un pla per percentatge es proposa en percentatge, sobre la base
        // d'aquest mes: aplicar-lo no l'ha de convertir en un import.
        BigDecimal base = (BigDecimal) node.get("base_assignacio");
        recommendation.put("percentatge_actual", percent);
        recommendation.put("percentatge_proposat", percent != null && base != null && base.signum() > 0
                ? proposal.multiply(BigDecimal.valueOf(100)).divide(base, 1, RoundingMode.CEILING)
                : null);
        return recommendation;
    }

    // ============ AJUDES ============

    /** El real de cada categoria d'un mes, de tots els nodes del resum. */
    record MonthReals(YearMonth period, Map<Long, BigDecimal> reals, boolean hasMovements) {

        @SuppressWarnings("unchecked")
        static MonthReals of(YearMonth period, Map<String, Object> summary) {
            Map<Long, BigDecimal> reals = new HashMap<>();
            boolean movements = false;
            for (Map<String, Object> section : (List<Map<String, Object>>) summary.get("seccions")) {
                for (Map<String, Object> node : (List<Map<String, Object>>) section.get("grups")) {
                    movements |= collect(node, reals);
                }
            }
            return new MonthReals(period, reals, movements);
        }

        @SuppressWarnings("unchecked")
        private static boolean collect(Map<String, Object> node, Map<Long, BigDecimal> reals) {
            BigDecimal real = amount(node.get("caixa_real"));
            reals.put(((Category) node.get("categoria")).getId(), real);
            boolean movements = real.signum() != 0;
            for (Map<String, Object> child : (List<Map<String, Object>>) node.getOrDefault("subcategories", List.of())) {
                movements |= collect(child, reals);
            }
            return movements;
        }

        BigDecimal realOf(Long categoryId) {
            return reals.getOrDefault(categoryId, BigDecimal.ZERO);
        }
    }

    private static BigDecimal averageOf(List<MonthReals> sample, Long categoryId) {
        if (sample.isEmpty()) return BigDecimal.ZERO;
        return sample.stream()
                .map(month -> month.realOf(categoryId))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(sample.size()), 6, RoundingMode.HALF_UP);
    }

    /** Cap amunt, al múltiple de 5 € següent: 301 → 305, 300 → 300, 0 → 0. */
    static BigDecimal roundUp(BigDecimal value) {
        if (value.signum() <= 0) return BigDecimal.ZERO.setScale(2);
        return value.divide(STEP, 0, RoundingMode.CEILING).multiply(STEP).setScale(2);
    }

    private static BigDecimal amount(Object value) {
        return value instanceof BigDecimal decimal ? decimal : BigDecimal.ZERO;
    }
}
