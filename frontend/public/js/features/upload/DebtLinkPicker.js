import { formatCurrency, escapeHtml } from '../../api.js';
import { t, formatDate } from '../../i18n.js';
import { hasReceiptsToChoose, receiptOptions } from '../../debtReceipts.js';
import { debtOptionLabel } from '../../debtLinks.js';

/**
 * A quin deute va una fila de la revisió d'un extracte: la quota de la Steam
 * Deck, l'entrada d'un préstec. Com el desplegable de l'edició d'un moviment,
 * amb el rebut que paga si el deute va a quotes.
 *
 * No crida l'API: la fila es desa amb la importació (deute_id i deute_rebut).
 *
 * @param save   (fila, { debtId, receipt }) => la vincula; receipt buit és «el que toca»
 * @param remove (fila) => la desvincula
 * @returns open(fila, { debts, current: { debtId, receipt } })
 */
export function setUpDebtLinkPicker(container, { save, remove }) {
    container.insertAdjacentHTML('beforeend', `
        <div id="debt-link-modal" class="fixed inset-0 bg-black/50 hidden items-center justify-center z-50 p-4">
            <div class="bg-white dark:bg-slate-800 rounded-3xl p-6 w-full max-w-md max-h-full overflow-y-auto">
                <h3 class="text-xl font-bold mb-1">${t('upload.debt-title')}</h3>
                <p id="debt-link-summary" class="text-sm font-medium mb-4"></p>
                <label class="block text-sm font-medium mb-1" for="debt-link-debt">${t('transactions.debt')}</label>
                <select id="debt-link-debt" class="form-control"></select>
                <p class="text-xs text-gray-500 dark:text-slate-400 mt-1">${t('transactions.debt-hint')}</p>
                <div id="debt-link-receipt-field" class="hidden mt-3">
                    <label class="block text-sm font-medium mb-1" for="debt-link-receipt">${t('transactions.debt-receipt')}</label>
                    <select id="debt-link-receipt" class="form-control"></select>
                    <p class="text-xs text-gray-500 dark:text-slate-400 mt-1">${t('transactions.debt-receipt-hint')}</p>
                </div>
                <div class="flex flex-wrap gap-2 justify-between mt-4">
                    <button type="button" id="debt-link-remove" class="btn btn-outline hidden">${t('upload.debt-remove')}</button>
                    <div class="flex gap-2 ml-auto">
                        <button type="button" id="debt-link-cancel" class="btn btn-outline">${t('common.cancel')}</button>
                        <button type="button" id="debt-link-save" class="btn btn-primary">${t('upload.debt-save')}</button>
                    </div>
                </div>
            </div>
        </div>
    `);

    const modal = document.getElementById('debt-link-modal');
    const debtSelect = document.getElementById('debt-link-debt');
    const receiptField = document.getElementById('debt-link-receipt-field');
    const receiptSelect = document.getElementById('debt-link-receipt');
    let current = null;
    let debtsById = new Map();

    const close = () => {
        modal.classList.add('hidden');
        modal.classList.remove('flex');
        current = null;
    };

    /** El rebut només es tria als deutes a quotes; per defecte, el que toca. */
    const refreshReceipts = (selected = null) => {
        const debt = debtsById.get(Number.parseInt(debtSelect.value, 10));
        const show = hasReceiptsToChoose(debt);
        receiptField.classList.toggle('hidden', !show);
        receiptSelect.innerHTML = show ? receiptOptions(debt, selected) : '';
    };

    debtSelect.addEventListener('change', () => refreshReceipts());
    document.getElementById('debt-link-cancel').addEventListener('click', close);
    modal.addEventListener('click', (event) => {
        if (event.target.id === 'debt-link-modal') close();
    });

    document.getElementById('debt-link-save').addEventListener('click', () => {
        const debt = debtsById.get(Number.parseInt(debtSelect.value, 10));
        if (!current || !debt) return;
        save(current, { debtId: debt.id, receipt: receiptField.classList.contains('hidden') ? null : (receiptSelect.value || null) });
        close();
    });

    document.getElementById('debt-link-remove').addEventListener('click', () => {
        if (current) remove(current);
        close();
    });

    return function open(row, { debts = [], current: selected = null } = {}) {
        current = row;
        debtsById = new Map(debts.map(debt => [debt.id, debt]));
        document.getElementById('debt-link-summary').textContent = t('upload.debt-summary', {
            company: row.empresa || t('common.unknown'),
            date: formatDate(row.data),
            amount: formatCurrency(row.cost)
        });
        debtSelect.innerHTML = debts
            .map(debt => `<option value="${debt.id}">${escapeHtml(debtOptionLabel(debt))}</option>`)
            .join('');
        if (selected?.debtId) debtSelect.value = String(selected.debtId);
        refreshReceipts(selected?.receipt || null);
        document.getElementById('debt-link-save').disabled = debts.length === 0;
        document.getElementById('debt-link-remove').classList.toggle('hidden', !selected?.debtId);
        modal.classList.remove('hidden');
        modal.classList.add('flex');
    };
}
