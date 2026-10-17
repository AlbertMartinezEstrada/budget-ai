import { escapeHtml, formatCurrency } from './api.js';
import { t, formatDate, formatMonth, formatMonthRange } from './i18n.js';

/**
 * Els rebuts d'un deute a quotes, vistos des del navegador.
 *
 * Els rebuts van per períodes: el d'octubre es paga a l'octubre i no va
 * endarrerit fins que l'octubre s'acaba. Per això es diuen pel mes
 * ("octubre 2026") i no pel dia. L'estat el calcula el backend
 * (RepaymentSchedule); aquí només es posa en paraules i se n'ofereix la tria
 * en vincular un pagament.
 */

const CURRENT_PERIOD = {
    SETMANAL: 'debts.due-this-week',
    MENSUAL: 'debts.due-this-month',
    TRIMESTRAL: 'debts.due-this-quarter'
};

const STATUS_LABELS = {
    PAGAT: 'debts.status-paid',
    PARCIAL: 'debts.status-partial',
    PENDENT: 'debts.status-pending',
    ENDARRERIT: 'debts.status-overdue',
    SALTAT: 'debts.status-skipped',
    DESCOMPTAT: 'debts.status-discounted'
};

const REMOVED = new Set(['SALTAT', 'DESCOMPTAT']);

/** Si el rebut és al calendari: ni saltat ni descomptat. */
export const isActiveReceipt = (receipt) => !REMOVED.has(receipt?.estat);

const parse = (isoDate) => {
    const [year, month, day] = String(isoDate).split('-').map(Number);
    return { year, month, day };
};

/** El dilluns de la setmana d'una data "AAAA-MM-DD". */
function mondayOf(isoDate) {
    const { year, month, day } = parse(isoDate);
    const date = new Date(year, month - 1, day);
    // getDay(): diumenge és 0. El període va de dilluns a diumenge.
    date.setDate(date.getDate() - ((date.getDay() + 6) % 7));
    return date;
}

/**
 * El nom del rebut pel seu període, en l'idioma actual: "octubre del 2026",
 * "setmana del 05/10/2026", "octubre del 2026 – desembre del 2026".
 *
 * @param frequency la del deute; sense (tot de cop), el mes
 */
export function receiptLabel(isoDate, frequency) {
    if (!isoDate || !/^\d{4}-\d{2}-\d{2}$/.test(isoDate)) return isoDate || '-';
    const { year, month } = parse(isoDate);
    if (frequency === 'SETMANAL') {
        return t('debts.receipt-week', { date: formatDate(mondayOf(isoDate)) });
    }
    if (frequency === 'TRIMESTRAL') {
        const lastMonth = ((month + 1) % 12) + 1;
        const lastYear = month + 2 > 12 ? year + 1 : year;
        return formatMonthRange(year, month, lastYear, lastMonth);
    }
    return formatMonth(year, month);
}

/** L'estat en paraules: "toca aquest mes", "a mitges", "saltat: passa al final". */
export function receiptStatusLabel(status, frequency) {
    if (status === 'TOCA') return t(CURRENT_PERIOD[frequency] || CURRENT_PERIOD.MENSUAL);
    return t(STATUS_LABELS[status] || STATUS_LABELS.PENDENT);
}

/**
 * Si una data cau dins del període d'un rebut. El mateix que
 * RepaymentSchedule.samePeriod: el backend troba el rebut triat així, i el
 * desplegable l'ha de reconèixer igual.
 */
export function samePeriod(receiptDate, otherDate, frequency) {
    if (!receiptDate || !otherDate) return false;
    if (frequency === 'SETMANAL') {
        return mondayOf(receiptDate).getTime() === mondayOf(otherDate).getTime();
    }
    const receipt = parse(receiptDate);
    const other = parse(otherDate);
    const monthsApart = (other.year - receipt.year) * 12 + (other.month - receipt.month);
    return frequency === 'TRIMESTRAL' ? monthsApart >= 0 && monthsApart < 3 : monthsApart === 0;
}

/** Si en vincular-hi un pagament té sentit triar-ne el rebut: només a quotes. */
export const hasReceiptsToChoose = (debt) =>
    debt?.forma_retorn === 'QUOTES' && (debt.calendari || []).some(isActiveReceipt);

/**
 * Les opcions del desplegable de rebuts d'un deute.
 *
 * La primera, buida, és "el que toca": el primer rebut sense pagar, que és
 * el que tria el backend si no se li diu res. Si el rebut triat ja no és al
 * calendari (s'ha tret), surt igualment, perquè editar el moviment no el
 * canviï sense voler; el backend el compta al que toca.
 *
 * @param selected el deute_rebut del moviment, si en té
 */
export function receiptOptions(debt, selected = null) {
    const frequency = debt?.frequencia;
    const receipts = (debt?.calendari || []).filter(isActiveReceipt);
    const match = selected ? receipts.find(receipt => samePeriod(receipt.data, selected, frequency)) : null;

    const options = [`<option value="">${t('debts.receipt-default')}</option>`];
    if (selected && !match) {
        options.push(`<option value="${escapeHtml(selected)}" selected>
            ${escapeHtml(t('debts.receipt-not-in-schedule', { receipt: receiptLabel(selected, frequency) }))}
        </option>`);
    }
    for (const receipt of receipts) {
        const isSelected = match && match.data === receipt.data;
        options.push(`<option value="${escapeHtml(receipt.data)}"${isSelected ? ' selected' : ''}>
            ${escapeHtml(receiptLabel(receipt.data, frequency))} · ${formatCurrency(receipt.import)} · ${escapeHtml(receiptStatusLabel(receipt.estat, frequency))}
        </option>`);
    }
    return options.join('');
}
