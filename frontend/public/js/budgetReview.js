import { escapeHtml, formatCurrency } from './api.js';
import { t, formatMonth, formatDate, formatPercent, formatList, getLocale } from './i18n.js';

/**
 * El pla contra el real (GET /analytics/budget-vs-actual), en paraules i
 * colors per a la pestanya Presupuesto de l'anàlisi.
 *
 * El semàfor el decideix el backend (BudgetReviewService.Status); aquí només
 * es diu en paraules. Les paraules depenen de la secció: a les despeses el
 * que va malament és passar-se'n, i a l'estalvi i als ingressos, quedar-se'n
 * curt. «Pasado» en un estalvi voldria dir el contrari del que ha passat.
 */

export const SECTION_ORDER = ['INCOME', 'FIXED', 'VARIABLE', 'SAVINGS'];

const MORE_IS_BETTER = new Set(['INCOME', 'SAVINGS']);

/** Si en aquesta secció arribar al pla és el bo (estalvi, ingressos). */
export const moreIsBetter = (section) => MORE_IS_BETTER.has(section);

// Tailwind no genera classes construïdes en temps d'execució: s'enumeren senceres.
const STATUSES = {
    BE: {
        expense: 'review.status-within', more: 'review.status-met',
        pill: 'bg-emerald-100 text-emerald-700 dark:bg-emerald-500/20 dark:text-emerald-300',
        dot: 'bg-emerald-500'
    },
    AVIS: {
        expense: 'review.status-slightly-over', more: 'review.status-almost',
        pill: 'bg-amber-100 text-amber-800 dark:bg-amber-500/20 dark:text-amber-300',
        dot: 'bg-amber-400'
    },
    DESVIAT: {
        expense: 'review.status-over', more: 'review.status-short',
        pill: 'bg-red-100 text-red-700 dark:bg-red-500/20 dark:text-red-300',
        dot: 'bg-red-500'
    },
    SENSE_PLA: {
        expense: 'review.status-no-plan', more: 'review.status-no-plan',
        pill: 'bg-slate-100 text-slate-600 dark:bg-slate-700 dark:text-slate-300',
        dot: 'bg-slate-400'
    },
    CARREC_PUNTUAL: {
        expense: 'review.status-one-off-charge', more: 'review.status-one-off-income',
        pill: 'bg-blue-100 text-blue-700 dark:bg-blue-500/20 dark:text-blue-300',
        dot: 'bg-blue-500'
    },
    EN_CURS: {
        expense: 'review.status-in-progress', more: 'review.status-in-progress',
        pill: 'bg-slate-100 text-slate-600 dark:bg-slate-700 dark:text-slate-300',
        dot: 'bg-slate-400'
    }
};

/**
 * Per què un estat és el que és, per al title de la pastilla. Els llindars
 * de l'avís són els de BudgetReviewService.
 */
const STATUS_HINTS = {
    AVIS: () => t('review.hint-slightly-over', { percent: formatPercent(10, 0), amount: formatCurrency(5) }),
    CARREC_PUNTUAL: () => t('review.hint-one-off'),
    EN_CURS: () => t('review.hint-in-progress'),
    SENSE_PLA: () => t('review.hint-no-plan')
};

/** «Dins», «Passat», «Curt», «Càrrec puntual»…; '' si no hi ha res a jutjar. */
export function statusLabel(status, section) {
    const entry = STATUSES[status];
    if (!entry) return '';
    return t(moreIsBetter(section) ? entry.more : entry.expense);
}

/** La pastilla de color amb l'estat; '' si no n'hi ha. */
export function statusPill(status, section) {
    const label = statusLabel(status, section);
    if (!label) return '';
    const hint = STATUS_HINTS[status]?.();
    return `<span class="px-2 py-0.5 rounded-full text-xs font-medium whitespace-nowrap ${STATUSES[status].pill}"
                  ${hint ? `title="${escapeHtml(hint)}"` : ''}>${escapeHtml(label)}</span>`;
}

/** El punt de color d'una cel·la de l'historial. Un mes sense res, un guionet. */
export function statusDot(status, title = '') {
    if (!STATUSES[status]) {
        return `<span class="text-xs text-slate-500" title="${escapeHtml(title || t('review.no-plan-no-movements'))}">–</span>`;
    }
    return `<span class="inline-block w-3 h-3 rounded-full ${STATUSES[status].dot}" title="${escapeHtml(title)}"></span>`;
}

/** Amb signe sempre: «+40,00 €», «−25,00 €». La desviació és real menys pla. */
export function formatDeviation(amount) {
    const value = Number.parseFloat(amount) || 0;
    if (value === 0) return formatCurrency(0);
    return `${value > 0 ? '+' : '−'}${formatCurrency(Math.abs(value))}`;
}

const isMonth = (month) => Number.isInteger(month) && month >= 1 && month <= 12;

/** «2026-10» → «octubre del 2026», «October 2026». */
export function monthLabel(period) {
    const [year, month] = String(period).split('-').map(Number);
    return isMonth(month) ? formatMonth(year, month) : String(period);
}

/** «2026-10» → «oct.», «Oct», per a les columnes de l'historial. */
export function shortMonthLabel(period) {
    const [, month] = String(period).split('-').map(Number);
    return isMonth(month) ? formatDate(new Date(2000, month - 1, 1), { month: 'short' }) : String(period);
}

const toPeriod = (year, month) => `${year}-${String(month).padStart(2, '0')}`;

/** El període «AAAA-MM» que és n mesos abans (n negatiu: després). */
export function shiftMonths(period, months) {
    const [year, month] = String(period).split('-').map(Number);
    const index = year * 12 + (month - 1) - months;
    return toPeriod(Math.floor(index / 12), (index % 12) + 1);
}

/** El mes en curs, «AAAA-MM». */
export function currentPeriod(today = new Date()) {
    return toPeriod(today.getFullYear(), today.getMonth() + 1);
}

/** Els mesos que es poden triar, del més recent enrere: el backend en compara com a molt 24. */
export function selectableMonths(today = new Date(), count = 24) {
    const current = currentPeriod(today);
    return Array.from({ length: count }, (unused, index) => shiftMonths(current, index));
}

/**
 * El que diu l'historial d'un bloc, a trossos separats per «·»: «Passat en 5
 * dels 6 mesos (1 per poc) · de mitjana +38,50 €». Només compten els mesos
 * tancats amb pla, com al backend.
 */
export function historySummary(block) {
    const closed = block.mesos_tancats_amb_pla || 0;
    if (closed === 0) return t('review.history-none');

    const more = moreIsBetter(block.seccio);
    const off = block.mesos_desviats || 0;
    const close = block.mesos_avis || 0;
    const oneOff = block.mesos_puntuals || 0;

    const parts = [];
    if (off === 0 && close === 0) {
        // Un mes amb càrrec puntual no és ni bé ni malament: no es compta com a bé.
        if (oneOff === 0) {
            parts.push(t(more ? 'review.history-all-met' : 'review.history-all-within', { count: closed }));
        } else {
            parts.push(t(more ? 'review.history-some-met' : 'review.history-some-within',
                { months: closed - oneOff, count: closed }));
        }
    } else if (close > 0) {
        parts.push(t(more ? 'review.history-short-close' : 'review.history-over-close',
            { months: off + close, count: closed, close }));
    } else {
        parts.push(t(more ? 'review.history-short' : 'review.history-over', { months: off, count: closed }));
    }
    if (oneOff > 0) {
        parts.push(t(more ? 'review.history-one-off-payments' : 'review.history-one-off-charges', { count: oneOff }));
    }
    if (block.desviacio_mitjana != null) {
        parts.push(t('review.history-average', { amount: formatDeviation(block.desviacio_mitjana) }));
    }
    return parts.join(' · ');
}

/**
 * L'historial per seccions, i dins de cada secció, primer els que més
 * vegades s'han desviat: són els que demanen una decisió.
 */
export function sortHistory(blocks) {
    const sectionIndex = (section) => {
        const index = SECTION_ORDER.indexOf(section);
        return index === -1 ? SECTION_ORDER.length : index;
    };
    return [...(blocks || [])].sort((first, second) =>
        sectionIndex(first.seccio) - sectionIndex(second.seccio)
        || (second.mesos_desviats || 0) - (first.mesos_desviats || 0)
        || (second.mesos_avis || 0) - (first.mesos_avis || 0)
        || String(first.nom).localeCompare(String(second.nom), getLocale()));
}

// ============ LA PREVISIÓ I LES PROPOSTES (GET /analytics/budget-advice) ============

/** D'on surt la previsió, per al title de la xifra. */
export function forecastBasis(basis) {
    switch (basis) {
        case 'HISTORIC': return t('review.basis-history');
        case 'PLA': return t('review.basis-plan');
        case 'RITME': return t('review.basis-pace');
        default: return '';
    }
}

/** El perquè d'una proposta, en una frase. */
export function adviceReason(recommendation) {
    const months = (recommendation.mesos || []).length;
    if (recommendation.tipus === 'PUJAR') {
        return t('review.reason-raise', {
            over: recommendation.mesos_passats, count: months, amount: formatCurrency(recommendation.mitjana_real)
        });
    }
    if ((Number.parseFloat(recommendation.maxim_real) || 0) === 0) {
        return t('review.reason-unused', { count: months });
    }
    return t('review.reason-lower', { amount: formatCurrency(recommendation.maxim_real) });
}

/** «10 % (100,00 €)» si el pla va per percentatge; si no, l'import. */
export function planLabel(amount, percentage) {
    if (percentage === null || percentage === undefined) return formatCurrency(amount);
    return t('review.plan-percent', { percent: formatPercent(percentage, 1, 0), amount: formatCurrency(amount) });
}

/** La suma de les diferències de les propostes marcades. */
export function netEffect(recommendations, selectedIds) {
    const selected = new Set((selectedIds || []).map(String));
    return (recommendations || [])
        .filter(recommendation => selected.has(String(recommendation.categoria_id)))
        .reduce((total, recommendation) => total + (Number.parseFloat(recommendation.diferencia) || 0), 0);
}

/** Què vol dir l'efecte net per al mes que ve. */
export function netEffectText(total) {
    const rounded = Math.round(total * 100) / 100;
    if (rounded < 0) return t('review.effect-frees', { amount: formatCurrency(-rounded) });
    if (rounded > 0) return t('review.effect-needs', { amount: formatCurrency(rounded) });
    return t('review.effect-none');
}

/**
 * El que ha fet el backend en aplicar canvis (POST /budgets/apply-changes), en
 * frases: els canvis al mes de destí, el que s'hi ha copiat del mes en curs i
 * els blocs que no s'han tocat. Ho fan servir les propostes i el simulador.
 *
 * @param target el nom del mes de destí, ja escrit («novembre del 2026»)
 */
export function appliedText(applied, target) {
    const skipped = applied.omesos || [];
    return [
        t('analysis.applied', { count: Number(applied.aplicats) || 0, target }),
        ...(applied.copiats > 0 ? [t('analysis.applied-copied', { count: Number(applied.copiats) })] : []),
        ...(skipped.length > 0 ? [t('analysis.applied-skipped', { names: formatList(skipped) })] : [])
    ].join(' ');
}

/**
 * El que s'envia per aplicar les propostes marcades: un pla per percentatge
 * es queda per percentatge.
 */
export function changesFor(recommendations, selectedIds) {
    const selected = new Set((selectedIds || []).map(String));
    return (recommendations || [])
        .filter(recommendation => selected.has(String(recommendation.categoria_id)))
        .map(recommendation => recommendation.percentatge_proposat != null
            ? { categoria_id: recommendation.categoria_id, percentatge: Number(recommendation.percentatge_proposat) }
            : { categoria_id: recommendation.categoria_id, import: Number(recommendation.proposta) });
}
