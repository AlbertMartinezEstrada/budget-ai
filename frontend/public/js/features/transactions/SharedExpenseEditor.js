import { formatCurrency, escapeHtml } from '../../api.js';
import { t, formatDate } from '../../i18n.js';
import { equalShare, balanceOf } from '../../sharedExpense.js';

/**
 * Els dos formularis d'una despesa compartida: compartir un pagament (el sopar
 * de 40 € entre quatre, amb els Bizums que ja han arribat) i vincular un Bizum
 * que arriba tard al deute que torna.
 *
 * Els fan servir Transaccions, on es desa de seguida, i la revisió d'un
 * extracte, on es guarda a les files fins que es confirma la importació. Per
 * això no criden l'API: qui els obre diu què vol dir desar.
 */

/**
 * @param save   async (pagament, { nom, part_meva, cobraments }) => desa-ho; si llança, el missatge surt al formulari
 * @param remove async (pagament) => desfà l'agrupació; només a la revisió, abans d'importar
 * @returns open(pagament, { candidates, current }) on cada candidat és { key, data, empresa, cost }
 */
export function setUpSharedExpenseEditor(container, { save, remove = null }) {
    container.insertAdjacentHTML('beforeend', `
        <div id="shared-modal" class="fixed inset-0 bg-black/50 hidden items-center justify-center z-50 p-4">
            <div class="bg-white dark:bg-slate-800 rounded-3xl p-6 w-full max-w-lg max-h-full overflow-y-auto">
                <h3 class="text-xl font-bold mb-1">${t('shared.title')}</h3>
                <p id="shared-summary" class="text-sm font-medium mb-1"></p>
                <p class="text-xs text-gray-500 dark:text-slate-400 mb-4">${t('shared.hint')}</p>
                <div class="space-y-3">
                    <div>
                        <label class="block text-sm font-medium mb-1" for="shared-name">${t('shared.name')}</label>
                        <input type="text" id="shared-name" class="form-control" placeholder="${t('shared.name-placeholder')}">
                    </div>
                    <div class="grid grid-cols-2 gap-3">
                        <div>
                            <label class="block text-sm font-medium mb-1" for="shared-people">${t('shared.people')}</label>
                            <input type="number" id="shared-people" class="form-control" min="2" step="1">
                        </div>
                        <div>
                            <label class="block text-sm font-medium mb-1" for="shared-mine">${t('shared.my-share')}</label>
                            <input type="number" id="shared-mine" class="form-control" min="0" step="0.01">
                        </div>
                    </div>
                    <p id="shared-owed" class="text-sm font-medium"></p>
                    <div>
                        <p class="block text-sm font-medium mb-1">${t('shared.repayments')}</p>
                        <p class="text-xs text-gray-500 dark:text-slate-400 mb-2">${t('shared.repayments-hint')}</p>
                        <div id="shared-candidates" class="space-y-1 max-h-56 overflow-y-auto"></div>
                    </div>
                    <p id="shared-balance" class="text-sm font-medium"></p>
                    <p id="shared-error" class="text-error text-sm hidden"></p>
                </div>
                <div class="flex flex-wrap gap-2 justify-between mt-4">
                    <button type="button" id="shared-remove" class="btn btn-outline hidden">${t('shared.remove')}</button>
                    <div class="flex gap-2 ml-auto">
                        <button type="button" id="shared-cancel" class="btn btn-outline">${t('common.cancel')}</button>
                        <button type="button" id="shared-save" class="btn btn-primary">${t('shared.save')}</button>
                    </div>
                </div>
            </div>
        </div>
    `);

    const modal = document.getElementById('shared-modal');
    const nameInput = document.getElementById('shared-name');
    const peopleInput = document.getElementById('shared-people');
    const mineInput = document.getElementById('shared-mine');
    const candidatesList = document.getElementById('shared-candidates');
    const error = document.getElementById('shared-error');
    const saveButton = document.getElementById('shared-save');
    let current = null;
    let candidates = [];

    const close = () => {
        modal.classList.add('hidden');
        modal.classList.remove('flex');
        current = null;
    };

    const chosenKeys = () => [...candidatesList.querySelectorAll('input[data-candidate]:checked')]
        .map(checkbox => checkbox.value);

    /** Diu quant em deuen i quant en falta, i només deixa desar si quadra. */
    function refresh() {
        if (!current) return;
        const chosen = new Set(chosenKeys());
        const amounts = candidates.filter(candidate => chosen.has(String(candidate.key))).map(candidate => candidate.cost);
        const balance = balanceOf(current.cost, mineInput.value, amounts);

        const owed = document.getElementById('shared-owed');
        owed.textContent = balance.validShare
            ? t('shared.owed', { amount: formatCurrency(balance.owed) })
            : t('shared.invalid-share', { total: formatCurrency(current.cost) });
        owed.classList.toggle('text-error', !balance.validShare);

        const summary = document.getElementById('shared-balance');
        summary.textContent = balance.tooMuch
            ? t('shared.too-much', { repaid: formatCurrency(balance.repaid), owed: formatCurrency(balance.owed) })
            : t('shared.balance', { repaid: formatCurrency(balance.repaid), pending: formatCurrency(balance.pending) });
        summary.classList.toggle('text-error', balance.tooMuch);
        saveButton.disabled = !balance.validShare || balance.tooMuch;
    }

    // Canviar quantes persones són torna a repartir a parts iguals; la meva
    // part es pot retocar després (si he pagat de més, la pujo).
    peopleInput.addEventListener('input', () => {
        if (!current) return;
        mineInput.value = equalShare(current.cost, peopleInput.value).mine.toFixed(2);
        refresh();
    });
    mineInput.addEventListener('input', refresh);
    candidatesList.addEventListener('change', refresh);

    document.getElementById('shared-cancel').addEventListener('click', close);
    modal.addEventListener('click', (event) => {
        if (event.target.id === 'shared-modal') close();
    });

    saveButton.addEventListener('click', async () => {
        error.classList.add('hidden');
        saveButton.disabled = true;
        try {
            await save(current, {
                nom: nameInput.value.trim(),
                part_meva: Number.parseFloat(mineInput.value),
                cobraments: chosenKeys()
            });
            close();
        } catch (failure) {
            error.textContent = failure.message || t('shared.save-failed');
            error.classList.remove('hidden');
            refresh();
        }
    });

    document.getElementById('shared-remove').addEventListener('click', async () => {
        if (!remove) return;
        await remove(current);
        close();
    });

    return function open(payment, { candidates: offered = [], current: existing = null } = {}) {
        current = payment;
        candidates = offered;
        error.classList.add('hidden');

        document.getElementById('shared-summary').textContent = t('shared.summary', {
            company: payment.empresa || t('common.unknown'),
            date: formatDate(payment.data),
            amount: formatCurrency(payment.cost)
        });
        nameInput.value = existing?.nom ?? (payment.empresa || '');
        peopleInput.value = existing ? '' : '2';
        mineInput.value = existing
            ? Number(existing.part_meva).toFixed(2)
            : equalShare(payment.cost, 2).mine.toFixed(2);

        const chosen = new Set((existing?.cobraments || []).map(String));
        candidatesList.innerHTML = offered.length === 0
            ? `<p class="text-sm text-gray-500 dark:text-slate-400">${t('shared.no-candidates')}</p>`
            : offered.map(candidate => `
                <label class="flex items-center gap-2 text-sm">
                    <input type="checkbox" data-candidate value="${escapeHtml(String(candidate.key))}"
                           ${chosen.has(String(candidate.key)) ? 'checked' : ''}>
                    <span>${escapeHtml(formatDate(candidate.data))} · ${escapeHtml(candidate.empresa || t('common.unknown'))}</span>
                    <span class="ml-auto text-success">+${escapeHtml(formatCurrency(candidate.cost))}</span>
                </label>`).join('');

        document.getElementById('shared-remove').classList.toggle('hidden', !(remove && existing));
        refresh();
        modal.classList.remove('hidden');
        modal.classList.add('flex');
        nameInput.focus();
    };
}

/**
 * El Bizum que arriba tard: a quin deute va. Les opcions són els deutes que
 * em deuen i no estan saldats, i a la revisió, també les despeses compartides
 * del mateix extracte.
 *
 * @param save   async (ingrés, valor de l'opció triada) => desa-ho
 * @param remove async (ingrés) => el desvincula; només a la revisió
 * @returns open(ingrés, { choices: [{ value, label }], current })
 */
export function setUpRepaymentPicker(container, { save, remove = null }) {
    container.insertAdjacentHTML('beforeend', `
        <div id="repayment-modal" class="fixed inset-0 bg-black/50 hidden items-center justify-center z-50 p-4">
            <div class="bg-white dark:bg-slate-800 rounded-3xl p-6 w-full max-w-md max-h-full overflow-y-auto">
                <h3 class="text-xl font-bold mb-1">${t('shared.repay-title')}</h3>
                <p id="repayment-summary" class="text-sm font-medium mb-1"></p>
                <p class="text-xs text-gray-500 dark:text-slate-400 mb-4">${t('shared.repay-hint')}</p>
                <label class="block text-sm font-medium mb-1" for="repayment-debt">${t('shared.repay-debt')}</label>
                <select id="repayment-debt" class="form-control"></select>
                <p id="repayment-error" class="text-error text-sm hidden mt-2"></p>
                <div class="flex flex-wrap gap-2 justify-between mt-4">
                    <button type="button" id="repayment-remove" class="btn btn-outline hidden">${t('shared.repay-remove')}</button>
                    <div class="flex gap-2 ml-auto">
                        <button type="button" id="repayment-cancel" class="btn btn-outline">${t('common.cancel')}</button>
                        <button type="button" id="repayment-save" class="btn btn-primary">${t('shared.repay-save')}</button>
                    </div>
                </div>
            </div>
        </div>
    `);

    const modal = document.getElementById('repayment-modal');
    const select = document.getElementById('repayment-debt');
    const error = document.getElementById('repayment-error');
    let current = null;

    const close = () => {
        modal.classList.add('hidden');
        modal.classList.remove('flex');
        current = null;
    };

    document.getElementById('repayment-cancel').addEventListener('click', close);
    modal.addEventListener('click', (event) => {
        if (event.target.id === 'repayment-modal') close();
    });

    document.getElementById('repayment-save').addEventListener('click', async () => {
        error.classList.add('hidden');
        try {
            await save(current, select.value);
            close();
        } catch (failure) {
            error.textContent = failure.message || t('shared.save-failed');
            error.classList.remove('hidden');
        }
    });

    document.getElementById('repayment-remove').addEventListener('click', async () => {
        if (!remove) return;
        await remove(current);
        close();
    });

    return function open(income, { choices = [], current: selected = null } = {}) {
        current = income;
        error.classList.add('hidden');
        document.getElementById('repayment-summary').textContent = t('shared.summary', {
            company: income.empresa || t('common.unknown'),
            date: formatDate(income.data),
            amount: formatCurrency(income.cost)
        });
        select.innerHTML = choices
            .map(choice => `<option value="${escapeHtml(String(choice.value))}">${escapeHtml(choice.label)}</option>`)
            .join('');
        if (selected !== null) select.value = String(selected);
        document.getElementById('repayment-save').disabled = choices.length === 0;
        document.getElementById('repayment-remove').classList.toggle('hidden', !(remove && selected !== null));
        modal.classList.remove('hidden');
        modal.classList.add('flex');
    };
}
