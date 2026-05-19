import { getTransactions } from '../../api.js';

export async function initDashboard(container) {
    container.innerHTML = `
        <div class="dashboard-grid">
            <div class="card">
                <div class="card-header">
                    <h3 class="card-title">Ingressos</h3>
                    <i class="ph ph-arrow-circle-down-right" style="font-size: 1.5rem; color: var(--secondary-color);"></i>
                </div>
                <div class="stat-value text-success" id="total-ingressos">0.00€</div>
                <div class="stat-label">Total rebut</div>
            </div>
            <div class="card">
                <div class="card-header">
                    <h3 class="card-title">Despeses</h3>
                    <i class="ph ph-arrow-circle-up-right" style="font-size: 1.5rem; color: #ef4444;"></i>
                </div>
                <div class="stat-value text-error" id="total-despeses">0.00€</div>
                <div class="stat-label">Total gastat</div>
            </div>
            <div class="card">
                <div class="card-header">
                    <h3 class="card-title">Balanç Net</h3>
                    <i class="ph ph-scales" style="font-size: 1.5rem; color: var(--primary-color);"></i>
                </div>
                <div class="stat-value" id="balanc-net">0.00€</div>
                <div class="stat-label">Ingressos - Despeses</div>
            </div>
        </div>
        
        <div class="grid grid-cols-1 lg:grid-cols-2 gap-6">
             <div class="card">
                <div class="card-header">
                    <h3 class="card-title">Top Categories</h3>
                    <i class="ph ph-chart-pie-slice" style="font-size: 1.5rem; color: #f59e0b;"></i>
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
        const transactions = await getTransactions();
        updateStats(transactions);
        renderRecentTransactions(transactions);
        renderTopCategories(transactions);
    } catch (error) {
        console.error('Error loading dashboard:', error);
        container.innerHTML += `<div class="alert alert-error mt-4">Error carregant dades: ${error.message}</div>`;
    }
}

function updateStats(transactions) {
    let income = 0;
    let expense = 0;

    transactions.forEach(t => {
        const amount = parseFloat(t.cost || 0);
        // Check type. If not present, assume expense if amount is positive? 
        // Usually banks give negative for expense. 
        // But our backend seems to store absolute value in 'amount' and type in 'type'.
        
        if (t.type === 'INCOME') {
            income += amount;
        } else {
            // EXPENSE or undefined (default to expense)
            expense += amount;
        }
    });

    const balance = income - expense;

    document.getElementById('total-ingressos').textContent = `${income.toFixed(2)}€`;
    document.getElementById('total-despeses').textContent = `${expense.toFixed(2)}€`;
    
    const balanceEl = document.getElementById('balanc-net');
    balanceEl.textContent = `${balance.toFixed(2)}€`;
    
    if (balance >= 0) {
        balanceEl.classList.add('text-success');
        balanceEl.classList.remove('text-error');
    } else {
        balanceEl.classList.add('text-error');
        balanceEl.classList.remove('text-success');
    }
}

function renderTopCategories(transactions) {
    // Filter only expenses for categories
    const expenses = transactions.filter(t => t.type !== 'INCOME');
    const categories = {};
    
    expenses.forEach(t => {
        const cat = t.categoria || 'Altres';
        categories[cat] = (categories[cat] || 0) + parseFloat(t.cost || 0);
    });

    const sortedCats = Object.entries(categories)
        .sort((a, b) => b[1] - a[1])
        .slice(0, 5);

    const container = document.getElementById('top-categories-list');
    
    if (sortedCats.length === 0) {
        container.innerHTML = '<div class="text-center text-sm text-gray-500">No hi ha dades de despeses.</div>';
        return;
    }

    const maxVal = sortedCats[0][1];

    container.innerHTML = sortedCats.map(([name, value]) => `
        <div class="flex items-center justify-between text-sm">
            <span class="font-medium text-gray-700">${name}</span>
            <span class="font-bold text-gray-900">${value.toFixed(2)}€</span>
        </div>
        <div class="w-full bg-gray-200 rounded-full h-2.5">
            <div class="bg-indigo-600 h-2.5 rounded-full" style="width: ${(value / maxVal) * 100}%"></div>
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

    tbody.innerHTML = recent.map(t => {
        const isIncome = t.type === 'INCOME';
        const amountClass = isIncome ? 'text-success' : 'text-error';
        const sign = isIncome ? '+' : '-';
        
        return `
        <tr>
            <td class="text-sm text-gray-500">${new Date(t.data).toLocaleDateString()}</td>
            <td class="font-medium text-gray-900">${t.empresa || 'Desconegut'}</td>
            <td class="text-right font-bold ${amountClass}">${sign}${parseFloat(t.cost).toFixed(2)}€</td>
        </tr>
    `}).join('');
}
