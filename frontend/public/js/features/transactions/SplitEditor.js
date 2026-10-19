import { formatCurrency, escapeHtml } from '../../api.js';
import { categoryOptions, leafCategories } from '../../categoryOptions.js';
import { t, formatDate } from '../../i18n.js';

/**
 * Dividir un moviment en parts.
 *
 * Una mateixa línia de l'extracte pot ser diverses coses alhora: la
 * transferència a Trade Republic porta estalvi, la devolució d'un préstec, el
 * que es guarda per a l'assegurança i uns diners que no han de comptar. Cada
 * part té la seva categoria, si compta al pressupost i el seu deute.
 *
 * El saldo no es toca: el moviment ja el va moure pel total. Per això les parts
 * han de sumar exactament l'import, i el botó de desar no s'activa fins que
 * quadren. El backend ho torna a comprovar.
 *
 * El fa servir Transaccions, on les parts es desen de seguida, i la revisió
 * d'un extracte, on es guarden a la fila fins que es confirma la importació.
 * Per això no crida l'API: qui l'obre diu què vol dir desar.
 */

/** Els imports es comparen en cèntims: amb decimals, 0.1 + 0.2 no fa 0.3. */
const toCents = (value) => Math.round((Number.parseFloat(value) || 0) * 100);

/**
 * @param container  on s'afegeix el formulari
 * @param categories totes, tal com arriben de l'API: només s'ofereixen les fulles
 * @param debts      per vincular una part a un deute
 * @param save       async (moviment, parts) => desa-les; si llança, el missatge surt al formulari
 * @param remove     async (moviment) => treu la divisió
 * @returns la funció que obre el formulari per a un moviment
 */
export function setUpSplitEditor(container, { categories, debts, save, remove }) {
    container.insertAdjacentHTML('beforeend', `
        <div id="split-modal" class="fixed inset-0 bg-black/50 hidden items-center justify-center z-50 p-4">
            <div class="bg-white dark:bg-slate-800 rounded-3xl p-6 w-full max-w-3xl max-h-full overflow-y-auto">
                <h3 class="text-xl font-bold mb-1">${t('split.title')}</h3>
                <p id="split-summary" class="text-sm font-medium mb-1"></p>
                <p class="text-xs text-gray-500 dark:text-slate-400 mb-4">${t('split.hint')}</p>
                <div id="split-rows" class="space-y-3"></div>
                <button type="button" id="split-add" class="btn btn-sm btn-outline mt-3">${t('split.add-part')}</button>
                <p id="split-balance" class="text-sm font-medium mt-4"></p>
                <p id="split-error" class="text-error text-sm hidden mt-2"></p>
                <div class="flex flex-wrap gap-2 justify-between mt-4">
                    <button type="button" id="split-remove" class="btn btn-outline">${t('split.remove')}</button>
                    <div class="flex gap-2 ml-auto">
                        <button type="button" id="split-cancel" class="btn btn-outline">${t('common.cancel')}</button>
                        <button type="button" id="split-save" class="btn btn-primary">${t('common.save')}</button>
                    </div>
                </div>
            </div>
        </div>
    `);

    const modal = document.getElementById('split-modal');
    const rows = document.getElementById('split-rows');
    const error = document.getElementById('split-error');
    const saveButton = document.getElementById('split-save');
    const leafIdByName = new Map(leafCategories(categories).map(category => [category.nom, category.id]));
    let current = null;

    const categoryChoices = categoryOptions(categories);
    const debtOptions = [
        `<option value="">${t('split.no-debt')}</option>`,
        ...debts.map(debt => `<option value="${debt.id}">
            ${escapeHtml(t(debt.direccio === 'DEC' ? 'transactions.debt-option-owe' : 'transactions.debt-option-owed', { name: debt.nom }))}
        </option>`)
    ].join('');

    const close = () => {
        modal.classList.add('hidden');
        modal.classList.remove('flex');
        current = null;
    };

    function addRow(part = {}) {
        rows.insertAdjacentHTML('beforeend', `
            <div class="border border-slate-200 dark:border-slate-700 rounded-2xl p-3 grid grid-cols-12 gap-2 items-center" data-part-row>
                <input type="number" step="0.01" min="0.01" class="form-control col-span-6 md:col-span-2"
                       data-field="amount" placeholder="${t('common.amount')}" aria-label="${t('split.amount-label')}">
                <select class="form-control col-span-6 md:col-span-4" data-field="category" aria-label="${t('split.category-label')}">
                    ${categoryChoices}
                </select>
                <select class="form-control col-span-10 md:col-span-5" data-field="debt" aria-label="${t('split.debt-label')}">
                    ${debtOptions}
                </select>
                <button type="button" class="col-span-2 md:col-span-1 p-1 hover:bg-red-50 dark:hover:bg-red-500/10 text-red-600 rounded-lg justify-self-end"
                        data-action="remove-part" title="${t('split.remove-part')}">
                    <span class="material-symbols-outlined text-sm">close</span>
                </button>
                <input type="text" class="form-control col-span-12 md:col-span-6" data-field="description"
                       placeholder="${t('split.description-placeholder')}" aria-label="${t('split.description-label')}">
                <label class="col-span-8 md:col-span-4 flex items-center gap-2 text-sm">
                    <input type="checkbox" data-field="excluded">
                    ${t('split.excluded')}
                </label>
                <button type="button" class="col-span-4 md:col-span-2 text-xs text-primary hover:underline justify-self-end"
                        data-action="fill-rest" title="${t('split.fill-rest-title')}">
                    ${t('split.fill-rest')}
                </button>
            </div>
        `);
        const row = rows.lastElementChild;
        if (part.amount !== undefined) row.querySelector('[data-field="amount"]').value = part.amount;
        if (part.categoryId) row.querySelector('[data-field="category"]').value = String(part.categoryId);
        if (part.debtId) row.querySelector('[data-field="debt"]').value = String(part.debtId);
        row.querySelector('[data-field="description"]').value = part.description || '';
        row.querySelector('[data-field="excluded"]').checked = Boolean(part.excluded);
    }

    function readRows() {
        return [...rows.querySelectorAll('[data-part-row]')].map(row => ({
            amount: row.querySelector('[data-field="amount"]').value,
            categoryId: Number.parseInt(row.querySelector('[data-field="category"]').value, 10),
            debtId: Number.parseInt(row.querySelector('[data-field="debt"]').value, 10),
            description: row.querySelector('[data-field="description"]').value.trim(),
            excluded: row.querySelector('[data-field="excluded"]').checked
        }));
    }

    /** Diu quant falta o sobra, i només deixa desar quan quadra. */
    function refreshBalance() {
        if (!current) return;
        const parts = readRows();
        const total = toCents(current.cost);
        const assigned = parts.reduce((sum, part) => sum + toCents(part.amount), 0);
        const missing = total - assigned;
        const complete = parts.length >= 2
            && parts.every(part => toCents(part.amount) > 0 && Number.isInteger(part.categoryId));

        const balance = document.getElementById('split-balance');
        balance.textContent = t('split.assigned', { assigned: formatCurrency(assigned / 100), total: formatCurrency(total / 100) })
            + ' · ' + (missing === 0 ? t('split.balanced') : missing > 0
                ? t('split.missing', { amount: formatCurrency(missing / 100) })
                : t('split.extra', { amount: formatCurrency(-missing / 100) }));
        balance.classList.toggle('text-error', missing !== 0);
        balance.classList.toggle('text-success', missing === 0);
        saveButton.disabled = missing !== 0 || !complete;
    }

    rows.addEventListener('input', refreshBalance);
    rows.addEventListener('click', (event) => {
        const button = event.target.closest('button[data-action]');
        if (!button) return;
        const row = button.closest('[data-part-row]');

        if (button.dataset.action === 'remove-part') {
            row.remove();
        } else if (button.dataset.action === 'fill-rest') {
            const amountInput = row.querySelector('[data-field="amount"]');
            const others = readRows().reduce((sum, part) => sum + toCents(part.amount), 0)
                - toCents(amountInput.value);
            const rest = toCents(current.cost) - others;
            if (rest > 0) amountInput.value = (rest / 100).toFixed(2);
        }
        refreshBalance();
    });

    document.getElementById('split-add').addEventListener('click', () => {
        addRow();
        refreshBalance();
    });
    document.getElementById('split-cancel').addEventListener('click', close);
    modal.addEventListener('click', (event) => {
        if (event.target.id === 'split-modal') close();
    });

    saveButton.addEventListener('click', async () => {
        error.classList.add('hidden');
        saveButton.disabled = true;
        // Els noms han de coincidir amb els @JsonProperty de TransactionPart.
        // -1 vol dir "sense deute", com a l'edició d'un moviment.
        const payload = readRows().map(part => ({
            import: Number.parseFloat(part.amount),
            category: { id: part.categoryId },
            exclos_pressupost: part.excluded,
            deute_id: Number.isInteger(part.debtId) ? part.debtId : -1,
            descripcio: part.description
        }));
        try {
            await save(current, payload);
            close();
        } catch (failure) {
            error.textContent = failure.message || t('split.save-failed');
            error.classList.remove('hidden');
            refreshBalance();
        }
    });

    document.getElementById('split-remove').addEventListener('click', async () => {
        if (!confirm(t('split.remove-confirm'))) return;
        try {
            await remove(current);
            close();
        } catch (failure) {
            error.textContent = failure.message || t('split.remove-failed');
            error.classList.remove('hidden');
        }
    });

    return function open(transaction) {
        current = transaction;
        error.classList.add('hidden');
        rows.innerHTML = '';

        document.getElementById('split-summary').textContent =
            `${transaction.empresa || t('common.unknown')} · ${formatDate(transaction.data)} · ${formatCurrency(transaction.cost)}`;

        const parts = transaction.parts || [];
        if (parts.length > 0) {
            parts.forEach(part => addRow({
                amount: Number.parseFloat(part.import),
                categoryId: part.category?.id,
                debtId: part.deute_id,
                description: part.descripcio,
                excluded: part.exclos_pressupost
            }));
        } else {
            // La primera part comença com el moviment d'ara —la seva categoria,
            // el seu deute, si compta—, perquè dividir no en perdi res. L'import
            // es deixa buit: és el que cal decidir.
            addRow({
                categoryId: leafIdByName.get(transaction.categoria),
                debtId: transaction.deute_id,
                excluded: transaction.exclos_pressupost
            });
            addRow();
        }
        document.getElementById('split-remove').classList.toggle('hidden', parts.length === 0);
        refreshBalance();

        modal.classList.remove('hidden');
        modal.classList.add('flex');
    };
}
