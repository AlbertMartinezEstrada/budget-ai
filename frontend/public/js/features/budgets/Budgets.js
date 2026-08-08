import {
    getBudgets, getBudget, createBudget, updateBudget, deleteBudget,
    getBudgetMonthlySummary, getCategories, formatCurrency, escapeHtml
} from '../../api.js';

// Tailwind no pot generar classes construïdes en temps d'execució
// (`bg-${color}-500`), així que s'enumeren senceres.
const BAR_CLASSES = { low: 'bg-green-500', medium: 'bg-orange-500', high: 'bg-red-500' };
const TEXT_CLASSES = { low: 'text-green-600', medium: 'text-orange-600', high: 'text-red-600' };

const MONTH_NAMES = ['Enero', 'Febrero', 'Marzo', 'Abril', 'Mayo', 'Junio',
    'Julio', 'Agosto', 'Septiembre', 'Octubre', 'Noviembre', 'Diciembre'];

let categories = [];
// Els grups desplegats es recorden entre recàrregues perquè guardar un
// pressupost no plegui el que l'usuari estava mirant.
const expandedGroups = new Set();

export async function initBudgets(container) {
    const now = new Date();

    container.innerHTML = `
        <div class="page-header flex justify-between items-center mb-6">
            <h2 class="text-2xl font-bold text-slate-800 dark:text-slate-100">Presupuestos</h2>
            <div class="flex items-center gap-2">
                <select id="budget-year" class="px-3 py-2 border rounded-lg"></select>
                <select id="budget-month" class="px-3 py-2 border rounded-lg">
                    ${MONTH_NAMES.map((m, i) => `<option value="${i + 1}">${m}</option>`).join('')}
                </select>
                <button id="add-budget-btn" class="bg-primary text-white px-4 py-2 rounded-lg hover:bg-primary/90 flex items-center gap-2">
                    <span class="material-symbols-outlined">add</span>
                    Nuevo
                </button>
            </div>
        </div>

        <div class="bg-white dark:bg-slate-800 rounded-xl p-4 shadow-sm border border-slate-200 dark:border-slate-700 mb-6">
            <div class="grid grid-cols-1 sm:grid-cols-3 gap-4 text-center">
                <div>
                    <div class="text-xs text-gray-500 dark:text-slate-400">Coste de vida · plan</div>
                    <div class="text-xl font-bold" id="total-pla">—</div>
                </div>
                <div>
                    <div class="text-xs text-gray-500 dark:text-slate-400">Coste de vida · real</div>
                    <div class="text-xl font-bold text-primary" id="total-cost">—</div>
                </div>
                <div>
                    <div class="text-xs text-gray-500 dark:text-slate-400">Caja del mes</div>
                    <div class="text-xl font-bold" id="total-caixa">—</div>
                </div>
            </div>
            <p class="text-xs text-gray-500 dark:text-slate-400 mt-3 text-center">
                El <strong>coste de vida</strong> reparte los gastos fijos mes a mes.
                La <strong>caja</strong> es lo que ha salido de la cuenta este mes.
            </p>
        </div>

        <div id="budgets-list" class="space-y-4"></div>

        <!-- Modal -->
        <div id="budget-modal" class="fixed inset-0 bg-black/50 hidden items-center justify-center z-50">
            <div class="bg-white dark:bg-slate-800 rounded-xl p-6 w-full max-w-md">
                <h3 class="text-xl font-bold mb-4" id="modal-title">Nuevo Presupuesto</h3>
                <form id="budget-form" class="space-y-4">
                    <input type="hidden" id="budget-id">
                    <div>
                        <label class="block text-sm font-medium mb-1">Categoría</label>
                        <select id="budget-category" required class="w-full px-3 py-2 border rounded-lg"></select>
                        <p class="text-xs text-gray-500 dark:text-slate-400 mt-1">
                            Un grupo fija el techo del conjunto; una subcategoría, solo el suyo.
                        </p>
                    </div>
                    <div>
                        <label class="block text-sm font-medium mb-1">Límite mensual</label>
                        <input type="number" id="budget-limit" step="0.01" required class="w-full px-3 py-2 border rounded-lg">
                    </div>
                    <div class="grid grid-cols-2 gap-4">
                        <div>
                            <label class="block text-sm font-medium mb-1">Inicio</label>
                            <input type="date" id="budget-start" required class="w-full px-3 py-2 border rounded-lg">
                        </div>
                        <div>
                            <label class="block text-sm font-medium mb-1">Fin</label>
                            <input type="date" id="budget-end" required class="w-full px-3 py-2 border rounded-lg">
                        </div>
                    </div>
                    <div class="flex gap-2 justify-end">
                        <button type="button" id="cancel-btn" class="px-4 py-2 border rounded-lg hover:bg-gray-50 dark:hover:bg-slate-700">Cancelar</button>
                        <button type="submit" class="bg-primary text-white px-4 py-2 rounded-lg hover:bg-primary/90">Guardar</button>
                    </div>
                </form>
            </div>
        </div>
    `;

    const yearSelect = document.getElementById('budget-year');
    for (let y = now.getFullYear(); y >= now.getFullYear() - 5; y--) {
        const option = document.createElement('option');
        option.value = y;
        option.textContent = y;
        yearSelect.appendChild(option);
    }
    // El valor s'assigna després de crear les opcions, no abans.
    yearSelect.value = now.getFullYear();
    document.getElementById('budget-month').value = now.getMonth() + 1;

    await loadCategories();
    await loadSummary();

    document.getElementById('add-budget-btn').addEventListener('click', () => openModal());
    document.getElementById('cancel-btn').addEventListener('click', () => closeModal());
    document.getElementById('budget-form').addEventListener('submit', handleSubmit);
    document.getElementById('budgets-list').addEventListener('click', handleListClick);
    document.getElementById('budget-year').addEventListener('change', loadSummary);
    document.getElementById('budget-month').addEventListener('change', loadSummary);
    document.getElementById('budget-modal').addEventListener('click', (e) => {
        if (e.target.id === 'budget-modal') closeModal();
    });
}

function selectedPeriod() {
    return {
        year: Number.parseInt(document.getElementById('budget-year').value, 10),
        month: Number.parseInt(document.getElementById('budget-month').value, 10)
    };
}

async function loadCategories() {
    try {
        categories = await getCategories();

        // Al desplegable, les subcategories surten indentades sota el seu grup
        // perquè es vegi l'estructura sense haver-la de recordar.
        const groups = categories.filter(c => categories.some(x => x.parent_id === c.id));
        const groupIds = new Set(groups.map(g => g.id));
        const orphans = categories.filter(c => !groupIds.has(c.id) && !c.parent_id);

        const options = [];
        for (const group of groups) {
            options.push(`<option value="${group.id}">${escapeHtml(group.nom)} (grupo)</option>`);
            for (const child of categories.filter(c => c.parent_id === group.id)) {
                options.push(`<option value="${child.id}">&nbsp;&nbsp;&nbsp;${escapeHtml(child.nom)}</option>`);
            }
        }
        for (const orphan of orphans) {
            options.push(`<option value="${orphan.id}">${escapeHtml(orphan.nom)}</option>`);
        }

        document.getElementById('budget-category').innerHTML = options.join('');
    } catch (error) {
        console.error('Error loading categories:', error);
    }
}

async function loadSummary() {
    const container = document.getElementById('budgets-list');
    const { year, month } = selectedPeriod();

    try {
        const [summary, budgets] = await Promise.all([
            getBudgetMonthlySummary(year, month),
            getBudgets()
        ]);
        renderSummary(summary, budgets);
    } catch (error) {
        console.error('Error loading budgets:', error);
        container.innerHTML = '<p class="text-red-500">Error al cargar presupuestos</p>';
    }
}

function renderSummary(summary, budgets) {
    const container = document.getElementById('budgets-list');

    if (!summary || summary.length === 0) {
        container.innerHTML = `
            <p class="text-gray-500 dark:text-slate-400 text-center py-8">
                No hay categorías todavía.
            </p>`;
        updateTotals([]);
        return;
    }

    // Un mateix grup pot tenir diversos pressupostos al llarg del temps; per
    // editar-lo cal saber quin és el que aplica al període que es mira.
    const budgetByCategory = new Map();
    for (const budget of budgets || []) {
        if (budget.category) budgetByCategory.set(budget.category.id, budget);
    }

    container.innerHTML = summary.map(node => renderNode(node, budgetByCategory)).join('');
    updateTotals(summary);
}

function renderNode(node, budgetByCategory) {
    const category = node.categoria;
    const plan = Number.parseFloat(node.cost_vida_pla) || 0;
    const real = Number.parseFloat(node.cost_vida_real) || 0;
    const cash = Number.parseFloat(node.caixa_real) || 0;

    const percent = plan > 0 ? Math.min((real / plan) * 100, 100) : 0;
    const level = percent > 90 ? 'high' : percent > 70 ? 'medium' : 'low';
    const budget = budgetByCategory.get(category.id);
    const expanded = expandedGroups.has(category.id);
    const hasChildren = (node.subcategories || []).length > 0;

    return `
        <div class="bg-white dark:bg-slate-800 rounded-xl p-4 shadow-sm border border-slate-200 dark:border-slate-700">
            <div class="flex justify-between items-start mb-3">
                <div class="flex items-center gap-2">
                    ${hasChildren ? `
                        <button data-action="toggle" data-id="${category.id}"
                                class="p-1 hover:bg-gray-100 dark:hover:bg-slate-700 rounded"
                                title="${expanded ? 'Plegar' : 'Desplegar'}">
                            <span class="material-symbols-outlined text-sm">${expanded ? 'expand_less' : 'expand_more'}</span>
                        </button>` : '<span class="w-7 inline-block"></span>'}
                    <div>
                        <h3 class="font-semibold text-lg">${escapeHtml(category.nom)}</h3>
                        <span class="text-xs text-gray-500 dark:text-slate-400">
                            ${hasChildren ? 'Grupo' : (category.tipus_cost === 'FIXED' ? 'Fijo' : 'Variable')}
                        </span>
                    </div>
                </div>
                <div class="flex gap-1">
                    ${budget ? `
                        <button data-action="edit" data-id="${budget.id}" class="p-1 hover:bg-gray-100 dark:hover:bg-slate-700 rounded" title="Editar presupuesto">
                            <span class="material-symbols-outlined text-sm">edit</span>
                        </button>
                        <button data-action="delete" data-id="${budget.id}" class="p-1 hover:bg-red-50 text-red-500 rounded" title="Eliminar presupuesto">
                            <span class="material-symbols-outlined text-sm">delete</span>
                        </button>` : `
                        <button data-action="create" data-category="${category.id}" class="p-1 hover:bg-gray-100 dark:hover:bg-slate-700 rounded" title="Poner un límite">
                            <span class="material-symbols-outlined text-sm">add</span>
                        </button>`}
                </div>
            </div>

            <div class="mb-2">
                <div class="flex justify-between text-sm mb-1">
                    <span class="text-gray-600 dark:text-slate-300">${formatCurrency(real)} coste de vida</span>
                    <span class="font-medium">${plan > 0 ? `${formatCurrency(plan)} plan` : 'sin plan'}</span>
                </div>
                <div class="h-2 bg-gray-200 dark:bg-slate-700 rounded-full overflow-hidden">
                    <div class="h-full ${BAR_CLASSES[level]} transition-all" style="width: ${percent}%"></div>
                </div>
            </div>

            <div class="flex justify-between items-center text-sm">
                <span class="${TEXT_CLASSES[level]} font-medium">
                    ${plan > 0 ? `${percent.toFixed(0)}% del plan` : ''}
                </span>
                <span class="text-gray-600 dark:text-slate-300">
                    Caja: <strong>${formatCurrency(cash)}</strong>
                    ${node.carrec_puntual_aquest_mes
                        ? '<span class="ml-1 text-orange-600" title="Este mes ha caído un cargo fijo puntual">⚑</span>'
                        : ''}
                </span>
            </div>

            ${hasChildren && expanded ? `
                <div class="mt-4 border-t border-slate-200 dark:border-slate-700 pt-3 space-y-2">
                    ${node.subcategories.map(renderLeaf).join('')}
                </div>` : ''}
        </div>
    `;
}

function renderLeaf(leaf) {
    const category = leaf.categoria;
    const fixed = category.tipus_cost === 'FIXED';
    const cash = Number.parseFloat(leaf.caixa_real) || 0;
    const cost = Number.parseFloat(leaf.cost_vida_real) || 0;
    const plan = Number.parseFloat(leaf.cost_vida_pla) || 0;

    // Un fix es mesura pel prorrateig; un variable, pel sostre si en té.
    const right = fixed
        ? `${formatCurrency(cost)}/mes prorrateado`
        : (plan > 0 ? `${formatCurrency(cost)} de ${formatCurrency(plan)}` : formatCurrency(cost));

    return `
        <div class="flex justify-between items-center text-sm">
            <span class="flex items-center gap-2">
                <span class="text-xs px-2 py-0.5 rounded-full ${fixed
                    ? 'bg-blue-100 text-blue-700'
                    : 'bg-slate-100 text-slate-600'}">${fixed ? 'fijo' : 'variable'}</span>
                <span>${escapeHtml(category.nom)}</span>
                ${leaf.carrec_puntual_aquest_mes
                    ? '<span class="text-orange-600 text-xs" title="El cargo real ha caído este mes">⚑ cargo este mes</span>'
                    : ''}
            </span>
            <span class="text-gray-600 dark:text-slate-300">
                ${right}
                ${fixed && cash > 0 ? `<span class="text-xs text-gray-400"> · caja ${formatCurrency(cash)}</span>` : ''}
            </span>
        </div>
    `;
}

function updateTotals(summary) {
    const sum = (key) => (summary || []).reduce(
        (total, node) => total + (Number.parseFloat(node[key]) || 0), 0);

    document.getElementById('total-pla').textContent = formatCurrency(sum('cost_vida_pla'));
    document.getElementById('total-cost').textContent = formatCurrency(sum('cost_vida_real'));
    document.getElementById('total-caixa').textContent = formatCurrency(sum('caixa_real'));
}

function openModal(budget = null, presetCategoryId = null) {
    const modal = document.getElementById('budget-modal');
    const title = document.getElementById('modal-title');
    const form = document.getElementById('budget-form');
    const { year, month } = selectedPeriod();

    form.reset();

    // Per defecte, el període és el mes que s'està mirant: els pressupostos
    // d'aquesta pantalla són mensuals.
    const start = new Date(year, month - 1, 1);
    const end = new Date(year, month, 0);
    document.getElementById('budget-start').value = toInputDate(start);
    document.getElementById('budget-end').value = toInputDate(end);

    if (budget) {
        title.textContent = 'Editar Presupuesto';
        document.getElementById('budget-id').value = budget.id;
        document.getElementById('budget-category').value = budget.category?.id;
        document.getElementById('budget-limit').value = budget.quantitat_limit;
        document.getElementById('budget-start').value = budget.periode_inici;
        document.getElementById('budget-end').value = budget.periode_fi;
    } else {
        title.textContent = 'Nuevo Presupuesto';
        document.getElementById('budget-id').value = '';
        if (presetCategoryId) {
            document.getElementById('budget-category').value = presetCategoryId;
        }
    }

    modal.classList.remove('hidden');
    modal.classList.add('flex');
}

function toInputDate(date) {
    // toISOString() passa a UTC i pot restar un dia segons la zona horària.
    const month = String(date.getMonth() + 1).padStart(2, '0');
    const day = String(date.getDate()).padStart(2, '0');
    return `${date.getFullYear()}-${month}-${day}`;
}

function closeModal() {
    const modal = document.getElementById('budget-modal');
    modal.classList.add('hidden');
    modal.classList.remove('flex');
}

async function handleListClick(event) {
    const button = event.target.closest('button[data-action]');
    if (!button) return;

    const action = button.dataset.action;

    if (action === 'toggle') {
        const categoryId = Number.parseInt(button.dataset.id, 10);
        if (expandedGroups.has(categoryId)) expandedGroups.delete(categoryId);
        else expandedGroups.add(categoryId);
        await loadSummary();
        return;
    }

    if (action === 'create') {
        openModal(null, Number.parseInt(button.dataset.category, 10));
        return;
    }

    const id = Number.parseInt(button.dataset.id, 10);
    if (!Number.isInteger(id)) return;

    if (action === 'edit') {
        try {
            openModal(await getBudget(id));
        } catch (error) {
            alert(error.message || 'Error al cargar presupuesto');
        }
        return;
    }

    if (action === 'delete') {
        if (!confirm('¿Eliminar este presupuesto?')) return;
        try {
            await deleteBudget(id);
            await loadSummary();
        } catch (error) {
            alert(error.message || 'Error al eliminar presupuesto');
        }
    }
}

async function handleSubmit(e) {
    e.preventDefault();
    const id = document.getElementById('budget-id').value;
    const data = {
        category: { id: parseInt(document.getElementById('budget-category').value) },
        quantitat_limit: parseFloat(document.getElementById('budget-limit').value),
        periode_inici: document.getElementById('budget-start').value,
        periode_fi: document.getElementById('budget-end').value,
        actiu: true
    };

    try {
        if (id) {
            await updateBudget(parseInt(id), data);
        } else {
            await createBudget(data);
        }
        closeModal();
        await loadSummary();
    } catch (error) {
        alert(error.message || 'Error al guardar presupuesto');
    }
}
