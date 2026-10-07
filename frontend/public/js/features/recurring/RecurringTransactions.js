import { getRecurringTransactions, getRecurringTransaction, createRecurringTransaction, updateRecurringTransaction, deleteRecurringTransaction, processRecurring, getCategories, formatCurrency, escapeHtml } from '../../api.js';
import {
    categoryOptions, leafCategories, sectionOfCategory, EXPENSE_SECTIONS, INCOME_SECTIONS
} from '../../categoryOptions.js';

export async function initRecurring(container) {
    container.innerHTML = `
        <div class="page-header flex justify-between items-center mb-6">
            <h2 class="text-2xl font-bold text-slate-800 dark:text-slate-100">Transacciones Recurrentes</h2>
            <div class="flex gap-2">
                <button id="process-btn" class="bg-green-600 text-white px-4 py-2 rounded-lg hover:bg-green-600/90 flex items-center gap-2">
                    <span class="material-symbols-outlined">play_arrow</span>
                    Procesar
                </button>
                <button id="add-recurring-btn" class="bg-primary text-white px-4 py-2 rounded-lg hover:bg-primary/90 flex items-center gap-2">
                    <span class="material-symbols-outlined">add</span>
                    Nueva
                </button>
            </div>
        </div>
        <div id="recurring-list" class="space-y-4"></div>

        <!-- Modal -->
        <div id="recurring-modal" class="fixed inset-0 bg-black/50 hidden items-center justify-center z-50">
            <div class="bg-white dark:bg-slate-800 rounded-xl p-6 w-full max-w-md">
                <h3 class="text-xl font-bold mb-4" id="modal-title">Nueva Transacción Recurrente</h3>
                <form id="recurring-form" class="space-y-4">
                    <input type="hidden" id="recurring-id">
                    <div>
                        <label class="block text-sm font-medium mb-1">Nombre</label>
                        <input type="text" id="recurring-name" required class="w-full px-3 py-2 border rounded-lg">
                    </div>
                    <div>
                        <label class="block text-sm font-medium mb-1">Tipo</label>
                        <select id="recurring-type" required class="w-full px-3 py-2 border rounded-lg">
                            <option value="EXPENSE">Gasto</option>
                            <option value="INCOME">Ingreso</option>
                        </select>
                    </div>
                    <div>
                        <label class="block text-sm font-medium mb-1">Cantidad</label>
                        <input type="number" id="recurring-amount" step="0.01" required class="w-full px-3 py-2 border rounded-lg">
                    </div>
                    <div>
                        <label class="block text-sm font-medium mb-1">Frecuencia</label>
                        <select id="recurring-frequency" required class="w-full px-3 py-2 border rounded-lg">
                            <option value="DIARIA">Diaria</option>
                            <option value="SETMANAL">Semanal</option>
                            <option value="MENSUAL">Mensual</option>
                            <option value="TRIMESTRAL">Trimestral</option>
                            <option value="ANUAL">Anual</option>
                        </select>
                    </div>
                    <div>
                        <label class="block text-sm font-medium mb-1">Próxima Fecha</label>
                        <input type="date" id="recurring-next-date" required class="w-full px-3 py-2 border rounded-lg">
                    </div>
                    <div>
                        <label class="block text-sm font-medium mb-1">Categoría</label>
                        <select id="recurring-category" required class="w-full px-3 py-2 border rounded-lg"></select>
                    </div>
                    <div>
                        <label class="block text-sm font-medium mb-1">Descripción</label>
                        <textarea id="recurring-description" class="w-full px-3 py-2 border rounded-lg" rows="2"></textarea>
                    </div>
                    <div class="flex gap-2 justify-end">
                        <button type="button" id="cancel-btn" class="px-4 py-2 border rounded-lg hover:bg-gray-50 dark:hover:bg-slate-700">Cancelar</button>
                        <button type="submit" class="bg-primary text-white px-4 py-2 rounded-lg hover:bg-primary/90">Guardar</button>
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
    document.getElementById('recurring-category').innerHTML = '<option value="">— Elige una categoría —</option>'
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
        document.getElementById('recurring-list').innerHTML = '<p class="text-red-500">Error al cargar transacciones recurrentes</p>';
    }
}

function renderRecurrings(recurrings) {
    const container = document.getElementById('recurring-list');
    if (!recurrings || recurrings.length === 0) {
        container.innerHTML = '<p class="text-gray-500 dark:text-slate-400 text-center py-8">No hay transacciones recurrentes</p>';
        return;
    }

    const freqLabels = {
        'DIARIA': 'Diaria',
        'SETMANAL': 'Semanal',
        'MENSUAL': 'Mensual',
        'TRIMESTRAL': 'Trimestral',
        'ANUAL': 'Anual'
    };

    container.innerHTML = recurrings.map(item => `
        <div class="bg-white dark:bg-slate-800 rounded-xl p-4 shadow-sm border border-slate-200 dark:border-slate-700">
            <div class="flex items-center justify-between mb-3">
                <div class="flex items-center gap-3">
                    <div class="w-10 h-10 rounded-lg flex items-center justify-center ${item.tipus === 'INCOME' ? 'bg-green-100' : 'bg-red-100'}">
                        <span class="material-symbols-outlined ${item.tipus === 'INCOME' ? 'text-green-600' : 'text-red-600'}">${item.tipus === 'INCOME' ? 'trending_up' : 'trending_down'}</span>
                    </div>
                    <div>
                        <h3 class="font-semibold">${escapeHtml(item.nom)}</h3>
                        <span class="text-xs text-gray-500 dark:text-slate-400">${escapeHtml(freqLabels[item.frequencia] || item.frequencia)} • ${escapeHtml(item.proxima_data)}</span>
                    </div>
                </div>
                <div class="flex gap-1">
                    <button data-action="edit" data-id="${item.id}" class="p-1 hover:bg-gray-100 dark:hover:bg-slate-700 rounded" title="Editar">
                        <span class="material-symbols-outlined text-sm">edit</span>
                    </button>
                    <button data-action="delete" data-id="${item.id}" class="p-1 hover:bg-red-50 text-red-500 rounded" title="Eliminar">
                        <span class="material-symbols-outlined text-sm">delete</span>
                    </button>
                </div>
            </div>
            <div class="flex justify-between items-center">
                <span class="text-xl font-bold ${item.tipus === 'INCOME' ? 'text-green-600' : 'text-red-600'}">
                    ${item.tipus === 'INCOME' ? '+' : '-'}${formatCurrency(item.import)}
                </span>
                <span class="text-xs ${item.activa ? 'text-green-600' : 'text-gray-400'}">${item.activa ? 'Activa' : 'Inactiva'}</span>
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
                ${escapeHtml(item.category.nom)} · cuenta ${formatCurrency(item.prorrateig_mensual)} al mes en el presupuesto
            </p>`;
}

function whyItDoesNotCount(item) {
    if (!item.activa) return 'Inactiva: no cuenta en el presupuesto.';
    if (!item.category) return 'Sin categoría: no cuenta en el presupuesto. Edítala y elige una.';
    // Sense les categories carregades no es pot saber: val més no alarmar.
    if (categories.length === 0) return null;

    const name = item.category.nom;
    if (!leafCategories(categories).some(category => category.id === item.category.id)) {
        return `«${name}» es un bloque: no cuenta en el presupuesto. Edítala y elige una de sus subcategorías.`;
    }
    const isIncomeCategory = sectionOfCategory(categories, item.category.id) === 'INCOME';
    if (isIncomeCategory !== (item.tipus === 'INCOME')) {
        return isIncomeCategory
            ? `«${name}» es de ingresos: un gasto ahí no cuenta en el presupuesto. Edítala y cambia la categoría.`
            : `«${name}» es de gastos: un ingreso ahí no cuenta en el presupuesto. Edítala y cambia la categoría.`;
    }
    return null;
}

function openModal(recurring = null) {
    const modal = document.getElementById('recurring-modal');
    const title = document.getElementById('modal-title');
    const form = document.getElementById('recurring-form');

    const today = new Date().toISOString().split('T')[0];

    if (recurring) {
        title.textContent = 'Editar Transacción Recurrente';
        document.getElementById('recurring-id').value = recurring.id;
        document.getElementById('recurring-name').value = recurring.nom;
        document.getElementById('recurring-type').value = recurring.tipus;
        document.getElementById('recurring-amount').value = recurring.import;
        document.getElementById('recurring-frequency').value = recurring.frequencia;
        document.getElementById('recurring-next-date').value = recurring.proxima_data;
        fillCategorySelect(recurring.tipus, recurring.category?.id);
        document.getElementById('recurring-description').value = recurring.descripcio || '';
    } else {
        title.textContent = 'Nueva Transacción Recurrente';
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
            alert(error.message || 'Error al cargar transacción');
        }
        return;
    }

    if (button.dataset.action === 'delete') {
        if (!confirm('¿Eliminar esta transacción recurrente?')) return;
        try {
            await deleteRecurringTransaction(id);
            await loadRecurrings();
        } catch (error) {
            alert(error.message || 'Error al eliminar');
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
        alert(error.message || 'Error al guardar');
    }
}

async function handleProcess() {
    try {
        const result = await processRecurring();
        alert(`Procesadas ${result.length || 0} transacciones`);
        await loadRecurrings();
    } catch (error) {
        alert('Error al procesar');
    }
}