import { t, formatMonth, formatMonthRange, formatDate } from './i18n.js';

/**
 * La pestanya General de l'anàlisi (GET /analytics/general), sense DOM: els
 * períodes, les comparacions i els colors dels gràfics. El que pinta és a
 * features/analytics/General.js.
 *
 * Els noms (label) són claus de lang/Language_*.properties: la vista els passa
 * per t().
 */

const pad = (month) => String(month).padStart(2, '0');
const toPeriod = (year, month) => `${year}-${pad(month)}`;
const parse = (period) => String(period).split('-').map(Number);

/** El període «AAAA-MM» que és n mesos abans. */
export function monthsBefore(period, months) {
    const [year, month] = parse(period);
    const index = year * 12 + (month - 1) - months;
    return toPeriod(Math.floor(index / 12), (index % 12) + 1);
}

export const PERIOD_KINDS = [
    { id: 'mes', label: 'general.period-month' },
    { id: 'trimestre', label: 'general.period-quarter' },
    { id: 'any', label: 'general.period-year' },
    { id: 'ultims12', label: 'general.period-last-12' }
];

// Una clau per trimestre: el número s'escriu diferent en cada un (1r, 2n, 3r, 4t; 1.er, 2.º).
const QUARTER_KEYS = ['general.quarter-1', 'general.quarter-2', 'general.quarter-3', 'general.quarter-4'];

/**
 * Les opcions del segon desplegable segons el tipus de període, de la més
 * recent enrere. «Últims 12 mesos» no en té: sempre acaba al mes actual.
 */
export function periodOptions(kind, today = new Date()) {
    const year = today.getFullYear();
    const month = today.getMonth() + 1;
    if (kind === 'mes') {
        return Array.from({ length: 24 }, (unused, index) => {
            const period = monthsBefore(toPeriod(year, month), index);
            const [optionYear, optionMonth] = parse(period);
            return { value: period, label: formatMonth(optionYear, optionMonth) };
        });
    }
    if (kind === 'trimestre') {
        const quarter = Math.ceil(month / 3);
        return Array.from({ length: 8 }, (unused, index) => {
            const absolute = year * 4 + (quarter - 1) - index;
            const optionYear = Math.floor(absolute / 4);
            const optionQuarter = (absolute % 4) + 1;
            return { value: `${optionYear}-T${optionQuarter}`, label: t(QUARTER_KEYS[optionQuarter - 1], { year: optionYear }) };
        });
    }
    if (kind === 'any') {
        return Array.from({ length: 5 }, (unused, index) => ({ value: String(year - index), label: String(year - index) }));
    }
    return [];
}

/**
 * El rang de mesos (from, to) d'un període. Un any o un trimestre que encara
 * no s'ha acabat el retalla el backend al mes actual.
 */
export function periodRange(kind, value, today = new Date()) {
    const current = toPeriod(today.getFullYear(), today.getMonth() + 1);
    if (kind === 'mes') return { from: value, to: value };
    if (kind === 'trimestre') {
        const [year, quarter] = String(value).split('-T').map(Number);
        return { from: toPeriod(year, quarter * 3 - 2), to: toPeriod(year, quarter * 3) };
    }
    if (kind === 'any') return { from: `${value}-01`, to: `${value}-12` };
    return { from: monthsBefore(current, 11), to: current };
}

/** «octubre de 2026», «julio–septiembre de 2026», «2025» (un any sencer). */
export function rangeLabel(from, to) {
    const [fromYear, fromMonth] = parse(from);
    const [toYear, toMonth] = parse(to);
    if (from === to) return formatMonth(fromYear, fromMonth);
    if (fromYear === toYear && fromMonth === 1 && toMonth === 12) return String(fromYear);
    return formatMonthRange(fromYear, fromMonth, toYear, toMonth);
}

/** Les quatre xifres de dalt i si pujar és bo o dolent en cadascuna. */
export const SUMMARY_METRICS = [
    { key: 'total_income', label: 'general.income', upIsGood: true },
    { key: 'total_expense', label: 'general.expense', upIsGood: false },
    { key: 'total_savings', label: 'general.saved', upIsGood: true },
    { key: 'unassigned', label: 'general.unassigned', upIsGood: true }
];

/**
 * El canvi d'una xifra respecte d'una referència, en %. Null si no es pot
 * dir: sense referència, o amb una referència zero (de 0 a 50 no és cap %).
 *
 * @returns { percent, direction: 'up'|'down'|'same', tone: 'good'|'bad'|'neutral' }
 */
export function changeOf(current, reference, upIsGood) {
    const now = Number.parseFloat(current);
    const before = Number.parseFloat(reference);
    if (!Number.isFinite(now) || !Number.isFinite(before) || before === 0) return null;
    const percent = Math.round(((now - before) / Math.abs(before)) * 100);
    if (percent === 0) return { percent: 0, direction: 'same', tone: 'neutral' };
    const direction = percent > 0 ? 'up' : 'down';
    return { percent, direction, tone: (direction === 'up') === upIsGood ? 'good' : 'bad' };
}

/** «▲ 8 %», «▼ 3 %», «= 0 %»: la fletxa perquè no depengui només del color. */
export function formatChange(change) {
    if (!change) return '';
    const arrow = { up: '▲', down: '▼', same: '=' }[change.direction];
    return `${arrow} ${Math.abs(change.percent)} %`;
}

/**
 * De cada mes, quina part dels ingressos ha anat a fixos, variables, estalvi
 * i què ha quedat sense destinar, en %. Sense ingressos no es pot dir (null).
 * Si s'ha gastat més del que ha entrat, el que no s'ha destinat és negatiu:
 * la barra no el pot dibuixar i es diu a part (excedit).
 */
export function structureShares(month) {
    const income = Number.parseFloat(month.ingressos) || 0;
    if (income <= 0) return { periode: month.periode, shares: null };
    const share = (value) => Math.round(((Number.parseFloat(value) || 0) / income) * 1000) / 10;
    const unassigned = share(month.sense_destinar);
    return {
        periode: month.periode,
        shares: {
            fixos: share(month.fixos),
            variables: share(month.variables),
            estalvi: share(month.estalvi),
            sense_destinar: Math.max(unassigned, 0)
        },
        excedit: unassigned < 0 ? -unassigned : 0
    };
}

export const STRUCTURE_SERIES = [
    { key: 'fixos', label: 'general.fixed' },
    { key: 'variables', label: 'general.variable' },
    { key: 'estalvi', label: 'general.savings' },
    { key: 'sense_destinar', label: 'general.unassigned' }
];

export const EVOLUTION_SERIES = [
    { key: 'total_income', label: 'general.income' },
    { key: 'total_expense', label: 'general.expense' },
    { key: 'total_savings', label: 'general.saved' }
];

/**
 * Els colors dels gràfics: les quatre primeres posicions de la paleta
 * categòrica, en aquest ordre (validat per a daltonisme contra el blanc i
 * contra la superfície fosca de l'aplicació). Cada sèrie té sempre el mateix
 * color: els ingressos són blaus a tot arreu.
 */
export function chartTheme(dark) {
    return dark
        ? {
            series: ['#3987e5', '#d95926', '#199e70', '#c98500'],
            surface: '#1e293b', text: '#f0efec', muted: '#c3c2b7', grid: '#334155'
        }
        : {
            series: ['#2a78d6', '#eb6834', '#1baf7a', '#eda100'],
            surface: '#ffffff', text: '#0b0b0b', muted: '#52514e', grid: '#e1e0d9'
        };
}

/** Si el que està sense classificar és prou per desconfiar de la resta. */
export const UNCLASSIFIED_WARNING_PERCENT = 15;

export function unclassifiedIsHigh(unclassified) {
    return (Number.parseFloat(unclassified?.percentatge) || 0) >= UNCLASSIFIED_WARNING_PERCENT;
}

/** Les mides de les barres d'una llista, relatives a la més gran (0–100). */
export function barWidths(items, key = 'total') {
    const max = Math.max(...(items || []).map(item => Number.parseFloat(item[key]) || 0), 0);
    return (items || []).map(item => (max > 0 ? Math.round(((Number.parseFloat(item[key]) || 0) / max) * 1000) / 10 : 0));
}

/**
 * Treu els mesos buits del principi: els d'abans de començar a fer servir
 * l'aplicació. Al gràfic semblarien mesos sense ingressos ni despeses de
 * debò. Els buits del mig es queden: allà sí que vol dir que no hi ha hagut res.
 */
export function trimEmptyLeading(months, keys) {
    const list = months || [];
    const first = list.findIndex(month => keys.some(key => (Number.parseFloat(month[key]) || 0) !== 0));
    return first === -1 ? [] : list.slice(first);
}

/** «2026-10» → «oct 26», «Oct 26», per als eixos. */
export function axisMonth(period) {
    const [year, month] = parse(period);
    return formatDate(new Date(year, month - 1, 1), { month: 'short', year: '2-digit' });
}

/** Fins on arriba l'eix de l'estructura: el 100 %, llevat que algun mes s'hi passi. */
export function structureAxisMax(rows) {
    const largest = Math.max(0, ...(rows || [])
        .filter(row => row.shares)
        .map(row => Object.values(row.shares).reduce((sum, value) => sum + value, 0)));
    return largest <= 100.5 ? 100 : undefined;
}
