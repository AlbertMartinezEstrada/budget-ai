import { getBudgets, getBudget, createBudget, updateBudget, deleteBudget, getCategories, formatCurrency, escapeHtml } from '../../api.js';

// Tailwind no pot generar classes construïdes en temps d'execució
// (`bg-${color}-500`), així que s'enumeren senceres.
const BAR_CLASSES = { low: 'bg-green-500', medium: 'bg-orange-500', high: 'bg-red-500' };
const TEXT_CLASSES = { low: 'text-green-600', medium: 'text-orange-600', high: 'text-red-600' };

export async function initBudgets(container) {
    container.innerHTML = `
        <div class="page-header flex justify-between items-center mb-6">
            <h2 class="text-2xl font-bold text-slate-800 dark:text-slate-100">Presupuestos</h2>
            <button id="add-budget-btn" class="bg-primary text-white px-4 py-2 rounded-lg hover:bg-primary/90 flex items-center gap-2">
                <span class="material-symbols-outlined">add</span>
                Nuevo Presupuesto
            </button>
        </div>
        <div id="budgets-list" class="space-y-4"></div>

        <!-- Modal -->
        <div id="budget-modal" class="fixed inset-0 bg-black/50 hidden items-center justify-center z-50">
            <div class="bg-white dark:bg-slate-800 rounded-xl p-6 w-full max-w-md">
                <h3 class="text-xl font-bold mb-4" id="modal-title">Nuevo Presupuesto</h3>
                <form id="budget-form" class="space-y-4">
                    <input type="hidden" id="budget-id">
                    <div>
                        <label class="block text-sm font-medium mb-1">Categoría</label>
                        <select id="budget-category" required class="w-full px-3 py-2 border rounded-lg"></select>
                    </div>
                    <div>
                        <label class="block text-sm font-medium mb-1">Límite</label>
                        <input type="number" id="budget-limit" step="0.01" required class="w-full px-3 py-2 border rounded-lg">
                    </div>
                    <div class="grid grid-cols-2 gap-4">
                        <div>
                            <label class="block text-sm font-medium mb-1">Inicio</label>
                            <input type="date" id="budget-start" required class="w-full px-3 py-2 border rounded-lg">
                        </div>
                        <div>
                            <label class="block text-sm font-medium mb-1">Fin</label>
                            <input type="date" id="budget-end" required class="w-full px-3 py-2 border rounded-lg">
                        </div>
                    </div>
                    <div class="flex gap-2 justify-end">
                        <button type="button" id="cancel-btn" class="px-4 py-2 border rounded-lg hover:bg-gray-50 dark:hover:bg-slate-700">Cancelar</button>
                        <button type="submit" class="bg-primary text-white px-4 py-2 rounded-lg hover:bg-primary/90">Guardar</button>
                    </div>
                </form>
            </div>
        </div>
    `;

    await loadCategories();
    await loadBudgets();

    document.getElementById('add-budget-btn').addEventListener('click', () => openModal());
    document.getElementById('cancel-btn').addEventListener('click', () => closeModal());
    document.getElementById('budget-form').addEventListener('submit', handleSubmit);
    document.getElementById('budgets-list').addEventListener('click', handleListClick);
}

let categories = [];

async function loadCategories() {
    try {
        categories = await getCategories();
        const select = document.getElementById('budget-category');
        select.innerHTML = categories.map(cat => `<option value="${cat.id}">${escapeHtml(cat.nom)}</option>`).join('');
    } catch (error) {
        console.error('Error loading categories:', error);
    }
}

async function loadBudgets() {
    try {
        const budgets = await getBudgets();
        renderBudgets(budgets);
    } catch (error) {
        console.error('Error loading budgets:', error);
        document.getElementById('budgets-list').innerHTML = '<p class="text-red-500">Error al cargar presupuestos</p>';
    }
}

function renderBudgets(budgets) {
    const container = document.getElementById('budgets-list');
    if (!budgets || budgets.length === 0) {
        container.innerHTML = '<p class="text-gray-500 dark:text-slate-400 text-center py-8">No hay presupuestos</p>';
        return;
    }

    container.innerHTML = budgets.map(budget => {
        const spent = Number.parseFloat(budget.gasto_actual) || 0;
        const limit = Number.parseFloat(budget.quantitat_limit) || 0;
        const percent = limit > 0 ? Math.min((spent / limit) * 100, 100) : 0;
        const level = percent > 90 ? 'high' : percent > 70 ? 'medium' : 'low';

        return `
            <div class="bg-white dark:bg-slate-800 rounded-xl p-4 shadow-sm border border-slate-200 dark:border-slate-700">
                <div class="flex justify-between items-start mb-3">
                    <div>
                        <h3 class="font-semibold text-lg">${escapeHtml(budget.category?.nom || 'Sin categoría')}</h3>
                        <span class="text-xs text-gray-500 dark:text-slate-400">${escapeHtml(budget.periode_inici)} - ${escapeHtml(budget.periode_fi)}</span>
                    </div>
                    <div class="flex gap-1">
                        <button data-action="edit" data-id="${budget.id}" class="p-1 hover:bg-gray-100 dark:hover:bg-slate-700 rounded" title="Editar">
                            <span class="material-symbols-outlined text-sm">edit</span>
                        </button>
                        <button data-action="delete" data-id="${budget.id}" class="p-1 hover:bg-red-50 text-red-500 rounded" title="Eliminar">
                            <span class="material-symbols-outlined text-sm">delete</span>
                        </button>
                    </div>
                </div>
                <div class="mb-2">
                    <div class="flex justify-between text-sm mb-1">
                        <span class="text-gray-600 dark:text-slate-300">${formatCurrency(spent)} gastado</span>
                        <span class="font-medium">${formatCurrency(limit)} límite</span>
                    </div>
                    <div class="h-2 bg-gray-200 dark:bg-slate-700 rounded-full overflow-hidden">
                        <div class="h-full ${BAR_CLASSES[level]} transition-all" style="width: ${percent}%"></div>
                    </div>
                </div>
                <div class="text-sm ${TEXT_CLASSES[level]} font-medium">${percent.toFixed(0)}% utilizado</div>
            </div>
        `;
    }).join('');
}

function openModal(budget = null) {
    const modal = document.getElementById('budget-modal');
    const title = document.getElementById('modal-title');
    const form = document.getElementById('budget-form');

    const today = new Date().toISOString().split('T')[0];
    document.getElementById('budget-start').value = today;
    document.getElementById('budget-end').value = today;

    if (budget) {
        title.textContent = 'Editar Presupuesto';
        document.getElementById('budget-id').value = budget.id;
        document.getElementById('budget-category').value = budget.category?.id;
        document.getElementById('budget-limit').value = budget.quantitat_limit;
        document.getElementById('budget-start').value = budget.periode_inici;
        document.getElementById('budget-end').value = budget.periode_fi;
    } else {
        title.textContent = 'Nuevo Presupuesto';
        form.reset();
        document.getElementById('budget-id').value = '';
    }

    modal.classList.remove('hidden');
    modal.classList.add('flex');
}

function closeModal() {
    const modal = document.getElementById('budget-modal');
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
            // Abans això cridava http://localhost:8000 a pèl, de manera que
            // només funcionava obrint l'aplicació des de la mateixa màquina.
            openModal(await getBudget(id));
        } catch (error) {
            alert(error.message || 'Error al cargar presupuesto');
        }
        return;
    }

    if (button.dataset.action === 'delete') {
        if (!confirm('¿Eliminar este presupuesto?')) return;
        try {
            await deleteBudget(id);
            await loadBudgets();
        } catch (error) {
            alert(error.message || 'Error al eliminar presupuesto');
        }
    }
}

async function handleSubmit(e) {
    e.preventDefault();
    const id = document.getElementById('budget-id').value;
    const data = {
        category: { id: parseInt(document.getElementById('budget-category').value) },
        quantitat_limit: parseFloat(document.getElementById('budget-limit').value),
        periode_inici: document.getElementById('budget-start').value,
        periode_fi: document.getElementById('budget-end').value
    };

    try {
        if (id) {
            await updateBudget(parseInt(id), data);
        } else {
            await createBudget(data);
        }
        closeModal();
        await loadBudgets();
    } catch (error) {
        alert('Error al guardar presupuesto');
    }
}