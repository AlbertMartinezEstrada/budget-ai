import { getBudgets, getCurrentBudget, createBudget, updateBudget, deleteBudget, getCategories, formatCurrency } from '../../api.js';

export async function initBudgets(container) {
    container.innerHTML = `
        <div class="page-header flex justify-between items-center mb-6">
            <h2 class="text-2xl font-bold text-slate-800">Presupuestos</h2>
            <button id="add-budget-btn" class="bg-primary text-white px-4 py-2 rounded-lg hover:bg-primary/90 flex items-center gap-2">
                <span class="material-symbols-outlined">add</span>
                Nuevo Presupuesto
            </button>
        </div>
        <div id="budgets-list" class="space-y-4"></div>

        <!-- Modal -->
        <div id="budget-modal" class="fixed inset-0 bg-black/50 hidden items-center justify-center z-50">
            <div class="bg-white rounded-xl p-6 w-full max-w-md">
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
                        <button type="button" id="cancel-btn" class="px-4 py-2 border rounded-lg hover:bg-gray-50">Cancelar</button>
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
}

let categories = [];

async function loadCategories() {
    try {
        categories = await getCategories();
        const select = document.getElementById('budget-category');
        select.innerHTML = categories.map(cat => `<option value="${cat.id}">${cat.nom}</option>`).join('');
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
        container.innerHTML = '<p class="text-gray-500 text-center py-8">No hay presupuestos</p>';
        return;
    }

    container.innerHTML = budgets.map(budget => {
        const percent = budget.gasto_actual && budget.quantitat_limit
            ? Math.min((budget.gasto_actual / budget.quantitat_limit) * 100, 100)
            : 0;
        const color = percent > 90 ? 'red' : percent > 70 ? 'orange' : 'green';

        return `
            <div class="bg-white rounded-xl p-4 shadow-sm border border-slate-200">
                <div class="flex justify-between items-start mb-3">
                    <div>
                        <h3 class="font-semibold text-lg">${budget.category?.nom || 'Sin categoría'}</h3>
                        <span class="text-xs text-gray-500">${budget.periode_inici} - ${budget.periode_fi}</span>
                    </div>
                    <div class="flex gap-1">
                        <button onclick="editBudget(${budget.id})" class="p-1 hover:bg-gray-100 rounded" title="Editar">
                            <span class="material-symbols-outlined text-sm">edit</span>
                        </button>
                        <button onclick="deleteBudgetById(${budget.id})" class="p-1 hover:bg-red-50 text-red-500 rounded" title="Eliminar">
                            <span class="material-symbols-outlined text-sm">delete</span>
                        </button>
                    </div>
                </div>
                <div class="mb-2">
                    <div class="flex justify-between text-sm mb-1">
                        <span class="text-gray-600">${formatCurrency(budget.gasto_actual)} gastado</span>
                        <span class="font-medium">${formatCurrency(budget.quantitat_limit)} límite</span>
                    </div>
                    <div class="h-2 bg-gray-200 rounded-full overflow-hidden">
                        <div class="h-full bg-${color}-500 transition-all" style="width: ${percent}%"></div>
                    </div>
                </div>
                <div class="text-sm text-${color}-600 font-medium">${percent.toFixed(0)}% utilizado</div>
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

window.editBudget = async function(id) {
    try {
        const budget = await (await fetch(`http://localhost:8000/budgets/${id}`)).json();
        openModal(budget);
    } catch (error) {
        alert('Error al cargar presupuesto');
    }
};

window.deleteBudgetById = async function(id) {
    if (confirm('¿Eliminar este presupuesto?')) {
        try {
            await deleteBudget(id);
            await loadBudgets();
        } catch (error) {
            alert('Error al eliminar presupuesto');
        }
    }
};

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