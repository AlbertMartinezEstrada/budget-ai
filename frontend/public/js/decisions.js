import { formatCurrency } from './api.js';

/**
 * La pestanya Decisiones de l'anàlisi (GET /analytics/decisions), sense DOM:
 * la simulació, el temps per arribar a cada meta i el que s'envia per
 * aplicar-la. La simulació es fa aquí perquè respongui a l'instant mentre
 * s'escriu.
 */

const MONTHS = ['enero', 'febrero', 'marzo', 'abril', 'mayo', 'junio',
    'julio', 'agosto', 'septiembre', 'octubre', 'noviembre', 'diciembre'];

const number = (value) => Number.parseFloat(value) || 0;
// «|| 0» perquè -0 no surti com a «−0.00 €».
const round2 = (value) => (Math.round(value * 100) / 100) || 0;

/** «2027-03» → «marzo 2027». */
export function monthName(period) {
    const [year, month] = String(period).split('-').map(Number);
    return MONTHS[month - 1] ? `${MONTHS[month - 1]} ${year}` : String(period);
}

/** El període «AAAA-MM» n mesos després d'una data. */
export function monthsAfter(today, months) {
    const index = today.getFullYear() * 12 + today.getMonth() + months;
    return `${Math.floor(index / 12)}-${String((index % 12) + 1).padStart(2, '0')}`;
}

/** El valor de partida de cada partida: el pla si en té; si no, el que s'hi gasta. */
export function startingValue(item) {
    return number(item.pla) > 0 ? number(item.pla) : number(item.mitjana_real);
}

/**
 * El que canvia amb els imports nous: cada partida i el total. Retallar una
 * partida allibera diners per estalviar (extra positiu); pujar-la en treu.
 *
 * @param values { categoria_id: nou import }; una partida que no hi és, no canvia
 */
export function simulate(items, values) {
    const rows = (items || []).map(item => {
        const before = startingValue(item);
        const raw = values?.[item.categoria_id];
        const after = raw === undefined || raw === '' || !Number.isFinite(Number.parseFloat(raw))
            ? before
            : Math.max(0, Number.parseFloat(raw));
        return { categoria_id: item.categoria_id, abans: before, despres: after, diferencia: round2(after - before) };
    });
    const extraMonthly = round2(-rows.reduce((total, row) => total + row.diferencia, 0));
    return { rows, extraMonthly, extraYearly: round2(extraMonthly * 12) };
}

/**
 * Quants mesos falten per a una meta estalviant cada mes el mateix. Null si
 * amb aquest estalvi no s'hi arriba mai.
 */
export function monthsToGoal(remaining, monthlySaving) {
    if (number(remaining) <= 0) return 0;
    if (number(monthlySaving) <= 0) return null;
    return Math.ceil(number(remaining) / number(monthlySaving));
}

/**
 * Quan s'arriba a una meta amb un estalvi mensual, i si és a temps de la
 * data objectiu (null si la meta no en té).
 */
export function goalProjection(goal, monthlySaving, today = new Date()) {
    const months = monthsToGoal(goal.falta, monthlySaving);
    if (months === null) return { months: null, period: null, onTime: goal.data_objectiu ? false : null };
    const period = monthsAfter(today, months);
    const deadline = goal.data_objectiu ? String(goal.data_objectiu).slice(0, 7) : null;
    return { months, period, onTime: deadline ? period <= deadline : null };
}

/** La meta ara i amb la simulació, i quants mesos abans s'hi arriba. */
export function goalComparison(goal, monthlySaving, extraMonthly, today = new Date()) {
    const now = goalProjection(goal, monthlySaving, today);
    const simulated = goalProjection(goal, number(monthlySaving) + number(extraMonthly), today);
    const monthsSooner = now.months !== null && simulated.months !== null ? now.months - simulated.months : null;
    return { now, simulated, monthsSooner };
}

/** «llegas en marzo 2027 (5 meses)», o que no s'hi arriba. */
export function projectionText(projection) {
    if (projection.months === null) return 'no llegas: ahorrando esto no se acaba nunca';
    if (projection.months === 0) return 'ya la tienes';
    return `llegas en ${monthName(projection.period)} (${projection.months} ${projection.months === 1 ? 'mes' : 'meses'})`;
}

/**
 * El que s'envia per aplicar la simulació al mes que ve: només les partides
 * que canvien. Un pla per percentatge es queda per percentatge, sobre la base
 * d'aquest mes, com a les propostes.
 */
export function simulationChanges(items, values) {
    const byId = new Map((items || []).map(item => [String(item.categoria_id), item]));
    return simulate(items, values).rows
        .filter(row => row.diferencia !== 0)
        .map(row => {
            const item = byId.get(String(row.categoria_id));
            const base = number(item.base);
            if (item.percentatge !== null && item.percentatge !== undefined && base > 0) {
                return { categoria_id: row.categoria_id, percentatge: Math.ceil((row.despres / base) * 1000) / 10 };
            }
            return { categoria_id: row.categoria_id, import: row.despres };
        });
}

/** «al mes», «cada 3 meses»… */
export function frequencyLabel(frequency) {
    return {
        DIARIA: 'al día', SETMANAL: 'a la semana', MENSUAL: 'al mes', TRIMESTRAL: 'cada 3 meses', ANUAL: 'al año'
    }[String(frequency || '').toUpperCase()] || '';
}

/** Els recurrents per blocs (Subscripcions, Llar…), el bloc que més costa primer. */
export function recurringGroups(items) {
    const groups = new Map();
    for (const item of items || []) {
        const name = item.bloc || 'Sin categoría';
        if (!groups.has(name)) groups.set(name, { bloc: name, anual: 0, partides: [] });
        const group = groups.get(name);
        group.partides.push(item);
        group.anual = round2(group.anual + number(item.anual));
    }
    return [...groups.values()].sort((first, second) => second.anual - first.anual);
}

/** «de 42.33 € a 95.00 € al mes (+124 %)». */
export function trendText(trend) {
    const percent = trend.increment_percent !== null && trend.increment_percent !== undefined
        ? ` (+${Number(trend.increment_percent)} %)` : '';
    return `de ${formatCurrency(trend.abans)} a ${formatCurrency(trend.ultim)} al mes${percent}`;
}
