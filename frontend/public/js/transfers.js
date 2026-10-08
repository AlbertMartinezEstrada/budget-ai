import { escapeHtml } from './api.js';

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
    `⇄ ${type === 'INCOME' ? 'des de' : 'a'} ${counterpartName || 'un altre compte'}`;

/**
 * Les opcions del desplegable de l'altre compte.
 *
 * @param ownAccountId el compte del moviment, que no s'ofereix: un traspàs al
 *        mateix compte no mou res, i el backend el rebutja.
 */
export function counterpartOptions(accounts, ownAccountId, selected = null, emptyLabel = 'No és un traspàs') {
    return [`<option value="">${escapeHtml(emptyLabel)}</option>`]
        .concat((accounts || [])
            .filter(account => String(account.id) !== String(ownAccountId))
            .map(account => `<option value="${account.id}"${String(account.id) === String(selected) ? ' selected' : ''}>
                ${escapeHtml(account.nom)}${isSavingsAccount(account) ? ' (estalvi)' : ''}
            </option>`))
        .join('');
}
