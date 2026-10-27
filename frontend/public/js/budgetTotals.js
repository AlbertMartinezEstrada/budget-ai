/**
 * El resum del mes, a dalt de Pressupost: de cada part (ingressos, fixos,
 * variables i estalvi) el que es va pressupostar i el que ha passat de debò, i
 * quant s'ha gastat i apartat dels ingressos del mes.
 *
 * El real és caixa_real, com a la pestanya Presupuesto de l'anàlisi
 * (BudgetReviewService): el que ha sortit a les despeses, el que s'ha apartat a
 * l'estalvi i el que ha entrat als ingressos. No el cost de vida («real» de la
 * secció), que en un fix és el mateix prorrateig que el pla i sempre quadraria.
 *
 * Sense DOM ni traduccions, perquè es pugui provar sol.
 */

/** Els imports es compten en cèntims: amb decimals, 0.1 + 0.2 no fa 0.3. */
const toCents = (value) => Math.round((Number.parseFloat(value) || 0) * 100);

/** Les files de la taula, en ordre. SPENT és el total gastat: fixos més variables. */
export const TOTAL_ROWS = ['INCOME', 'FIXED', 'VARIABLE', 'SPENT', 'SAVINGS'];

/** El que omple la barra dels ingressos, en l'ordre en què es pinta. */
export const BAR_PARTS = ['FIXED', 'VARIABLE', 'SAVINGS'];

/** A l'estalvi i als ingressos, el bo és arribar al pla; a les despeses, no passar-se'n. */
const MORE_IS_BETTER = new Set(['INCOME', 'SAVINGS']);

/**
 * @param summary el resum mensual (GET /budgets/monthly-summary)
 * @returns {{ available: number|null, rows: Array, bar: Array, overIncome: number }}
 *   available  els ingressos del mes (total_disponible); null si no n'hi ha
 *   rows       una per TOTAL_ROWS: { key, plan, real, difference, tone, share }
 *              difference és real − pla; tone, 'bad' si una despesa passa del
 *              pla, 'good' si l'estalvi o els ingressos hi arriben, i si no
 *              'neutral'; share, el % dels ingressos (null als ingressos o
 *              si no n'hi ha)
 *   bar        { key, width } de BAR_PARTS, en % de l'amplada; si entre totes
 *              passen dels ingressos, es redueixen perquè hi càpiguen
 *   overIncome el que el gastat i l'estalviat passen dels ingressos (0 si no)
 */
export function monthTotals(summary) {
    const sections = new Map((summary?.seccions || []).map(section => [section.tipus, section]));
    const centsOf = (type) => {
        const section = sections.get(type);
        return {
            plan: toCents(section?.assignat),
            // Els blocs de primer nivell ja sumen els seus fills.
            real: (section?.grups || []).reduce((sum, block) => sum + toCents(block.caixa_real), 0)
        };
    };

    const parts = {
        INCOME: centsOf('INCOME'),
        FIXED: centsOf('FIXED'),
        VARIABLE: centsOf('VARIABLE'),
        SAVINGS: centsOf('SAVINGS')
    };
    parts.SPENT = {
        plan: parts.FIXED.plan + parts.VARIABLE.plan,
        real: parts.FIXED.real + parts.VARIABLE.real
    };

    const availableCents = toCents(summary?.total_disponible);
    const hasIncome = availableCents > 0;

    const rows = TOTAL_ROWS.map(key => {
        const { plan, real } = parts[key];
        return {
            key,
            plan: plan / 100,
            real: real / 100,
            difference: (real - plan) / 100,
            tone: toneOf(key, plan, real),
            share: key === 'INCOME' || !hasIncome ? null : (real / availableCents) * 100
        };
    });

    // Un estalvi negatiu (s'ha tret més del que s'hi ha posat) no ocupa barra.
    const widths = BAR_PARTS.map(key => hasIncome ? Math.max(0, parts[key].real) / availableCents * 100 : 0);
    const filled = widths.reduce((sum, width) => sum + width, 0);
    const scale = filled > 100 ? 100 / filled : 1;
    const usedCents = parts.SPENT.real + Math.max(0, parts.SAVINGS.real);

    return {
        available: hasIncome ? availableCents / 100 : null,
        rows,
        bar: BAR_PARTS.map((key, index) => ({ key, width: widths[index] * scale })),
        overIncome: hasIncome ? Math.max(0, usedCents - availableCents) / 100 : 0
    };
}

/**
 * Sense pla no hi ha res a jutjar: és el mateix criteri que el semàfor de
 * l'anàlisi (SENSE_PLA no és ni bé ni malament).
 */
function toneOf(key, plan, real) {
    if (plan <= 0) return 'neutral';
    if (MORE_IS_BETTER.has(key)) return real >= plan ? 'good' : 'neutral';
    return real > plan ? 'bad' : 'neutral';
}
