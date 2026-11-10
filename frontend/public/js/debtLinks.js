import { t } from './i18n.js';

/**
 * Vincular un moviment a un deute, amb el mateix criteri a l'edició d'un
 * moviment (Transaccions) i a la revisió d'un extracte: el nom de cada deute al
 * desplegable i la categoria que es proposa.
 */

/**
 * La categoria que toca a un moviment d'un deute, segons el sentit.
 *
 * Un préstec compta al pressupost pels moviments: el que em deixen eixampla
 * el que es pot repartir, i el que torno n'és una despesa. Triar el deute
 * proposa la categoria perquè no s'hagi de recordar quina és; si el moviment
 * és una altra cosa (el sopar que un amic em va pagar), es pot canviar.
 */
const DEBT_CATEGORIES = {
    DEC: { INCOME: 'Préstecs rebuts', EXPENSE: 'Pagament de deutes' },
    EM_DEUEN: { EXPENSE: 'Préstecs fets', INCOME: 'Cobrament de préstecs' }
};

/** El nom de la categoria per a un moviment d'aquest tipus vinculat a aquest deute; null si no n'hi ha. */
export function suggestedDebtCategory(debt, type) {
    return DEBT_CATEGORIES[debt?.direccio]?.[type] || null;
}

/** «Pares (dec)», «Steam Deck (dec, saldat)»: el deute i en quin sentit va. */
export function debtOptionLabel(debt) {
    const key = debt.direccio === 'DEC'
        ? (debt.saldat ? 'transactions.debt-option-owe-settled' : 'transactions.debt-option-owe')
        : (debt.saldat ? 'transactions.debt-option-owed-settled' : 'transactions.debt-option-owed');
    return t(key, { name: debt.nom });
}
