import {
    getTransactions, getCategories, getCompanies, getAccounts,
    createTransaction, deleteTransaction, formatCurrency, escapeHtml
} from '../../api.js';

const MONTH_FORMATTER = new Intl.DateTimeFormat('ca-ES', {
    month: 'long',
    year: 'numeric'
});

let currentSortMode = 'month-desc';
let currentTransactions = [];

export async function initTransactions(container) {
    container.innerHTML = `
        <div class="card mb-4">
            <div class="card-header">
                <h3 class="card-title">Filtres</h3>
                <button class="btn btn-sm btn-outline" id="clear-filters">Netejar</button>
            </div>
            <div class="filter-grid">
                <div class="form-group">
                    <label for="filter-category">Categoria</label>
                    <select id="filter-category" class="form-control">
                        <option value="">Totes</option>
                    </select>
                </div>
                <div class="form-group">
                    <label for="filter-company">Empresa</label>
                    <select id="filter-company" class="form-control">
                        <option value="">Totes</option>
                    </select>
                </div>
                <div class="form-group">
                    <label for="filter-account">Compte</label>
                    <select id="filter-account" class="form-control">
                        <option value="">Tots</option>
                    </select>
                </div>
                <div class="form-group">
                    <label for="filter-month">Mes</label>
                    <select id="filter-month" class="form-control">
                        <option value="">Tots els mesos</option>
                    </select>
                </div>
            </div>
        </div>

        <div class="card">
            <div class="card-header">
                <h3 class="card-title">Llistat de Transaccions</h3>
                <div class="card-header-actions">
                    <button class="btn btn-sm btn-primary" id="add-transaction-btn" type="button">
                        + Afegir moviment
                    </button>
                    <button class="btn btn-sm btn-outline" id="toggle-transaction-sort" type="button"></button>
                    <span class="badge badge-secondary" id="transaction-count">0</span>
                </div>
            </div>
            <div class="table-responsive">
                <table class="table table-hover">
                    <thead>
                        <tr>
                            <th>Data</th>
                            <th>Compte</th>
                            <th>Empresa</th>
                            <th>Categoria</th>
                            <th>Descripció</th>
                            <th class="text-right">Import</th>
                            <th style="width: 3rem;"></th>
                        </tr>
                    </thead>
                    <tbody id="transactions-body">
                        <tr><td colspan="7" class="text-center">Carregant...</td></tr>
                    </tbody>
                </table>
            </div>
        </div>

        <!-- Modal d'alta manual -->
        <div id="transaction-modal" class="fixed inset-0 bg-black/50 hidden items-center justify-center z-50 p-4">
            <div class="bg-white dark:bg-slate-800 rounded-xl p-6 w-full max-w-md max-h-full overflow-y-auto">
                <h3 class="text-xl font-bold mb-1">Afegir moviment</h3>
                <p class="text-sm text-gray-500 dark:text-slate-400 mb-4">
                    Per al que no surt de l'extracte: efectiu, un préstec, una devolució.
                </p>
                <form id="transaction-form" class="space-y-4">
                    <div>
                        <label class="block text-sm font-medium mb-1" for="new-type">Tipus</label>
                        <select id="new-type" class="form-control">
                            <option value="EXPENSE">Despesa — resta del saldo</option>
                            <option value="INCOME">Ingrés — suma al saldo</option>
                        </select>
                    </div>
                    <div>
                        <label class="block text-sm font-medium mb-1" for="new-amount">Import</label>
                        <input type="number" id="new-amount" class="form-control" step="0.01" min="0.01" required>
                    </div>
                    <div>
                        <label class="block text-sm font-medium mb-1" for="new-date">Data</label>
                        <input type="date" id="new-date" class="form-control" required>
                    </div>
                    <div>
                        <label class="block text-sm font-medium mb-1" for="new-category">Categoria</label>
                        <select id="new-category" class="form-control" required></select>
                    </div>
                    <div>
                        <label class="block text-sm font-medium mb-1" for="new-company">Empresa</label>
                        <input type="text" id="new-company" class="form-control" list="company-suggestions"
                               placeholder="Desconegut">
                        <datalist id="company-suggestions"></datalist>
                    </div>
                    <div>
                        <label class="block text-sm font-medium mb-1" for="new-description">Descripció</label>
                        <input type="text" id="new-description" class="form-control" placeholder="Opcional">
                    </div>
                    <p id="transaction-form-error" class="text-error text-sm hidden"></p>
                    <div class="flex gap-2 justify-end">
                        <button type="button" id="transaction-cancel" class="btn btn-outline">Cancel·lar</button>
                        <button type="submit" class="btn btn-primary">Afegir</button>
                    </div>
                </form>
            </div>
        </div>
    `;

    // Load Filters
    const categories = await getCategories();
    const companies = await getCompanies();
    
    const catSelect = document.getElementById('filter-category');
    categories.forEach(c => {
        const option = document.createElement('option');
        option.value = c.id;
        option.textContent = c.nom;
        catSelect.appendChild(option);
    });

    const accSelect = document.getElementById('filter-account');
    (await getAccounts()).forEach(a => {
        const option = document.createElement('option');
        option.value = a.id;
        option.textContent = a.nom;
        accSelect.appendChild(option);
    });

    const compSelect = document.getElementById('filter-company');
    companies.forEach(c => {
        const option = document.createElement('option');
        option.value = c.id;
        option.textContent = c.nom;
        compSelect.appendChild(option);
    });

    try {
        const initialTransactions = await getTransactions();
        currentTransactions = initialTransactions;
        populateMonthOptions(initialTransactions);
        updateSortButton();
        renderTable(initialTransactions, currentSortMode);
    } catch (error) {
        console.error('Error loading initial transactions:', error);
        document.getElementById('transaction-count').textContent = '0';
        updateSortButton();
        renderMessageRow(`Error: ${error.message}`, true);
    }

    // Event Listeners
    document.getElementById('filter-category').addEventListener('change', loadData);
    document.getElementById('filter-company').addEventListener('change', loadData);
    document.getElementById('filter-account').addEventListener('change', loadData);
    document.getElementById('filter-month').addEventListener('change', loadData);
    document.getElementById('toggle-transaction-sort').addEventListener('click', toggleSortMode);
    document.getElementById('clear-filters').addEventListener('click', () => {
        document.getElementById('filter-category').value = '';
        document.getElementById('filter-company').value = '';
        document.getElementById('filter-account').value = '';
        document.getElementById('filter-month').value = '';
        currentSortMode = 'month-desc';
        updateSortButton();
        loadData();
    });

    // Delegació: les files es repinten a cada filtre, així que un listener
    // posat a cada botó no sobreviuria. I res d'onclick amb dades
    // interpolades, que es trenca amb un nom com O'Brien.
    document.getElementById('transactions-body').addEventListener('click', handleRowAction);

    setUpManualEntry(categories, companies);
}

async function handleRowAction(event) {
    const button = event.target.closest('button[data-action="delete-transaction"]');
    if (!button) return;

    const id = Number.parseInt(button.dataset.id, 10);
    if (!Number.isInteger(id)) return;

    // Esborrar mou el saldo del compte cap enrere, així que es pregunta.
    if (!confirm('Esborrar aquest moviment? El saldo del compte es desfarà.')) return;

    button.disabled = true;
    try {
        await deleteTransaction(id);
        await loadData();
    } catch (error) {
        alert(error.message || 'No s\'ha pogut esborrar el moviment.');
        button.disabled = false;
    }
}

/**
 * Alta manual d'un moviment.
 *
 * Al desplegable només hi van les fulles. Un grup existeix per agregar els
 * seus fills, i un moviment penjat d'un grup es comptaria dues vegades: el
 * backend ho rebutja, així que val més no oferir-ho.
 */
function setUpManualEntry(categories, companies) {
    const modal = document.getElementById('transaction-modal');
    const form = document.getElementById('transaction-form');
    const error = document.getElementById('transaction-form-error');

    const parents = new Set(categories.map(c => c.parent_id).filter(Boolean));
    const leaves = categories.filter(c => !parents.has(c.id));

    document.getElementById('new-category').innerHTML = leaves
        .map(c => `<option value="${escapeHtml(c.nom)}">${escapeHtml(c.nom)}</option>`)
        .join('');

    document.getElementById('company-suggestions').innerHTML = companies
        .map(c => `<option value="${escapeHtml(c.nom)}"></option>`)
        .join('');

    const close = () => {
        modal.classList.add('hidden');
        modal.classList.remove('flex');
    };

    document.getElementById('add-transaction-btn').addEventListener('click', () => {
        form.reset();
        error.classList.add('hidden');
        // Per defecte, avui: el cas normal és apuntar una cosa que acaba de passar.
        document.getElementById('new-date').value = todayInputValue();
        modal.classList.remove('hidden');
        modal.classList.add('flex');
    });

    document.getElementById('transaction-cancel').addEventListener('click', close);
    modal.addEventListener('click', (e) => {
        if (e.target.id === 'transaction-modal') close();
    });

    form.addEventListener('submit', async (event) => {
        event.preventDefault();
        error.classList.add('hidden');

        const amount = Number.parseFloat(document.getElementById('new-amount').value);
        if (!Number.isFinite(amount) || amount <= 0) {
            error.textContent = 'L\'import ha de ser més gran que zero.';
            error.classList.remove('hidden');
            return;
        }

        const submitButton = form.querySelector('button[type="submit"]');
        submitButton.disabled = true;

        try {
            await createTransaction({
                data: document.getElementById('new-date').value,
                cost: amount,
                type: document.getElementById('new-type').value,
                categoria: document.getElementById('new-category').value,
                empresa: document.getElementById('new-company').value.trim() || 'Desconegut',
                descripcio_curta: document.getElementById('new-description').value.trim()
            });
            close();
            await loadData();
        } catch (e) {
            error.textContent = e.message || 'No s\'ha pogut afegir el moviment.';
            error.classList.remove('hidden');
        } finally {
            // Es rehabilita sempre: si fallava, el botó quedava bloquejat i
            // calia tancar i tornar a obrir el formulari.
            submitButton.disabled = false;
        }
    });
}

/** Avui en el format que espera un <input type="date">. */
function todayInputValue() {
    const now = new Date();
    // toISOString() passa a UTC i pot restar un dia segons la zona horària.
    const month = String(now.getMonth() + 1).padStart(2, '0');
    const day = String(now.getDate()).padStart(2, '0');
    return `${now.getFullYear()}-${month}-${day}`;
}

async function loadData() {
    const categoryId = document.getElementById('filter-category').value;
    const companyId = document.getElementById('filter-company').value;
    const accountId = document.getElementById('filter-account').value;
    const selectedMonth = document.getElementById('filter-month').value;

    const filters = {};
    if (categoryId) filters.categoryId = categoryId;
    if (companyId) filters.companyId = companyId;
    if (accountId) filters.accountId = accountId;
    if (selectedMonth) {
        const { startDate, endDate } = buildMonthRange(selectedMonth);
        filters.startDate = startDate;
        filters.endDate = endDate;
    }

    try {
        const transactions = await getTransactions(filters);
        currentTransactions = transactions;
        renderTable(transactions, currentSortMode);
    } catch (error) {
        console.error('Error loading transactions:', error);
        currentTransactions = [];
        document.getElementById('transaction-count').textContent = '0';
        renderMessageRow(`Error: ${error.message}`, true);
    }
}

function toggleSortMode() {
    currentSortMode = currentSortMode === 'month-desc' ? 'month-asc' : 'month-desc';
    updateSortButton();
    renderTable(currentTransactions, currentSortMode);
}

function updateSortButton() {
    const sortButton = document.getElementById('toggle-transaction-sort');

    if (!sortButton) {
        return;
    }

    sortButton.textContent = currentSortMode === 'month-desc'
        ? 'Mesos: nous primer'
        : 'Mesos: antics primer';
    sortButton.setAttribute('aria-label', `Canviar ordre. Actual: ${sortButton.textContent}`);
}

function renderTable(transactions, sortBy = 'month-desc') {
    const tbody = document.getElementById('transactions-body');
    document.getElementById('transaction-count').textContent = transactions.length;

    if (transactions.length === 0) {
        renderMessageRow('No s\'han trobat resultats.');
        return;
    }

    const sortedTransactions = [...transactions].sort((left, right) => compareTransactions(left, right, sortBy));
    const shouldGroupByMonth = sortBy.startsWith('month-');

    let currentMonthKey = null;
    tbody.innerHTML = sortedTransactions.map(transaction => {
        const currentDate = parseTransactionDate(transaction.data);
        const monthKey = getMonthKey(currentDate);
        const showMonthHeader = shouldGroupByMonth && monthKey !== currentMonthKey;

        if (showMonthHeader) {
            currentMonthKey = monthKey;
        }

        return `${showMonthHeader ? buildMonthRow(currentDate) : ''}${buildTransactionRow(transaction)}`;
    }).join('');
}

function compareTransactions(left, right, sortBy) {
    const leftDate = parseTransactionDate(left.data);
    const rightDate = parseTransactionDate(right.data);
    const leftTime = leftDate ? leftDate.getTime() : Number.NEGATIVE_INFINITY;
    const rightTime = rightDate ? rightDate.getTime() : Number.NEGATIVE_INFINITY;

    switch (sortBy) {
        case 'date-asc':
        case 'month-asc':
            return leftTime - rightTime;
        case 'date-desc':
        case 'month-desc':
        default:
            return rightTime - leftTime;
    }
}

function buildMonthRow(date) {
    return `
        <tr class="table-group-row">
            <td colspan="7">${formatMonth(date)}</td>
        </tr>
    `;
}

function buildTransactionRow(transaction) {
    return `
        <tr>
            <td>${escapeHtml(formatTransactionDate(transaction.data))}</td>
            <td class="text-sm">${escapeHtml(transaction.account?.nom || '-')}</td>
            <td>
                ${escapeHtml(transaction.empresa || '-')}
                ${transaction.exclos_pressupost
                    ? '<span class="badge badge-outline text-sm" title="Diners ja comptats en sortir del compte principal: no compten al pressupost">no compta</span>'
                    : ''}
            </td>
            <td><span class="badge badge-outline">${escapeHtml(transaction.categoria || '-')}</span></td>
            <td class="text-muted text-sm">${escapeHtml(transaction.descripcio_curta || '-')}</td>
            <td class="text-right font-bold">${formatAmount(transaction.cost)}</td>
            <td class="text-right">
                <button class="btn btn-sm btn-outline" data-action="delete-transaction"
                        data-id="${transaction.id}" title="Esborrar aquest moviment">
                    <span class="material-symbols-outlined text-sm">delete</span>
                </button>
            </td>
        </tr>
    `;
}

function renderMessageRow(message, isError = false) {
    document.getElementById('transactions-body').innerHTML = `
        <tr>
            <td colspan="7" class="text-center ${isError ? 'text-error' : ''}">${message}</td>
        </tr>
    `;
}

function populateMonthOptions(transactions) {
    const monthSelect = document.getElementById('filter-month');
    const previousValue = monthSelect.value;
    const monthKeys = [...new Set(
        transactions
            .map(transaction => getMonthKey(parseTransactionDate(transaction.data)))
            .filter(monthKey => monthKey !== 'no-date')
    )].sort((left, right) => right.localeCompare(left));

    monthSelect.innerHTML = '<option value="">Tots els mesos</option>';

    monthKeys.forEach(monthKey => {
        const option = document.createElement('option');
        option.value = monthKey;
        option.textContent = formatMonth(createMonthDate(monthKey));
        monthSelect.appendChild(option);
    });

    if (monthKeys.includes(previousValue)) {
        monthSelect.value = previousValue;
    }
}

function buildMonthRange(monthKey) {
    const [year, month] = monthKey.split('-').map(Number);
    const lastDay = new Date(year, month, 0).getDate();

    return {
        startDate: `${year}-${String(month).padStart(2, '0')}-01`,
        endDate: `${year}-${String(month).padStart(2, '0')}-${String(lastDay).padStart(2, '0')}`
    };
}

function parseTransactionDate(rawDate) {
    if (!rawDate) {
        return null;
    }

    if (/^\d{4}-\d{2}-\d{2}$/.test(rawDate)) {
        const [year, month, day] = rawDate.split('-').map(Number);
        return new Date(year, month - 1, day);
    }

    const parsedDate = new Date(rawDate);
    return Number.isNaN(parsedDate.getTime()) ? null : parsedDate;
}

function formatTransactionDate(rawDate) {
    const date = parseTransactionDate(rawDate);
    return date ? date.toLocaleDateString('ca-ES') : '-';
}

function formatMonth(date) {
    if (!date) {
        return 'Sense data';
    }

    const label = MONTH_FORMATTER.format(date);
    return label.charAt(0).toUpperCase() + label.slice(1);
}

function getMonthKey(date) {
    if (!date) {
        return 'no-date';
    }

    return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`;
}

function createMonthDate(monthKey) {
    const [year, month] = monthKey.split('-').map(Number);
    return new Date(year, month - 1, 1);
}

function formatAmount(amount) {
    const numericAmount = Number.parseFloat(amount);
    return formatCurrency(Number.isFinite(numericAmount) ? numericAmount : 0);
}
