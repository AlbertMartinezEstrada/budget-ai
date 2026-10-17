import { escapeHtml } from './api.js';
import { t } from './i18n.js';

/**
 * Traspassos entre comptes propis, vistos des del navegador.
 *
 * Un traspàs no és ni despesa ni ingrés: els diners continuen sent teus. Entre
 * comptes del dia a dia (Principal → Revolut) no compta al pressupost, i el que
 * es paga des de Revolut compta a la seva categoria. Cap a un compte d'estalvi
 * (Trade Republic) compta, a la seva categoria (Estalvis). El backend decideix
 * el mateix a InternalTransferService; aquí només serveix perquè el formulari
 * ho ensenyi abans de desar.
 */

/** Un compte d'estalvi o d'inversió: el que hi entra s'aparta, no es gasta. */
export const isSavingsAccount = (account) => account?.tipus === 'AHORRO' || account?.tipus === 'INVERSIONES';

/**
 * Si un traspàs compta al pressupost: només si un costat és d'estalvi i
 * l'altre no. Entre dos comptes del dia a dia els diners només canvien de lloc.
 */
export const transferCounts = (own, counterpart) => isSavingsAccount(counterpart) !== isSavingsAccount(own);

/** "⇄ a Revolut" si en surten, "⇄ des de Trade Republic" si hi tornen. */
export const transferLabel = (type, counterpartName) =>
    t(type === 'INCOME' ? 'transfers.label-from' : 'transfers.label-to',
        { account: counterpartName || t('transfers.another-account') });

/**
 * Les opcions del desplegable de l'altre compte.
 *
 * @param ownAccountId el compte del moviment, que no s'ofereix: un traspàs al
 *        mateix compte no mou res, i el backend el rebutja.
 */
export function counterpartOptions(accounts, ownAccountId, selected = null, emptyLabel = t('transfers.not-a-transfer')) {
    return [`<option value="">${escapeHtml(emptyLabel)}</option>`]
        .concat((accounts || [])
            .filter(account => String(account.id) !== String(ownAccountId))
            .map(account => `<option value="${account.id}"${String(account.id) === String(selected) ? ' selected' : ''}>
                ${escapeHtml(account.nom)}${isSavingsAccount(account) ? ` (${t('transfers.savings')})` : ''}
            </option>`))
        .join('');
}

/**
 * Què passarà amb un moviment d'una categoria d'Estalvi i inversió, per dir-ho
 * abans de desar. El mateix criteri que MoneyFlows al backend: mana la
 * categoria, estigui marcat com a traspàs o no.
 *
 * @param own el compte del moviment
 * @param counterpart l'altre compte, si és un traspàs: ja ho explica el seu avís
 * @returns el text de l'avís, o '' si no cal dir res
 */
export function savingsCategoryHint(type, own, counterpart) {
    if (counterpart) return '';
    if (isSavingsAccount(own)) {
        return type === 'EXPENSE' ? t('transfers.hint-paid-from-savings') : '';
    }
    return t(type === 'EXPENSE' ? 'transfers.hint-savings-category-expense' : 'transfers.hint-savings-category-income');
}
