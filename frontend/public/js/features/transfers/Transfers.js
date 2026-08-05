import { getTransfers, createTransfer, deleteTransfer, getAccounts, formatCurrency, escapeHtml } from '../../api.js';

export async function initTransfers(container) {
    container.innerHTML = `
        <div class="page-header flex justify-between items-center mb-6">
            <h2 class="text-2xl font-bold text-slate-800 dark:text-slate-100">Transferencias</h2>
            <button id="add-transfer-btn" class="bg-primary text-white px-4 py-2 rounded-lg hover:bg-primary/90 flex items-center gap-2">
                <span class="material-symbols-outlined">add</span>
                Nueva Transferencia
            </button>
        </div>
        <div id="transfers-list" class="space-y-4"></div>

        <!-- Modal -->
        <div id="transfer-modal" class="fixed inset-0 bg-black/50 hidden items-center justify-center z-50">
            <div class="bg-white dark:bg-slate-800 rounded-xl p-6 w-full max-w-md">
                <h3 class="text-xl font-bold mb-4">Nueva Transferencia</h3>
                <form id="transfer-form" class="space-y-4">
                    <div>
                        <label class="block text-sm font-medium mb-1">Cuenta Origen</label>
                        <select id="transfer-source" required class="w-full px-3 py-2 border rounded-lg"></select>
                    </div>
                    <div>
                        <label class="block text-sm font-medium mb-1">Cuenta Destino</label>
                        <select id="transfer-destination" required class="w-full px-3 py-2 border rounded-lg"></select>
                    </div>
                    <div>
                        <label class="block text-sm font-medium mb-1">Cantidad</label>
                        <input type="number" id="transfer-amount" step="0.01" required class="w-full px-3 py-2 border rounded-lg">
                    </div>
                    <div>
                        <label class="block text-sm font-medium mb-1">Fecha</label>
                        <input type="date" id="transfer-date" required class="w-full px-3 py-2 border rounded-lg">
                    </div>
                    <div>
                        <label class="block text-sm font-medium mb-1">Descripción</label>
                        <input type="text" id="transfer-description" class="w-full px-3 py-2 border rounded-lg">
                    </div>
                    <div class="flex gap-2 justify-end">
                        <button type="button" id="cancel-btn" class="px-4 py-2 border rounded-lg hover:bg-gray-50 dark:hover:bg-slate-700">Cancelar</button>
                        <button type="submit" class="bg-primary text-white px-4 py-2 rounded-lg hover:bg-primary/90">Guardar</button>
                    </div>
                </form>
            </div>
        </div>
    `;

    await loadAccountsSelect();
    await loadTransfers();

    const today = new Date().toISOString().split('T')[0];
    document.getElementById('transfer-date').value = today;

    document.getElementById('add-transfer-btn').addEventListener('click', () => openModal());
    document.getElementById('cancel-btn').addEventListener('click', () => closeModal());
    document.getElementById('transfer-form').addEventListener('submit', handleSubmit);
    document.getElementById('transfers-list').addEventListener('click', handleListClick);
}

let accounts = [];

async function loadAccountsSelect() {
    try {
        accounts = await getAccounts();
        const sourceSelect = document.getElementById('transfer-source');
        const destSelect = document.getElementById('transfer-destination');
        const options = accounts.map(acc => `<option value="${acc.id}">${escapeHtml(acc.nom)} (${formatCurrency(acc.saldo_actual)})</option>`).join('');
        sourceSelect.innerHTML = options;
        destSelect.innerHTML = options;
    } catch (error) {
        console.error('Error loading accounts:', error);
    }
}

async function loadTransfers() {
    try {
        const transfers = await getTransfers();
        renderTransfers(transfers);
    } catch (error) {
        console.error('Error loading transfers:', error);
        document.getElementById('transfers-list').innerHTML = '<p class="text-red-500">Error al cargar transferencias</p>';
    }
}

function renderTransfers(transfers) {
    const container = document.getElementById('transfers-list');
    if (!transfers || transfers.length === 0) {
        container.innerHTML = '<p class="text-gray-500 dark:text-slate-400 text-center py-8">No hay transferencias</p>';
        return;
    }

    container.innerHTML = transfers.map(transfer => `
        <div class="bg-white dark:bg-slate-800 rounded-xl p-4 shadow-sm border border-slate-200 dark:border-slate-700 flex items-center justify-between">
            <div class="flex items-center gap-4">
                <div class="w-10 h-10 rounded-full bg-blue-100 flex items-center justify-center">
                    <span class="material-symbols-outlined text-blue-600">swap_horiz</span>
                </div>
                <div>
                    <div class="font-medium">${escapeHtml(transfer.sourceAccount?.nom || 'Origen')} → ${escapeHtml(transfer.destinationAccount?.nom || 'Destino')}</div>
                    <div class="text-sm text-gray-500 dark:text-slate-400">${escapeHtml(transfer.data)} ${transfer.descripcio ? '- ' + escapeHtml(transfer.descripcio) : ''}</div>
                </div>
            </div>
            <div class="flex items-center gap-3">
                <span class="text-lg font-bold text-slate-800 dark:text-slate-100">${formatCurrency(transfer.import)}</span>
                <button data-action="delete" data-id="${transfer.id}" class="p-1 hover:bg-red-50 text-red-500 rounded" title="Eliminar">
                    <span class="material-symbols-outlined text-sm">delete</span>
                </button>
            </div>
        </div>
    `).join('');
}

function openModal() {
    const modal = document.getElementById('transfer-modal');
    modal.classList.remove('hidden');
    modal.classList.add('flex');
}

function closeModal() {
    const modal = document.getElementById('transfer-modal');
    modal.classList.add('hidden');
    modal.classList.remove('flex');
    document.getElementById('transfer-form').reset();
    document.getElementById('transfer-date').value = new Date().toISOString().split('T')[0];
}

async function handleListClick(event) {
    const button = event.target.closest('button[data-action="delete"]');
    if (!button) return;

    const id = Number.parseInt(button.dataset.id, 10);
    if (!Number.isInteger(id)) return;
    if (!confirm('¿Eliminar esta transferencia? Se devolverán los saldos.')) return;

    try {
        await deleteTransfer(id);
        await loadTransfers();
        await loadAccountsSelect();
    } catch (error) {
        alert(error.message || 'Error al eliminar transferencia');
    }
}

async function handleSubmit(e) {
    e.preventDefault();
    // Els comptes van com a "sourceAccount"/"destinationAccount": són els noms
    // de camp del model Transfer, que no porta @JsonProperty. Abans s'enviaven
    // "account_origen_id"/"account_desti_id", que són els noms de les columnes
    // de la base de dades; Jackson els descartava i el backend responia que
    // faltaven els comptes, o sigui que crear una transferència no funcionava.
    const data = {
        sourceAccount: { id: parseInt(document.getElementById('transfer-source').value) },
        destinationAccount: { id: parseInt(document.getElementById('transfer-destination').value) },
        import: parseFloat(document.getElementById('transfer-amount').value),
        data: document.getElementById('transfer-date').value,
        descripcio: document.getElementById('transfer-description').value
    };

    try {
        await createTransfer(data);
        closeModal();
        await loadTransfers();
        await loadAccountsSelect();
    } catch (error) {
        alert(error.message || 'Error al crear transferencia');
    }
}