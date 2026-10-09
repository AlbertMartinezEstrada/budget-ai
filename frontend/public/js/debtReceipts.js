import { escapeHtml, formatCurrency } from './api.js';

/**
 * Els rebuts d'un deute a quotes, vistos des del navegador.
 *
 * Els rebuts van per períodes: el d'octubre es paga a l'octubre i no va
 * endarrerit fins que l'octubre s'acaba. Per això es diuen pel mes
 * ("octubre 2026") i no pel dia. L'estat el calcula el backend
 * (RepaymentSchedule); aquí només es posa en paraules i se n'ofereix la tria
 * en vincular un pagament.
 */

const MONTHS = ['gener', 'febrer', 'març', 'abril', 'maig', 'juny',
    'juliol', 'agost', 'setembre', 'octubre', 'novembre', 'desembre'];

const CURRENT_PERIOD = {
    SETMANAL: 'toca aquesta setmana',
    MENSUAL: 'toca aquest mes',
    TRIMESTRAL: 'toca aquest trimestre'
};

const STATUS_LABELS = {
    PAGAT: 'pagat',
    PARCIAL: 'a mitges',
    PENDENT: 'pendent',
    ENDARRERIT: 'endarrerit',
    SALTAT: 'saltat: passa al final',
    DESCOMPTAT: 'descomptat del deute'
};

const REMOVED = new Set(['SALTAT', 'DESCOMPTAT']);

/** Si el rebut és al calendari: ni saltat ni descomptat. */
export const isActiveReceipt = (receipt) => !REMOVED.has(receipt?.estat);

const parse = (isoDate) => {
    const [year, month, day] = String(isoDate).split('-').map(Number);
    return { year, month, day };
};

const monthName = (year, month) => `${MONTHS[month - 1]} ${year}`;

/** El dilluns de la setmana d'una data "AAAA-MM-DD". */
function mondayOf(isoDate) {
    const { year, month, day } = parse(isoDate);
    const date = new Date(year, month - 1, day);
    // getDay(): diumenge és 0. El període va de dilluns a diumenge.
    date.setDate(date.getDate() - ((date.getDay() + 6) % 7));
    return date;
}

/**
 * El nom del rebut pel seu període: "octubre 2026", "setmana del 5/10/2026",
 * "octubre–desembre 2026".
 *
 * @param frequency la del deute; sense (tot de cop), el mes
 */
export function receiptLabel(isoDate, frequency) {
    if (!isoDate || !/^\d{4}-\d{2}-\d{2}$/.test(isoDate)) return isoDate || '-';
    const { year, month } = parse(isoDate);
    if (frequency === 'SETMANAL') {
        return `setmana del ${mondayOf(isoDate).toLocaleDateString('ca-ES')}`;
    }
    if (frequency === 'TRIMESTRAL') {
        const lastMonth = ((month + 1) % 12) + 1;
        const lastYear = month + 2 > 12 ? year + 1 : year;
        return lastYear === year
            ? `${MONTHS[month - 1]}–${monthName(lastYear, lastMonth)}`
            : `${monthName(year, month)}–${monthName(lastYear, lastMonth)}`;
    }
    return monthName(year, month);
}

/** L'estat en paraules: "toca aquest mes", "a mitges", "saltat: passa al final". */
export function receiptStatusLabel(status, frequency) {
    if (status === 'TOCA') return CURRENT_PERIOD[frequency] || CURRENT_PERIOD.MENSUAL;
    return STATUS_LABELS[status] || STATUS_LABELS.PENDENT;
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

    const options = ['<option value="">El que toca (el primer sense pagar)</option>'];
    if (selected && !match) {
        options.push(`<option value="${escapeHtml(selected)}" selected>
            ${escapeHtml(receiptLabel(selected, frequency))} · ja no és al calendari
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
