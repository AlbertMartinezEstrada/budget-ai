import { getGoals, createGoal, updateGoal, deleteGoal, addAmountToGoal } from '../../api.js';

export async function initGoals(container) {
    container.innerHTML = `
        <div class="page-header flex justify-between items-center mb-6">
            <h2 class="text-2xl font-bold text-slate-800">Objectius Financers</h2>
            <button id="add-goal-btn" class="bg-primary text-white px-4 py-2 rounded-lg hover:bg-primary/90 flex items-center gap-2">
                <span class="material-symbols-outlined">add</span>
                Nou Objectiu
            </button>
        </div>
        <div id="goals-list" class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
            <div class="text-center py-8 text-slate-500">Carregant...</div>
        </div>

        <!-- Modal -->
        <div id="goal-modal" class="fixed inset-0 bg-black/50 hidden items-center justify-center z-50">
            <div class="bg-white rounded-xl p-6 w-full max-w-md">
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
                        <button type="button" id="cancel-btn" class="px-4 py-2 border rounded-lg hover:bg-gray-50">Cancelar</button>
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
        container.innerHTML = '<div class="text-center py-8 text-slate-500">No hi ha objectius</div>';
        return;
    }

    container.innerHTML = goals.map(goal => {
        const progress = goal.target_amount > 0 ? (goal.current_amount / goal.target_amount * 100) : 0;
        return `
            <div class="bg-white rounded-xl p-4 shadow-sm border">
                <div class="flex justify-between items-start mb-2">
                    <h3 class="font-semibold">${goal.name}</h3>
                    <button onclick="deleteGoal(${goal.id})" class="text-red-500 hover:text-red-700">
                        <span class="material-symbols-outlined">delete</span>
                    </button>
                </div>
                <div class="text-2xl font-bold text-primary mb-2">${goal.current_amount}€ / ${goal.target_amount}€</div>
                <div class="w-full bg-gray-200 rounded-full h-2 mb-2">
                    <div class="bg-primary h-2 rounded-full" style="width: ${Math.min(progress, 100)}%"></div>
                </div>
                <div class="text-sm text-slate-500">${progress.toFixed(1)}% completat</div>
                ${goal.deadline ? `<div class="text-xs text-slate-400 mt-1">Data: ${goal.deadline}</div>` : ''}
                <button onclick="openAddAmountModal(${goal.id})" class="mt-2 text-sm text-primary hover:underline">Afegir quantitat</button>
            </div>
        `;
    }).join('');
}

function openModal(goal = null) {
    document.getElementById('modal-title').textContent = goal ? 'Editar Objectiu' : 'Nou Objectiu';
    document.getElementById('goal-id').value = goal?.id || '';
    document.getElementById('goal-name').value = goal?.name || '';
    document.getElementById('goal-target').value = goal?.target_amount || '';
    document.getElementById('goal-deadline').value = goal?.deadline || '';
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
    const data = {
        name: document.getElementById('goal-name').value,
        target_amount: parseFloat(document.getElementById('goal-target').value),
        deadline: document.getElementById('goal-deadline').value || null
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

window.deleteGoal = async function(id) {
    if (confirm('Segur que vols eliminar aquest objectiu?')) {
        try {
            await deleteGoal(id);
            loadGoals();
        } catch (error) {
            alert('Error eliminant objectiu: ' + error.message);
        }
    }
};

window.openAddAmountModal = function(id) {
    const amount = prompt('Quantitat a afegir:');
    if (amount && parseFloat(amount) > 0) {
        addAmountToGoal(id, parseFloat(amount)).then(() => loadGoals()).catch(err => alert(err.message));
    }
};