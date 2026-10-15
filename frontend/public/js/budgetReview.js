import { escapeHtml, formatCurrency } from './api.js';

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

export const SECTION_LABELS = {
    INCOME: 'Ingresos',
    FIXED: 'Gastos fijos',
    VARIABLE: 'Gastos variables',
    SAVINGS: 'Estalvi i inversió'
};

const MORE_IS_BETTER = new Set(['INCOME', 'SAVINGS']);

/** Si en aquesta secció arribar al pla és el bo (estalvi, ingressos). */
export const moreIsBetter = (section) => MORE_IS_BETTER.has(section);

// Tailwind no genera classes construïdes en temps d'execució: s'enumeren senceres.
const STATUSES = {
    BE: {
        expense: 'Dentro', more: 'Cumplido',
        pill: 'bg-emerald-100 text-emerald-700 dark:bg-emerald-500/20 dark:text-emerald-300',
        dot: 'bg-emerald-500'
    },
    AVIS: {
        expense: 'Pasado por poco', more: 'Casi',
        pill: 'bg-amber-100 text-amber-800 dark:bg-amber-500/20 dark:text-amber-300',
        dot: 'bg-amber-400'
    },
    DESVIAT: {
        expense: 'Pasado', more: 'Corto',
        pill: 'bg-red-100 text-red-700 dark:bg-red-500/20 dark:text-red-300',
        dot: 'bg-red-500'
    },
    SENSE_PLA: {
        expense: 'Sin plan', more: 'Sin plan',
        pill: 'bg-slate-100 text-slate-600 dark:bg-slate-700 dark:text-slate-300',
        dot: 'bg-slate-400'
    },
    CARREC_PUNTUAL: {
        expense: 'Cargo puntual', more: 'Cobro puntual',
        pill: 'bg-blue-100 text-blue-700 dark:bg-blue-500/20 dark:text-blue-300',
        dot: 'bg-blue-500'
    },
    EN_CURS: {
        expense: 'En curso', more: 'En curso',
        pill: 'bg-slate-100 text-slate-600 dark:bg-slate-700 dark:text-slate-300',
        dot: 'bg-slate-400'
    }
};

/** Per què un estat és el que és, per al title de la pastilla. */
const STATUS_HINTS = {
    AVIS: 'Fuera del plan por poco: hasta un 10 % (o 5 €).',
    CARREC_PUNTUAL: 'Es un recurrente trimestral o anual: el mes que cae pasa del prorrateo. Míralo en el acumulado.',
    EN_CURS: 'El mes no ha acabado: todavía puede llegar.',
    SENSE_PLA: 'Hay movimientos pero no había nada planeado.'
};

/** «Dentro», «Pasado», «Corto», «Cargo puntual»…; '' si no hi ha res a jutjar. */
export function statusLabel(status, section) {
    const entry = STATUSES[status];
    if (!entry) return '';
    return moreIsBetter(section) ? entry.more : entry.expense;
}

/** La pastilla de color amb l'estat; '' si no n'hi ha. */
export function statusPill(status, section) {
    const label = statusLabel(status, section);
    if (!label) return '';
    const hint = STATUS_HINTS[status];
    return `<span class="px-2 py-0.5 rounded-full text-xs font-medium whitespace-nowrap ${STATUSES[status].pill}"
                  ${hint ? `title="${escapeHtml(hint)}"` : ''}>${escapeHtml(label)}</span>`;
}

/** El punt de color d'una cel·la de l'historial. Un mes sense res, un guionet. */
export function statusDot(status, title = '') {
    if (!STATUSES[status]) {
        return `<span class="text-xs text-slate-500" title="${escapeHtml(title || 'Sin plan ni movimientos')}">–</span>`;
    }
    return `<span class="inline-block w-3 h-3 rounded-full ${STATUSES[status].dot}" title="${escapeHtml(title)}"></span>`;
}

/** Amb signe sempre: «+40.00 €», «−25.00 €». La desviació és real menys pla. */
export function formatDeviation(amount) {
    const value = Number.parseFloat(amount) || 0;
    if (value === 0) return formatCurrency(0);
    return `${value > 0 ? '+' : '−'}${formatCurrency(Math.abs(value))}`;
}

const MONTHS = ['enero', 'febrero', 'marzo', 'abril', 'mayo', 'junio',
    'julio', 'agosto', 'septiembre', 'octubre', 'noviembre', 'diciembre'];

/** «2026-10» → «octubre 2026». */
export function monthLabel(period) {
    const [year, month] = String(period).split('-').map(Number);
    return MONTHS[month - 1] ? `${MONTHS[month - 1]} ${year}` : String(period);
}

/** «2026-10» → «oct», per a les columnes de l'historial. */
export function shortMonthLabel(period) {
    const [, month] = String(period).split('-').map(Number);
    return MONTHS[month - 1] ? MONTHS[month - 1].slice(0, 3) : String(period);
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
 * El que diu l'historial d'un bloc en una frase: «Pasado 5 de 6 meses · de
 * media +38.00 €». Només compten els mesos tancats amb pla, com al backend.
 */
export function historySummary(block) {
    const closed = block.mesos_tancats_amb_pla || 0;
    if (closed === 0) return 'Sin meses cerrados con plan';

    const more = moreIsBetter(block.seccio);
    const off = block.mesos_desviats || 0;
    const close = block.mesos_avis || 0;
    const oneOff = block.mesos_puntuals || 0;

    const parts = [];
    if (off === 0 && close === 0) {
        const fine = more ? 'Cumplido' : 'Dentro';
        // Un mes amb càrrec puntual no és ni bé ni malament: no es compta com a bé.
        parts.push(oneOff === 0 ? `${fine} los ${closed} meses` : `${fine} ${closed - oneOff} de ${closed} meses`);
    } else {
        parts.push(`${more ? 'Corto' : 'Pasado'} ${off + close} de ${closed} ${closed === 1 ? 'mes' : 'meses'}`
            + (close > 0 ? ` (${close} por poco)` : ''));
    }
    if (oneOff > 0) parts.push(`${oneOff} ${more ? 'cobro' : 'cargo'} puntual${oneOff > 1 ? 'es' : ''}`);
    if (block.desviacio_mitjana != null) parts.push(`de media ${formatDeviation(block.desviacio_mitjana)}`);
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
        || String(first.nom).localeCompare(String(second.nom), 'es'));
}

// ============ LA PREVISIÓ I LES PROPOSTES (GET /analytics/budget-advice) ============

/** D'on surt la previsió, per al title de la xifra. */
export function forecastBasis(basis) {
    switch (basis) {
        case 'HISTORIC': return 'Lo gastado hasta hoy más lo que sueles gastar en los días que quedan (media de los últimos meses).';
        case 'PLA': return 'Lo gastado hasta hoy más el plan de los días que quedan: no hay meses anteriores con los que comparar.';
        case 'RITME': return 'Al ritmo de este mes: no hay plan ni meses anteriores.';
        default: return '';
    }
}

/** El perquè d'una proposta, en una frase. */
export function adviceReason(recommendation) {
    const months = (recommendation.mesos || []).length;
    if (recommendation.tipus === 'PUJAR') {
        return `Te has pasado ${recommendation.mesos_passats} de ${months} meses; de media gastas `
            + `${formatCurrency(recommendation.mitjana_real)}. El plan no es realista.`;
    }
    if ((Number.parseFloat(recommendation.maxim_real) || 0) === 0) {
        return `No lo has usado en ${months} meses.`;
    }
    return `Te sobra todos los meses: como mucho gastaste ${formatCurrency(recommendation.maxim_real)}.`;
}

/** «10 % (100.00 €)» si el pla va per percentatge; si no, l'import. */
export function planLabel(amount, percentage) {
    if (percentage === null || percentage === undefined) return formatCurrency(amount);
    return `${Number(percentage).toLocaleString('es-ES')} % (${formatCurrency(amount)})`;
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
    if (rounded < 0) {
        return `Si aplicas las marcadas, liberas ${formatCurrency(-rounded)} al mes: puedes pasarlos a ahorro.`;
    }
    if (rounded > 0) {
        return `Si aplicas las marcadas, necesitas ${formatCurrency(rounded)} más al mes: saldrán de lo que queda sin repartir o del ahorro.`;
    }
    return 'Las marcadas no cambian el total del mes.';
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
