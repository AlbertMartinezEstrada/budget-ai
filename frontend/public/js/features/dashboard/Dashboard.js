import { getTransactions, getOverallSummary, formatCurrency, escapeHtml } from '../../api.js';

/**
 * El tauler: el resum de tot el que hi ha i els últims moviments.
 *
 * Les xifres les calcula el backend (GET /analytics/overall) amb el mateix
 * criteri que l'anàlisi i el pressupost. Abans es calculaven aquí, amb un
 * criteri propi que no sabia què era l'estalvi: els 300 € passats a Estalvis
 * sortien com a despesa al tauler i com a estalvi al pressupost.
 */

export async function initDashboard(container) {
    container.innerHTML = `
        <div class="dashboard-grid">
            <div class="card">
                <div class="card-header">
                    <h3 class="card-title">Ingressos</h3>
                    <span class="material-symbols-outlined" style="font-size: 1.5rem; color: var(--secondary-color);">arrow_circle_down</span>
                </div>
                <div class="stat-value text-success" id="total-ingressos">${formatCurrency(0)}</div>
                <div class="stat-label">Total rebut</div>
            </div>
            <div class="card">
                <div class="card-header">
                    <h3 class="card-title">Despeses</h3>
                    <span class="material-symbols-outlined" style="font-size: 1.5rem; color: #ef4444;">arrow_circle_up</span>
                </div>
                <div class="stat-value text-error" id="total-despeses">${formatCurrency(0)}</div>
                <div class="stat-label">Total gastat</div>
            </div>
            <div class="card">
                <div class="card-header">
                    <h3 class="card-title">Estalviat</h3>
                    <span class="material-symbols-outlined" style="font-size: 1.5rem; color: #0d9488;">savings</span>
                </div>
                <div class="stat-value text-teal-700 dark:text-teal-400" id="total-estalviat">${formatCurrency(0)}</div>
                <div class="stat-label" id="taxa-estalvi">A Estalvi i inversió</div>
            </div>
            <div class="card">
                <div class="card-header">
                    <h3 class="card-title">Sense destinar</h3>
                    <span class="material-symbols-outlined" style="font-size: 1.5rem; color: var(--primary-color);">balance</span>
                </div>
                <div class="stat-value" id="sense-destinar">${formatCurrency(0)}</div>
                <div class="stat-label">Ingressos menys despeses i estalvi</div>
            </div>
        </div>
        
        <div class="grid grid-cols-1 lg:grid-cols-2 gap-6">
             <div class="card">
                <div class="card-header">
                    <h3 class="card-title">Top Categories</h3>
                    <span class="material-symbols-outlined" style="font-size: 1.5rem; color: #f59e0b;">pie_chart</span>
                </div>
                <div id="top-categories-list" class="space-y-3">
                    <div class="text-center text-sm text-gray-500">Carregant...</div>
                </div>
            </div>

            <div class="card">
                <div class="card-header">
                    <h3 class="card-title">Últimes Transaccions</h3>
                    <button class="btn btn-sm btn-outline" data-view="transactions">Veure tot</button>
                </div>
                <div class="table-responsive">
                    <table class="table">
                        <thead>
                            <tr>
                                <th>Data</th>
                                <th>Empresa</th>
                                <th>Import</th>
                            </tr>
                        </thead>
                        <tbody id="recent-transactions">
                            <tr><td colspan="3" class="text-center">Carregant...</td></tr>
                        </tbody>
                    </table>
                </div>
            </div>
        </div>
    `;

    try {
        const [summary, transactions] = await Promise.all([getOverallSummary(), getTransactions()]);
        updateStats(summary);
        renderRecentTransactions(transactions);
        renderTopCategories(summary.top_categories || []);
    } catch (error) {
        console.error('Error loading dashboard:', error);
        container.innerHTML += `<div class="alert alert-error mt-4">Error carregant dades: ${escapeHtml(error.message)}</div>`;
    }
}

/**
 * Despeses inclou el que s'ha pagat des d'un compte d'estalvi (un regal amb
 * la targeta de Trade Republic), però «Sense destinar» no ho resta: surt del
 * que ja s'havia apartat.
 */
function updateStats(summary) {
    const income = Number(summary.total_income) || 0;
    const expense = Number(summary.total_expense) || 0;
    const saved = Number(summary.total_savings) || 0;
    const unassigned = Number(summary.unassigned) || 0;

    document.getElementById('total-ingressos').textContent = formatCurrency(income);
    document.getElementById('total-despeses').textContent = formatCurrency(expense);
    document.getElementById('total-estalviat').textContent = formatCurrency(saved);
    if (summary.savings_rate != null) {
        document.getElementById('taxa-estalvi').textContent =
            `${Number(summary.savings_rate).toFixed(1)}% dels ingressos`;
    }

    const unassignedElement = document.getElementById('sense-destinar');
    unassignedElement.textContent = formatCurrency(unassigned);
    unassignedElement.classList.toggle('text-success', unassigned >= 0);
    unassignedElement.classList.toggle('text-error', unassigned < 0);
}

/** Les que dona el backend, ja ordenades: d'un moviment dividit, cada part a la seva. */
function renderTopCategories(topCategories) {
    const sortedCategories = topCategories.map(item => [item.category, Number(item.total) || 0]);

    const container = document.getElementById('top-categories-list');
    
    if (sortedCategories.length === 0) {
        container.innerHTML = '<div class="text-center text-sm text-gray-500">No hi ha dades de despeses.</div>';
        return;
    }

    const maxValue = sortedCategories[0][1];

    container.innerHTML = sortedCategories.map(([name, value]) => `
        <div class="flex items-center justify-between text-sm">
            <span class="font-medium text-gray-700">${escapeHtml(name)}</span>
            <span class="font-bold text-gray-900">${formatCurrency(value)}</span>
        </div>
        <div class="w-full bg-gray-200 rounded-full h-2.5">
            <div class="bg-indigo-600 h-2.5 rounded-full" style="width: ${(value / maxValue) * 100}%"></div>
        </div>
    `).join('');
}

function renderRecentTransactions(transactions) {
    const tbody = document.getElementById('recent-transactions');
    const recent = transactions.slice(0, 5);

    if (recent.length === 0) {
        tbody.innerHTML = '<tr><td colspan="3" class="text-center">No hi ha transaccions recents.</td></tr>';
        return;
    }

    tbody.innerHTML = recent.map(transaction => {
        const isIncome = transaction.type === 'INCOME';
        const amountClass = isIncome ? 'text-success' : 'text-error';
        const sign = isIncome ? '+' : '-';
        
        return `
        <tr>
            <td class="text-sm text-gray-500">${new Date(transaction.data).toLocaleDateString()}</td>
            <td class="font-medium text-gray-900">${escapeHtml(transaction.empresa || 'Desconegut')}</td>
            <td class="text-right font-bold ${amountClass}">${sign}${formatCurrency(parseFloat(transaction.cost))}</td>
        </tr>
    `}).join('');
}
