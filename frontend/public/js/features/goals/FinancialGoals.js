import { getGoals, createGoal, updateGoal, deleteGoal, addAmountToGoal, formatCurrency, escapeHtml } from '../../api.js';

export async function initGoals(container) {
    container.innerHTML = `
        <div class="page-header flex justify-between items-center mb-6">
            <h2 class="text-2xl font-bold text-slate-800 dark:text-slate-100">Objectius Financers</h2>
            <button id="add-goal-btn" class="bg-primary text-white px-4 py-2 rounded-lg hover:bg-primary/90 flex items-center gap-2">
                <span class="material-symbols-outlined">add</span>
                Nou Objectiu
            </button>
        </div>
        <div id="goals-list" class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
            <div class="text-center py-8 text-slate-500 dark:text-slate-400">Carregant...</div>
        </div>

        <!-- Modal -->
        <div id="goal-modal" class="fixed inset-0 bg-black/50 hidden items-center justify-center z-50">
            <div class="bg-white dark:bg-slate-800 rounded-xl p-6 w-full max-w-md">
                <h3 class="text-xl font-bold mb-4" id="modal-title">Nou Objectiu</h3>
                <form id="goal-form" class="space-y-4">
                    <input type="hidden" id="goal-id">
                    <div>
                        <label class="block text-sm font-medium mb-1">Nom</label>
                        <input type="text" id="goal-name" required class="w-full px-3 py-2 border rounded-lg">
                    </div>
                    <div>
                        <label class="block text-sm font-medium mb-1">Quantitat Objectiu</label>
                        <input type="number" id="goal-target" step="0.01" required class="w-full px-3 py-2 border rounded-lg">
                    </div>
                    <div>
                        <label class="block text-sm font-medium mb-1">Data Target</label>
                        <input type="date" id="goal-deadline" class="w-full px-3 py-2 border rounded-lg">
                    </div>
                    <div class="flex gap-2 justify-end">
                        <button type="button" id="cancel-btn" class="px-4 py-2 border rounded-lg hover:bg-gray-50 dark:hover:bg-slate-700">Cancelar</button>
                        <button type="submit" class="bg-primary text-white px-4 py-2 rounded-lg hover:bg-primary/90">Guardar</button>
                    </div>
                </form>
            </div>
        </div>
    `;

    loadGoals();

    document.getElementById('add-goal-btn').addEventListener('click', () => openModal());
    document.getElementById('cancel-btn').addEventListener('click', closeModal);
    document.getElementById('goal-modal').addEventListener('click', (e) => {
        if (e.target.id === 'goal-modal') closeModal();
    });
    document.getElementById('goal-form').addEventListener('submit', handleSubmit);
    document.getElementById('goals-list').addEventListener('click', handleListClick);
}

async function loadGoals() {
    try {
        const goals = await getGoals();
        renderGoals(goals);
    } catch (error) {
        console.error('Error carregant objectius:', error);
        document.getElementById('goals-list').innerHTML = `
            <div class="text-center py-8 text-red-500">Error carregant objectius</div>
        `;
    }
}

function renderGoals(goals) {
    const container = document.getElementById('goals-list');
    if (!goals || goals.length === 0) {
        container.innerHTML = '<div class="text-center py-8 text-slate-500 dark:text-slate-400">No hi ha objectius</div>';
        return;
    }

    container.innerHTML = goals.map(goal => {
        const target = goal.quantitat_objectiu || 0;
        const current = goal.quantitat_actual || 0;
        const progress = target > 0 ? (current / target * 100) : 0;
        return `
            <div class="bg-white dark:bg-slate-800 rounded-xl p-4 shadow-sm border">
                <div class="flex justify-between items-start mb-2">
                    <h3 class="font-semibold">${escapeHtml(goal.nom)}</h3>
                    <button data-action="delete" data-id="${goal.id}" class="text-red-500 hover:text-red-700">
                        <span class="material-symbols-outlined">delete</span>
                    </button>
                </div>
                <div class="text-2xl font-bold text-primary mb-2">${formatCurrency(current)} / ${formatCurrency(target)}</div>
                <div class="w-full bg-gray-200 dark:bg-slate-700 rounded-full h-2 mb-2">
                    <div class="bg-primary h-2 rounded-full" style="width: ${Math.min(progress, 100)}%"></div>
                </div>
                <div class="text-sm text-slate-500 dark:text-slate-400">${progress.toFixed(1)}% completat</div>
                ${goal.data_objectiu ? `<div class="text-xs text-slate-400 mt-1">Data: ${escapeHtml(goal.data_objectiu)}</div>` : ''}
                <button data-action="add-amount" data-id="${goal.id}" class="mt-2 text-sm text-primary hover:underline">Afegir quantitat</button>
            </div>
        `;
    }).join('');
}

function openModal(goal = null) {
    document.getElementById('modal-title').textContent = goal ? 'Editar Objectiu' : 'Nou Objectiu';
    document.getElementById('goal-id').value = goal?.id || '';
    document.getElementById('goal-name').value = goal?.nom || '';
    document.getElementById('goal-target').value = goal?.quantitat_objectiu || '';
    document.getElementById('goal-deadline').value = goal?.data_objectiu || '';
    document.getElementById('goal-modal').classList.remove('hidden');
    document.getElementById('goal-modal').classList.add('flex');
}

function closeModal() {
    document.getElementById('goal-modal').classList.add('hidden');
    document.getElementById('goal-modal').classList.remove('flex');
}

async function handleSubmit(e) {
    e.preventDefault();
    const id = document.getElementById('goal-id').value;
    // Els noms han de coincidir amb els @JsonProperty de FinancialGoal:
    // "nom" i "quantitat_objectiu" són NOT NULL a la base de dades.
    const data = {
        nom: document.getElementById('goal-name').value,
        quantitat_objectiu: parseFloat(document.getElementById('goal-target').value),
        data_objectiu: document.getElementById('goal-deadline').value || null
    };

    try {
        if (id) {
            await updateGoal(parseInt(id), data);
        } else {
            await createGoal(data);
        }
        closeModal();
        loadGoals();
    } catch (error) {
        alert('Error guardant objectiu: ' + error.message);
    }
}

async function handleListClick(event) {
    const button = event.target.closest('button[data-action]');
    if (!button) return;

    const id = Number.parseInt(button.dataset.id, 10);
    if (!Number.isInteger(id)) return;

    if (button.dataset.action === 'delete') {
        if (!confirm('Segur que vols eliminar aquest objectiu?')) return;
        try {
            await deleteGoal(id);
            await loadGoals();
        } catch (error) {
            alert('Error eliminant objectiu: ' + error.message);
        }
        return;
    }

    if (button.dataset.action === 'add-amount') {
        const input = prompt('Quantitat a afegir:');
        if (input === null) return;

        const amount = Number.parseFloat(input.replace(',', '.'));
        if (!Number.isFinite(amount) || amount <= 0) {
            alert('Introdueix una quantitat positiva.');
            return;
        }

        try {
            await addAmountToGoal(id, amount);
            await loadGoals();
        } catch (error) {
            alert(error.message || 'Error afegint la quantitat');
        }
    }
}