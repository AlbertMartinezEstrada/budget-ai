import { getMonthlySummary, getCategoryBreakdown, getMonthlyTrend, formatCurrency, escapeHtml } from '../../api.js';

export async function initAnalytics(container) {
    const currentDate = new Date();
    const currentYear = currentDate.getFullYear();
    const currentMonth = currentDate.getMonth() + 1;

    container.innerHTML = `
        <div class="page-header mb-6">
            <div class="flex justify-between items-center">
                <h2 class="text-2xl font-bold text-slate-800">Análisis</h2>
                <div class="flex gap-2">
                    <select id="year-select" class="px-3 py-2 border rounded-lg"></select>
                    <select id="month-select" class="px-3 py-2 border rounded-lg">
                        <option value="1">Enero</option>
                        <option value="2">Febrero</option>
                        <option value="3">Marzo</option>
                        <option value="4">Abril</option>
                        <option value="5">Mayo</option>
                        <option value="6">Junio</option>
                        <option value="7">Julio</option>
                        <option value="8">Agosto</option>
                        <option value="9">Septiembre</option>
                        <option value="10">Octubre</option>
                        <option value="11">Noviembre</option>
                        <option value="12">Diciembre</option>
                    </select>
                </div>
            </div>
        </div>

        <!-- Resumen Mensual -->
        <div class="grid grid-cols-1 md:grid-cols-4 gap-4 mb-6">
            <div class="bg-white rounded-xl p-4 shadow-sm border border-slate-200">
                <div class="text-sm text-gray-500 mb-1">Ingresos</div>
                <div class="text-2xl font-bold text-green-600" id="total-income">${formatCurrency(0)}</div>
            </div>
            <div class="bg-white rounded-xl p-4 shadow-sm border border-slate-200">
                <div class="text-sm text-gray-500 mb-1">Gastos</div>
                <div class="text-2xl font-bold text-red-600" id="total-expenses">${formatCurrency(0)}</div>
            </div>
            <div class="bg-white rounded-xl p-4 shadow-sm border border-slate-200">
                <div class="text-sm text-gray-500 mb-1">Balance</div>
                <div class="text-2xl font-bold" id="total-balance">${formatCurrency(0)}</div>
            </div>
            <div class="bg-white rounded-xl p-4 shadow-sm border border-slate-200">
                <div class="text-sm text-gray-500 mb-1">Ahorro</div>
                <div class="text-2xl font-bold text-primary" id="savings-rate">0%</div>
            </div>
        </div>

        <!-- Gráfico de Categorías -->
        <div class="bg-white rounded-xl p-6 shadow-sm border border-slate-200 mb-6">
            <h3 class="text-lg font-semibold mb-4">Gastos por Categoría</h3>
            <div id="category-chart" class="space-y-3"></div>
        </div>

        <!-- Tendencia Mensual -->
        <div class="bg-white rounded-xl p-6 shadow-sm border border-slate-200">
            <h3 class="text-lg font-semibold mb-4">Tendencia Mensual</h3>
            <div id="trend-chart" class="space-y-2"></div>
        </div>
    `;

    const yearSelect = document.getElementById('year-select');
    for (let y = currentYear; y >= currentYear - 5; y--) {
        const option = document.createElement('option');
        option.value = y;
        option.textContent = y;
        yearSelect.appendChild(option);
    }
    // El valor s'ha d'assignar després de crear les opcions, no abans.
    yearSelect.value = currentYear;
    document.getElementById('month-select').value = currentMonth;

    document.getElementById('year-select').addEventListener('change', loadAllData);
    document.getElementById('month-select').addEventListener('change', loadAllData);

    await loadAllData();
}

async function loadAllData() {
    const year = document.getElementById('year-select').value;
    const month = document.getElementById('month-select').value;

    try {
        const [summary, categories, trend] = await Promise.all([
            getMonthlySummary(year, month),
            getCategoryBreakdown(year, month),
            getMonthlyTrend(year)
        ]);

        renderSummary(summary);
        renderCategories(categories);
        renderTrend(trend);
    } catch (error) {
        console.error('Error loading analytics:', error);
    }
}

function renderSummary(summary) {
    // AnalyticsService retorna "total_expense" en singular.
    const income = summary?.total_income || 0;
    const expenses = summary?.total_expense || 0;
    const balance = income - expenses;
    const savingsRate = income > 0 ? ((balance / income) * 100) : 0;

    document.getElementById('total-income').textContent = formatCurrency(income);
    document.getElementById('total-expenses').textContent = formatCurrency(expenses);
    document.getElementById('total-balance').textContent = formatCurrency(balance);
    document.getElementById('total-balance').className = `text-2xl font-bold ${balance >= 0 ? 'text-green-600' : 'text-red-600'}`;
    document.getElementById('savings-rate').textContent = savingsRate.toFixed(1) + '%';
}

function renderCategories(categories) {
    const container = document.getElementById('category-chart');
    if (!categories || categories.length === 0) {
        container.innerHTML = '<p class="text-gray-500">Sin datos</p>';
        return;
    }

    const total = categories.reduce((sum, cat) => sum + (cat.total || 0), 0);
    const colors = ['#4f46e5', '#10b981', '#f59e0b', '#ef4444', '#8b5cf6', '#06b6d4', '#ec4899', '#84cc16'];

    container.innerHTML = categories.map((cat, idx) => {
        const percent = total > 0 ? ((cat.total || 0) / total) * 100 : 0;
        const color = colors[idx % colors.length];

        return `
            <div class="flex items-center gap-3">
                <div class="w-3 h-3 rounded-full" style="background-color: ${color}"></div>
                <div class="flex-1">
                    <div class="flex justify-between text-sm">
                        <span class="font-medium">${escapeHtml(cat.category || 'Sin categoría')}</span>
                        <span class="text-gray-600">${formatCurrency(cat.total)} (${percent.toFixed(0)}%)</span>
                    </div>
                    <div class="h-2 bg-gray-200 rounded-full mt-1">
                        <div class="h-full rounded-full transition-all" style="width: ${percent}%; background-color: ${color}"></div>
                    </div>
                </div>
            </div>
        `;
    }).join('');
}

function renderTrend(trend) {
    const container = document.getElementById('trend-chart');
    if (!trend || trend.length === 0) {
        container.innerHTML = '<p class="text-gray-500">Sin datos</p>';
        return;
    }

    // L'escala ha de contemplar ingressos i despeses: si només mira les
    // despeses, les barres d'ingressos se'n surten quan són més grans.
    const maxValue = Math.max(
        ...trend.map(t => Math.max(t.total_income || 0, t.total_expense || 0)),
        1
    );

    container.innerHTML = trend.map(item => {
        const income = item.total_income || 0;
        const expense = item.total_expense || 0;
        const incomeWidth = income / maxValue * 100;
        const expenseWidth = expense / maxValue * 100;

        return `
            <div class="flex items-center gap-2">
                <span class="text-xs text-gray-500 w-16">${escapeHtml(formatPeriod(item.period))}</span>
                <div class="flex-1 flex gap-1 h-6">
                    <div class="h-full bg-green-400 rounded" style="width: ${incomeWidth}%"></div>
                    <div class="h-full bg-red-400 rounded" style="width: ${expenseWidth}%"></div>
                </div>
                <span class="text-xs text-gray-600 w-20 text-right">
                    ${formatCurrency(income - expense)}
                </span>
            </div>
        `;
    }).join('');
}

const MONTH_NAMES = ['Ene', 'Feb', 'Mar', 'Abr', 'May', 'Jun', 'Jul', 'Ago', 'Sep', 'Oct', 'Nov', 'Dic'];

// El backend retorna el període com a "2026-01".
function formatPeriod(period) {
    if (!period) return '';
    const [, month] = String(period).split('-');
    const index = Number.parseInt(month, 10) - 1;
    return MONTH_NAMES[index] || period;
}
