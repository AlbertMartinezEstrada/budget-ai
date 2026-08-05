import { getAccounts, createAccount, updateAccount, deleteAccount, adjustBalance, formatCurrency, escapeHtml } from '../../api.js';

// Guardem els comptes carregats per no haver de passar-ne les dades pel DOM.
let loadedAccounts = [];

export async function initAccounts(container) {
    container.innerHTML = `
        <div class="page-header flex justify-between items-center mb-6">
            <h2 class="text-2xl font-bold text-slate-800 dark:text-slate-100">Cuentas</h2>
            <button id="add-account-btn" class="bg-primary text-white px-4 py-2 rounded-lg hover:bg-primary/90 flex items-center gap-2">
                <span class="material-symbols-outlined">add</span>
                Nueva Cuenta
            </button>
        </div>
        <div id="accounts-list" class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4"></div>

        <!-- Modal -->
        <div id="account-modal" class="fixed inset-0 bg-black/50 hidden items-center justify-center z-50">
            <div class="bg-white dark:bg-slate-800 rounded-xl p-6 w-full max-w-md">
                <h3 class="text-xl font-bold mb-4" id="modal-title">Nueva Cuenta</h3>
                <form id="account-form" class="space-y-4">
                    <input type="hidden" id="account-id">
                    <div>
                        <label class="block text-sm font-medium mb-1">Nombre</label>
                        <input type="text" id="account-name" required class="w-full px-3 py-2 border rounded-lg">
                    </div>
                    <div>
                        <label class="block text-sm font-medium mb-1">Tipo</label>
                        <select id="account-type" class="w-full px-3 py-2 border rounded-lg">
                            <option value="CORRIENTE">Cuenta Corriente</option>
                            <option value="AHORRO">Cuenta de Ahorro</option>
                            <option value="EFECTIVO">Efectivo</option>
                            <option value="TARJETA">Tarjeta</option>
                            <option value="INVERSIONES">Inversiones</option>
                        </select>
                    </div>
                    <div>
                        <label class="block text-sm font-medium mb-1">Saldo Inicial</label>
                        <input type="number" id="account-balance" step="0.01" value="0" class="w-full px-3 py-2 border rounded-lg">
                    </div>
                    <div>
                        <label class="block text-sm font-medium mb-1">Color</label>
                        <input type="color" id="account-color" value="#4f46e5" class="w-full h-10 border rounded-lg">
                    </div>
                    <div class="flex gap-2 justify-end">
                        <button type="button" id="cancel-btn" class="px-4 py-2 border rounded-lg hover:bg-gray-50 dark:hover:bg-slate-700">Cancelar</button>
                        <button type="submit" class="bg-primary text-white px-4 py-2 rounded-lg hover:bg-primary/90">Guardar</button>
                    </div>
                </form>
            </div>
        </div>

        <!-- Adjust Balance Modal -->
        <div id="adjust-modal" class="fixed inset-0 bg-black/50 hidden items-center justify-center z-50">
            <div class="bg-white dark:bg-slate-800 rounded-xl p-6 w-full max-w-md">
                <h3 class="text-xl font-bold mb-4">Ajustar Saldo</h3>
                <form id="adjust-form" class="space-y-4">
                    <input type="hidden" id="adjust-account-id">
                    <div>
                        <label class="block text-sm font-medium mb-1">Cantidad</label>
                        <input type="number" id="adjust-amount" step="0.01" class="w-full px-3 py-2 border rounded-lg">
                    </div>
                    <p class="text-sm text-gray-500 dark:text-slate-400">Usa valores positivos para aumentar o negativos para reducir.</p>
                    <div class="flex gap-2 justify-end">
                        <button type="button" id="cancel-adjust-btn" class="px-4 py-2 border rounded-lg hover:bg-gray-50 dark:hover:bg-slate-700">Cancelar</button>
                        <button type="submit" class="bg-primary text-white px-4 py-2 rounded-lg hover:bg-primary/90">Ajustar</button>
                    </div>
                </form>
            </div>
        </div>
    `;

    await loadAccounts();

    document.getElementById('add-account-btn').addEventListener('click', () => openModal());
    document.getElementById('cancel-btn').addEventListener('click', () => closeModal());
    document.getElementById('account-form').addEventListener('submit', handleSubmit);
    document.getElementById('cancel-adjust-btn').addEventListener('click', () => closeAdjustModal());
    document.getElementById('adjust-form').addEventListener('submit', handleAdjustSubmit);
    document.getElementById('accounts-list').addEventListener('click', handleListClick);

    // Tancar els modals fent clic al fons.
    document.getElementById('account-modal').addEventListener('click', (e) => {
        if (e.target.id === 'account-modal') closeModal();
    });
    document.getElementById('adjust-modal').addEventListener('click', (e) => {
        if (e.target.id === 'adjust-modal') closeAdjustModal();
    });
}

async function loadAccounts() {
    try {
        const accounts = await getAccounts();
        loadedAccounts = accounts || [];
        renderAccounts(accounts);
    } catch (error) {
        console.error('Error loading accounts:', error);
        document.getElementById('accounts-list').innerHTML = '<p class="text-red-500">Error al cargar cuentas</p>';
    }
}

function renderAccounts(accounts) {
    const container = document.getElementById('accounts-list');
    if (!accounts || accounts.length === 0) {
        container.innerHTML = '<p class="text-gray-500 dark:text-slate-400 col-span-3 text-center py-8">No hay cuentas</p>';
        return;
    }

    container.innerHTML = accounts.map(account => {
        const color = sanitizeColor(account.color);
        return `
        <div class="bg-white dark:bg-slate-800 rounded-xl p-4 shadow-sm border border-slate-200 dark:border-slate-700">
            <div class="flex items-center justify-between mb-3">
                <div class="flex items-center gap-3">
                    <div class="w-10 h-10 rounded-lg flex items-center justify-center" style="background-color: ${color}20">
                        <span class="material-symbols-outlined" style="color: ${color}">account_balance_wallet</span>
                    </div>
                    <div>
                        <h3 class="font-semibold">${escapeHtml(account.nom)}</h3>
                        <span class="text-xs text-gray-500 dark:text-slate-400">${escapeHtml(account.tipus)}</span>
                    </div>
                </div>
                <div class="flex gap-1">
                    <button data-action="adjust" data-id="${account.id}" class="p-1 hover:bg-gray-100 dark:hover:bg-slate-700 rounded" title="Ajustar saldo">
                        <span class="material-symbols-outlined text-sm">edit</span>
                    </button>
                    <button data-action="edit" data-id="${account.id}" class="p-1 hover:bg-gray-100 dark:hover:bg-slate-700 rounded" title="Editar">
                        <span class="material-symbols-outlined text-sm">settings</span>
                    </button>
                    <button data-action="delete" data-id="${account.id}" class="p-1 hover:bg-red-50 text-red-500 rounded" title="Eliminar">
                        <span class="material-symbols-outlined text-sm">delete</span>
                    </button>
                </div>
            </div>
            <div class="text-2xl font-bold text-slate-800 dark:text-slate-100">
                ${formatCurrency(account.saldo_actual)}
            </div>
            <div class="text-xs text-gray-500 dark:text-slate-400 mt-1">${escapeHtml(account.moneda || 'EUR')}</div>
        </div>
    `;
    }).join('');
}

// El color va a parar a un atribut style, on escapar no n'hi ha prou:
// només s'accepta un codi hexadecimal.
function sanitizeColor(color) {
    return /^#[0-9a-fA-F]{6}$/.test(color || '') ? color : '#4f46e5';
}

function openModal(account = null) {
    const modal = document.getElementById('account-modal');
    const title = document.getElementById('modal-title');
    const form = document.getElementById('account-form');

    if (account) {
        title.textContent = 'Editar Cuenta';
        document.getElementById('account-id').value = account.id;
        document.getElementById('account-name').value = account.nom;
        document.getElementById('account-type').value = account.tipus;
        document.getElementById('account-balance').value = account.saldo_actual || 0;
        document.getElementById('account-color').value = account.color || '#4f46e5';
    } else {
        title.textContent = 'Nueva Cuenta';
        form.reset();
        document.getElementById('account-id').value = '';
    }

    modal.classList.remove('hidden');
    modal.classList.add('flex');
}

function closeModal() {
    const modal = document.getElementById('account-modal');
    modal.classList.add('hidden');
    modal.classList.remove('flex');
}

function openAdjustModal(id) {
    document.getElementById('adjust-account-id').value = id;
    document.getElementById('adjust-amount').value = 0;
    const modal = document.getElementById('adjust-modal');
    modal.classList.remove('hidden');
    modal.classList.add('flex');
}

function closeAdjustModal() {
    const modal = document.getElementById('adjust-modal');
    modal.classList.add('hidden');
    modal.classList.remove('flex');
}

// Delegació d'esdeveniments en comptes d'onclick amb dades interpolades.
// Abans el nom del compte s'incrustava dins d'una crida JavaScript entre
// cometes simples: un compte anomenat "O'Brien" trencava el botó, i un nom
// preparat a posta podia executar codi.
async function handleListClick(event) {
    const button = event.target.closest('button[data-action]');
    if (!button) return;

    const id = Number.parseInt(button.dataset.id, 10);
    if (!Number.isInteger(id)) return;

    switch (button.dataset.action) {
        case 'adjust':
            openAdjustModal(id);
            break;
        case 'edit': {
            const account = loadedAccounts.find(a => a.id === id);
            if (account) openModal(account);
            break;
        }
        case 'delete':
            await removeAccount(id);
            break;
    }
}

async function removeAccount(id) {
    if (!confirm('¿Eliminar esta cuenta?')) return;
    try {
        await deleteAccount(id);
        await loadAccounts();
    } catch (error) {
        // El backend explica per què no es pot esborrar (moviments associats).
        alert(error.message || 'Error al eliminar cuenta');
    }
}

async function handleSubmit(e) {
    e.preventDefault();
    const id = document.getElementById('account-id').value;
    const data = {
        nom: document.getElementById('account-name').value,
        tipus: document.getElementById('account-type').value,
        saldo_actual: parseFloat(document.getElementById('account-balance').value),
        color: document.getElementById('account-color').value
    };

    try {
        if (id) {
            await updateAccount(parseInt(id), data);
        } else {
            await createAccount(data);
        }
        closeModal();
        await loadAccounts();
    } catch (error) {
        alert(error.message || 'Error al guardar cuenta');
    }
}

async function handleAdjustSubmit(e) {
    e.preventDefault();
    const id = parseInt(document.getElementById('adjust-account-id').value);
    const amount = parseFloat(document.getElementById('adjust-amount').value);

    try {
        await adjustBalance(id, amount);
        closeAdjustModal();
        await loadAccounts();
    } catch (error) {
        alert(error.message || 'Error al ajustar saldo');
    }
}