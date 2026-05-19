import { getTransactions, getCategories, getCompanies, formatCurrency } from '../../api.js';

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
                    <button class="btn btn-sm btn-outline" id="toggle-transaction-sort" type="button"></button>
                    <span class="badge badge-secondary" id="transaction-count">0</span>
                </div>
            </div>
            <div class="table-responsive">
                <table class="table table-hover">
                    <thead>
                        <tr>
                            <th>Data</th>
                            <th>Empresa</th>
                            <th>Categoria</th>
                            <th>Descripció</th>
                            <th class="text-right">Import</th>
                        </tr>
                    </thead>
                    <tbody id="transactions-body">
                        <tr><td colspan="5" class="text-center">Carregant...</td></tr>
                    </tbody>
                </table>
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
    document.getElementById('filter-month').addEventListener('change', loadData);
    document.getElementById('toggle-transaction-sort').addEventListener('click', toggleSortMode);
    document.getElementById('clear-filters').addEventListener('click', () => {
        document.getElementById('filter-category').value = '';
        document.getElementById('filter-company').value = '';
        document.getElementById('filter-month').value = '';
        currentSortMode = 'month-desc';
        updateSortButton();
        loadData();
    });
}

async function loadData() {
    const categoryId = document.getElementById('filter-category').value;
    const companyId = document.getElementById('filter-company').value;
    const selectedMonth = document.getElementById('filter-month').value;
    
    const filters = {};
    if (categoryId) filters.categoryId = categoryId;
    if (companyId) filters.companyId = companyId;
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
            <td colspan="5">${formatMonth(date)}</td>
        </tr>
    `;
}

function buildTransactionRow(transaction) {
    return `
        <tr>
            <td>${formatTransactionDate(transaction.data)}</td>
            <td>${transaction.empresa || '-'}</td>
            <td><span class="badge badge-outline">${transaction.categoria || '-'}</span></td>
            <td class="text-muted text-sm">${transaction.descripcio_curta || '-'}</td>
            <td class="text-right font-bold">${formatAmount(transaction.cost)}</td>
        </tr>
    `;
}

function renderMessageRow(message, isError = false) {
    document.getElementById('transactions-body').innerHTML = `
        <tr>
            <td colspan="5" class="text-center ${isError ? 'text-error' : ''}">${message}</td>
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
