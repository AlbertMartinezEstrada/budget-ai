import { getRecurringTransactions, createRecurringTransaction, updateRecurringTransaction, deleteRecurringTransaction, processRecurring, getCategories, getCompanies } from '../../api.js';

export async function initRecurring(container) {
    container.innerHTML = `
        <div class="page-header flex justify-between items-center mb-6">
            <h2 class="text-2xl font-bold text-slate-800">Transacciones Recurrentes</h2>
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
            <div class="bg-white rounded-xl p-6 w-full max-w-md">
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
                        <select id="recurring-category" class="w-full px-3 py-2 border rounded-lg"></select>
                    </div>
                    <div>
                        <label class="block text-sm font-medium mb-1">Descripción</label>
                        <textarea id="recurring-description" class="w-full px-3 py-2 border rounded-lg" rows="2"></textarea>
                    </div>
                    <div class="flex gap-2 justify-end">
                        <button type="button" id="cancel-btn" class="px-4 py-2 border rounded-lg hover:bg-gray-50">Cancelar</button>
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
    document.getElementById('process-btn').addEventListener('click', handleProcess);
}

let categories = [];

async function loadCategoriesSelect() {
    try {
        categories = await getCategories();
        const select = document.getElementById('recurring-category');
        select.innerHTML = '<option value="">Sin categoría</option>' + categories.map(cat => `<option value="${cat.id}">${cat.nom}</option>`).join('');
    } catch (error) {
        console.error('Error loading categories:', error);
    }
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
        container.innerHTML = '<p class="text-gray-500 text-center py-8">No hay transacciones recurrentes</p>';
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
        <div class="bg-white rounded-xl p-4 shadow-sm border border-slate-200">
            <div class="flex items-center justify-between mb-3">
                <div class="flex items-center gap-3">
                    <div class="w-10 h-10 rounded-lg flex items-center justify-center ${item.tipus === 'INCOME' ? 'bg-green-100' : 'bg-red-100'}">
                        <span class="material-symbols-outlined ${item.tipus === 'INCOME' ? 'text-green-600' : 'text-red-600'}">${item.tipus === 'INCOME' ? 'trending_up' : 'trending_down'}</span>
                    </div>
                    <div>
                        <h3 class="font-semibold">${item.nom}</h3>
                        <span class="text-xs text-gray-500">${freqLabels[item.frequencia] || item.frequencia} • ${item.proxima_data}</span>
                    </div>
                </div>
                <div class="flex gap-1">
                    <button onclick="editRecurring(${item.id})" class="p-1 hover:bg-gray-100 rounded" title="Editar">
                        <span class="material-symbols-outlined text-sm">edit</span>
                    </button>
                    <button onclick="deleteRecurringById(${item.id})" class="p-1 hover:bg-red-50 text-red-500 rounded" title="Eliminar">
                        <span class="material-symbols-outlined text-sm">delete</span>
                    </button>
                </div>
            </div>
            <div class="flex justify-between items-center">
                <span class="text-xl font-bold ${item.tipus === 'INCOME' ? 'text-green-600' : 'text-red-600'}">
                    ${item.tipus === 'INCOME' ? '+' : '-'}${item.import?.toFixed(2) || '0.00'} €
                </span>
                <span class="text-xs ${item.activa ? 'text-green-600' : 'text-gray-400'}">${item.activa ? 'Activa' : 'Inactiva'}</span>
            </div>
        </div>
    `).join('');
}

function openModal(recurring = null) {
    const modal = document.getElementById('recurring-modal');
    const title = document.getElementById('modal-title');
    const form = document.getElementById('recurring-form');

    const today = new Date().toISOString().split('T')[0];
    document.getElementById('recurring-next-date').value = today;

    if (recurring) {
        title.textContent = 'Editar Transacción Recurrente';
        document.getElementById('recurring-id').value = recurring.id;
        document.getElementById('recurring-name').value = recurring.nom;
        document.getElementById('recurring-type').value = recurring.tipus;
        document.getElementById('recurring-amount').value = recurring.import;
        document.getElementById('recurring-frequency').value = recurring.frequencia;
        document.getElementById('recurring-next-date').value = recurring.proxima_data;
        document.getElementById('recurring-category').value = recurring.category?.id || '';
        document.getElementById('recurring-description').value = recurring.descripcio || '';
    } else {
        title.textContent = 'Nueva Transacción Recurrente';
        form.reset();
        document.getElementById('recurring-id').value = '';
    }

    modal.classList.remove('hidden');
    modal.classList.add('flex');
}

function closeModal() {
    const modal = document.getElementById('recurring-modal');
    modal.classList.add('hidden');
    modal.classList.remove('flex');
}

window.editRecurring = async function(id) {
    try {
        const recurring = await (await fetch(`http://localhost:8000/recurring/${id}`)).json();
        openModal(recurring);
    } catch (error) {
        alert('Error al cargar transacción');
    }
};

window.deleteRecurringById = async function(id) {
    if (confirm('¿Eliminar esta transacción recurrente?')) {
        try {
            await deleteRecurringTransaction(id);
            await loadRecurrings();
        } catch (error) {
            alert('Error al eliminar');
        }
    }
};

async function handleSubmit(e) {
    e.preventDefault();
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
        alert('Error al guardar');
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