import { getRecurringTransactions, getRecurringTransaction, createRecurringTransaction, updateRecurringTransaction, deleteRecurringTransaction, processRecurring, getCategories, formatCurrency, escapeHtml } from '../../api.js';
import {
    categoryOptions, leafCategories, sectionOfCategory, EXPENSE_SECTIONS, INCOME_SECTIONS
} from '../../categoryOptions.js';
import { t, formatDate } from '../../i18n.js';

/** El nom de cada freqüència; el codi és el que desa el backend. */
const FREQUENCY_LABELS = {
    DIARIA: 'frequency.daily',
    SETMANAL: 'frequency.weekly',
    MENSUAL: 'frequency.monthly',
    TRIMESTRAL: 'frequency.quarterly',
    ANUAL: 'frequency.yearly'
};

const frequencyLabel = (frequency) => FREQUENCY_LABELS[frequency] ? t(FREQUENCY_LABELS[frequency]) : (frequency || '');

export async function initRecurring(container) {
    container.innerHTML = `
        <div class="page-header flex justify-between items-center mb-6">
            <h2 class="text-2xl font-bold text-slate-800 dark:text-slate-100">${t('nav.recurring')}</h2>
            <div class="flex gap-2">
                <button id="process-btn" class="bg-green-700 text-white px-4 py-2 rounded-2xl hover:bg-green-800 flex items-center gap-2">
                    <span class="material-symbols-outlined">play_arrow</span>
                    ${t('recurring.process')}
                </button>
                <button id="add-recurring-btn" class="bg-primary text-white px-4 py-2 rounded-2xl hover:bg-primary/90 flex items-center gap-2">
                    <span class="material-symbols-outlined">add</span>
                    ${t('recurring.add')}
                </button>
            </div>
        </div>
        <div id="recurring-list" class="space-y-4"></div>

        <!-- Modal -->
        <div id="recurring-modal" class="fixed inset-0 bg-black/50 hidden items-center justify-center z-50">
            <div class="bg-white dark:bg-slate-800 rounded-3xl p-6 w-full max-w-md">
                <h3 class="text-xl font-bold mb-4" id="modal-title">${t('recurring.add-title')}</h3>
                <form id="recurring-form" class="space-y-4">
                    <input type="hidden" id="recurring-id">
                    <div>
                        <label class="block text-sm font-medium mb-1" for="recurring-name">${t('common.name')}</label>
                        <input type="text" id="recurring-name" required class="w-full px-3 py-2 border rounded-2xl">
                    </div>
                    <div>
                        <label class="block text-sm font-medium mb-1" for="recurring-type">${t('common.type')}</label>
                        <select id="recurring-type" required class="w-full px-3 py-2 border rounded-2xl">
                            <option value="EXPENSE">${t('common.expense')}</option>
                            <option value="INCOME">${t('common.income')}</option>
                        </select>
                    </div>
                    <div>
                        <label class="block text-sm font-medium mb-1" for="recurring-amount">${t('common.amount')}</label>
                        <input type="number" id="recurring-amount" step="0.01" required class="w-full px-3 py-2 border rounded-2xl">
                    </div>
                    <div>
                        <label class="block text-sm font-medium mb-1" for="recurring-frequency">${t('recurring.frequency')}</label>
                        <select id="recurring-frequency" required class="w-full px-3 py-2 border rounded-2xl">
                            ${Object.keys(FREQUENCY_LABELS).map(frequency => `<option value="${frequency}">${frequencyLabel(frequency)}</option>`).join('')}
                        </select>
                    </div>
                    <div>
                        <label class="block text-sm font-medium mb-1" for="recurring-next-date">${t('recurring.next-date')}</label>
                        <input type="date" id="recurring-next-date" required class="w-full px-3 py-2 border rounded-2xl">
                    </div>
                    <div>
                        <label class="block text-sm font-medium mb-1" for="recurring-category">${t('common.category')}</label>
                        <select id="recurring-category" required class="w-full px-3 py-2 border rounded-2xl"></select>
                    </div>
                    <div>
                        <label class="block text-sm font-medium mb-1" for="recurring-description">${t('common.description')}</label>
                        <textarea id="recurring-description" class="w-full px-3 py-2 border rounded-2xl" rows="2"></textarea>
                    </div>
                    <div class="flex gap-2 justify-end">
                        <button type="button" id="cancel-btn" class="px-4 py-2 border rounded-2xl hover:bg-gray-50 dark:hover:bg-slate-700">${t('common.cancel')}</button>
                        <button type="submit" class="bg-primary text-white px-4 py-2 rounded-2xl hover:bg-primary/90">${t('common.save')}</button>
                    </div>
                </form>
            </div>
        </div>
    `;

    await loadCategoriesSelect();
    await loadRecurrings();

    document.getElementById('add-recurring-btn').addEventListener('click', () => openModal());
    document.getElementById('cancel-btn').addEventListener('click', () => closeModal());
    document.getElementById('recurring-form').addEventListener('submit', handleSubmit);
    document.getElementById('recurring-type').addEventListener('change', (event) => {
        fillCategorySelect(event.target.value, document.getElementById('recurring-category').value);
    });
    document.getElementById('process-btn').addEventListener('click', handleProcess);
    document.getElementById('recurring-list').addEventListener('click', handleListClick);
}

let categories = [];

async function loadCategoriesSelect() {
    try {
        categories = await getCategories();
        fillCategorySelect(document.getElementById('recurring-type').value);
    } catch (error) {
        console.error('Error loading categories:', error);
    }
}

/**
 * Les categories que té sentit donar a un recurrent d'aquest tipus.
 *
 * Una despesa en una categoria d'ingressos no comptaria enlloc: allà el
 * pressupost mira el que entra. I al revés, igual.
 *
 * La categoria és obligatòria: sense, el recurrent no compta al pressupost. La
 * primera opció és buida perquè, si el recurrent en tenia una que ja no
 * s'ofereix (un bloc), el desplegable no en triï una altra sense dir res.
 */
function fillCategorySelect(type, selected = null) {
    document.getElementById('recurring-category').innerHTML = `<option value="">${t('common.choose-category')}</option>`
        + categoryOptions(categories, {
            sections: type === 'INCOME' ? INCOME_SECTIONS : EXPENSE_SECTIONS,
            selected
        });
}

async function loadRecurrings() {
    try {
        const recurrings = await getRecurringTransactions();
        renderRecurrings(recurrings);
    } catch (error) {
        console.error('Error loading recurring transactions:', error);
        document.getElementById('recurring-list').innerHTML = `<p class="text-red-600">${t('recurring.load-failed')}</p>`;
    }
}

function renderRecurrings(recurrings) {
    const container = document.getElementById('recurring-list');
    if (!recurrings || recurrings.length === 0) {
        container.innerHTML = `<p class="text-gray-500 dark:text-slate-400 text-center py-8">${t('recurring.empty')}</p>`;
        return;
    }

    container.innerHTML = recurrings.map(item => `
        <div class="bg-white dark:bg-slate-800 rounded-3xl p-4 shadow-xs border border-slate-200 dark:border-slate-700">
            <div class="flex items-center justify-between mb-3">
                <div class="flex items-center gap-3">
                    <div class="w-10 h-10 rounded-2xl flex items-center justify-center ${item.tipus === 'INCOME' ? 'bg-green-100' : 'bg-red-100'}">
                        <span class="material-symbols-outlined ${item.tipus === 'INCOME' ? 'text-green-700' : 'text-red-600'}">${item.tipus === 'INCOME' ? 'trending_up' : 'trending_down'}</span>
                    </div>
                    <div>
                        <h3 class="font-semibold">${escapeHtml(item.nom)}</h3>
                        <span class="text-xs text-gray-500 dark:text-slate-400">${escapeHtml(frequencyLabel(item.frequencia))} • ${formatDate(item.proxima_data)}</span>
                    </div>
                </div>
                <div class="flex gap-1">
                    <button data-action="edit" data-id="${item.id}" class="p-1 hover:bg-gray-100 dark:hover:bg-slate-700 rounded-lg" title="${t('common.edit')}">
                        <span class="material-symbols-outlined text-sm">edit</span>
                    </button>
                    <button data-action="delete" data-id="${item.id}" class="p-1 hover:bg-red-50 text-red-600 rounded-lg" title="${t('common.delete')}">
                        <span class="material-symbols-outlined text-sm">delete</span>
                    </button>
                </div>
            </div>
            <div class="flex justify-between items-center">
                <span class="text-xl font-bold ${item.tipus === 'INCOME' ? 'text-green-700' : 'text-red-600'}">
                    ${item.tipus === 'INCOME' ? '+' : '-'}${formatCurrency(item.import)}
                </span>
                <span class="text-xs ${item.activa ? 'text-green-700' : 'text-gray-500'}">${t(item.activa ? 'recurring.active' : 'recurring.inactive')}</span>
            </div>
            ${budgetLine(item)}
        </div>
    `).join('');
}

/**
 * Com compta el recurrent al pressupost.
 *
 * Els recurrents i el pressupost semblaven dues coses sense relació: es posava
 * un import aquí i no se sabia si allà sortia. I n'hi havia que no hi sortien
 * mai —sense categoria, en un bloc, de l'altre sentit o desactivats en
 * editar-los— sense que res ho digués. El backend ja no n'accepta de nous,
 * però els d'abans hi són: la targeta diu què els passa.
 */
function budgetLine(item) {
    const problem = whyItDoesNotCount(item);
    if (problem) {
        return `<p class="text-xs mt-2 text-amber-800 dark:text-amber-300">${escapeHtml(problem)}</p>`;
    }
    return `<p class="text-xs mt-2 text-gray-500 dark:text-slate-400">
                ${escapeHtml(t('recurring.counts', { category: item.category.nom, amount: formatCurrency(item.prorrateig_mensual) }))}
            </p>`;
}

function whyItDoesNotCount(item) {
    if (!item.activa) return t('recurring.problem-inactive');
    if (!item.category) return t('recurring.problem-no-category');
    // Sense les categories carregades no es pot saber: val més no alarmar.
    if (categories.length === 0) return null;

    const name = item.category.nom;
    if (!leafCategories(categories).some(category => category.id === item.category.id)) {
        return t('recurring.problem-block', { name });
    }
    const isIncomeCategory = sectionOfCategory(categories, item.category.id) === 'INCOME';
    if (isIncomeCategory !== (item.tipus === 'INCOME')) {
        return t(isIncomeCategory ? 'recurring.problem-income-category' : 'recurring.problem-expense-category', { name });
    }
    return null;
}

function openModal(recurring = null) {
    const modal = document.getElementById('recurring-modal');
    const title = document.getElementById('modal-title');
    const form = document.getElementById('recurring-form');

    const today = new Date().toISOString().split('T')[0];

    if (recurring) {
        title.textContent = t('recurring.edit-title');
        document.getElementById('recurring-id').value = recurring.id;
        document.getElementById('recurring-name').value = recurring.nom;
        document.getElementById('recurring-type').value = recurring.tipus;
        document.getElementById('recurring-amount').value = recurring.import;
        document.getElementById('recurring-frequency').value = recurring.frequencia;
        document.getElementById('recurring-next-date').value = recurring.proxima_data;
        fillCategorySelect(recurring.tipus, recurring.category?.id);
        document.getElementById('recurring-description').value = recurring.descripcio || '';
    } else {
        title.textContent = t('recurring.add-title');
        form.reset();
        document.getElementById('recurring-id').value = '';
        // Després del reset: abans la data es posava primer i el reset la
        // tornava a deixar buida.
        document.getElementById('recurring-next-date').value = today;
        fillCategorySelect(document.getElementById('recurring-type').value);
    }

    modal.classList.remove('hidden');
    modal.classList.add('flex');
}

function closeModal() {
    const modal = document.getElementById('recurring-modal');
    modal.classList.add('hidden');
    modal.classList.remove('flex');
}

async function handleListClick(event) {
    const button = event.target.closest('button[data-action]');
    if (!button) return;

    const id = Number.parseInt(button.dataset.id, 10);
    if (!Number.isInteger(id)) return;

    if (button.dataset.action === 'edit') {
        try {
            // Abans es cridava http://localhost:8000 a pèl.
            openModal(await getRecurringTransaction(id));
        } catch (error) {
            alert(error.message || t('recurring.load-one-failed'));
        }
        return;
    }

    if (button.dataset.action === 'delete') {
        if (!confirm(t('recurring.delete-confirm'))) return;
        try {
            await deleteRecurringTransaction(id);
            await loadRecurrings();
        } catch (error) {
            alert(error.message || t('recurring.delete-failed'));
        }
    }
}

async function handleSubmit(event) {
    event.preventDefault();
    const id = document.getElementById('recurring-id').value;
    const categoryId = document.getElementById('recurring-category').value;

    const data = {
        nom: document.getElementById('recurring-name').value,
        tipus: document.getElementById('recurring-type').value,
        import: parseFloat(document.getElementById('recurring-amount').value),
        frequencia: document.getElementById('recurring-frequency').value,
        proxima_data: document.getElementById('recurring-next-date').value,
        descripcio: document.getElementById('recurring-description').value,
        ...(categoryId && { category: { id: parseInt(categoryId) } })
    };

    try {
        if (id) {
            await updateRecurringTransaction(parseInt(id), data);
        } else {
            await createRecurringTransaction(data);
        }
        closeModal();
        await loadRecurrings();
    } catch (error) {
        // El backend diu què no quadra (un bloc, una categoria de l'altre
        // sentit…): «Error al guardar» no ajudava a arreglar-ho.
        alert(error.message || t('recurring.save-failed'));
    }
}

async function handleProcess() {
    try {
        const result = await processRecurring();
        alert(t('recurring.processed', { count: result.length || 0 }));
        await loadRecurrings();
    } catch (error) {
        alert(error.message || t('recurring.process-failed'));
    }
}