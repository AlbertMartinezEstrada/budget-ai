import {
    getBudgets, getBudget, createBudget, updateBudget, deleteBudget,
    getBudgetMonthlySummary, setMonthlyIncome, deleteMonthlyIncome,
    getCategories, formatCurrency, escapeHtml
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
let lastSummary = null;

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
            <div class="flex flex-wrap items-center justify-between gap-3 mb-4 pb-4 border-b border-slate-200 dark:border-slate-700">
                <div>
                    <div class="text-xs text-gray-500 dark:text-slate-400">Sueldo de referencia</div>
                    <div class="text-2xl font-bold" id="sou-base">—</div>
                    <div class="text-xs text-gray-500 dark:text-slate-400" id="sou-origen"></div>
                </div>
                <div class="text-right">
                    <div class="text-xs text-gray-500 dark:text-slate-400">Ingresos reales del mes</div>
                    <div class="text-lg font-semibold" id="ingressos-reals">—</div>
                    <div class="text-xs" id="sou-desviacio"></div>
                </div>
                <div class="text-right">
                    <div class="text-xs text-gray-500 dark:text-slate-400">Repartido</div>
                    <div class="text-lg font-semibold" id="percentatge-assignat">—</div>
                    <button id="edit-income-btn" class="text-xs text-primary hover:underline mt-1">Ajustar sueldo de este mes</button>
                </div>
            </div>
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
                        <label class="block text-sm font-medium mb-1" for="budget-mode">Cómo se fija el techo</label>
                        <select id="budget-mode" class="w-full px-3 py-2 border rounded-lg">
                            <option value="AMOUNT">Importe fijo</option>
                            <option value="PERCENT">Porcentaje del sueldo</option>
                        </select>
                    </div>
                    <div id="budget-limit-wrapper">
                        <label class="block text-sm font-medium mb-1" for="budget-limit">Límite mensual</label>
                        <input type="number" id="budget-limit" step="0.01" class="w-full px-3 py-2 border rounded-lg">
                    </div>
                    <div id="budget-percent-wrapper" style="display: none;">
                        <label class="block text-sm font-medium mb-1" for="budget-percent">Porcentaje del sueldo</label>
                        <input type="number" id="budget-percent" step="0.01" min="0" max="100" class="w-full px-3 py-2 border rounded-lg">
                        <p class="text-xs text-gray-500 dark:text-slate-400 mt-1" id="budget-percent-hint"></p>
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
    document.getElementById('budget-mode').addEventListener('change', applyModeToForm);
    document.getElementById('budget-percent').addEventListener('input', updatePercentHint);
    document.getElementById('edit-income-btn').addEventListener('click', editMonthlyIncome);
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
        lastSummary = summary;
        renderHeader(summary);
        renderSummary(summary.grups, budgets);
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

/**
 * Capçalera amb el sou de referència, els ingressos reals i quant s'ha
 * repartit. Sense la base, un sostre calculat per percentatge no es pot
 * interpretar.
 */
function renderHeader(summary) {
    const base = Number.parseFloat(summary.sou_base);
    const real = Number.parseFloat(summary.ingressos_reals) || 0;
    const assigned = Number.parseFloat(summary.percentatge_assignat) || 0;

    const baseEl = document.getElementById('sou-base');
    const originEl = document.getElementById('sou-origen');

    if (Number.isFinite(base) && base > 0) {
        baseEl.textContent = formatCurrency(base);
        originEl.textContent = summary.sou_base_origen === 'MES'
            ? 'ajustado para este mes'
            : 'sueldo por defecto';
    } else {
        baseEl.textContent = 'sin definir';
        originEl.textContent = 'configúralo para usar porcentajes';
    }

    document.getElementById('ingressos-reals').textContent = formatCurrency(real);

    const deviation = document.getElementById('sou-desviacio');
    if (Number.isFinite(base) && base > 0 && real > 0) {
        const diff = real - base;
        deviation.textContent = diff === 0
            ? 'coincide con el previsto'
            : `${diff > 0 ? '+' : ''}${formatCurrency(diff)} sobre el previsto`;
        deviation.className = `text-xs ${diff < 0 ? 'text-orange-600' : 'text-green-600'}`;
    } else {
        deviation.textContent = '';
        deviation.className = 'text-xs';
    }

    // Repartir más del 100% del sueldo es un error de planificación, no del
    // programa: se avisa pero no se impide.
    const assignedEl = document.getElementById('percentatge-assignat');
    assignedEl.textContent = `${assigned.toFixed(0)}%`;
    assignedEl.className = assigned > 100
        ? 'text-lg font-semibold text-red-600'
        : 'text-lg font-semibold';
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
                            ${node.percentatge != null
                                ? ` · ${Number.parseFloat(node.percentatge).toFixed(0)}% del sueldo`
                                : ''}
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

/** Mostra el camp que toca segons si el sostre és un import o un percentatge. */
function applyModeToForm() {
    const percent = document.getElementById('budget-mode').value === 'PERCENT';
    document.getElementById('budget-limit-wrapper').style.display = percent ? 'none' : '';
    document.getElementById('budget-percent-wrapper').style.display = percent ? '' : 'none';
    document.getElementById('budget-limit').required = !percent;
    document.getElementById('budget-percent').required = percent;
    updatePercentHint();
}

/** Tradueix el percentatge a euros mentre s'escriu, per no anar a cegues. */
function updatePercentHint() {
    const hint = document.getElementById('budget-percent-hint');
    const percent = Number.parseFloat(document.getElementById('budget-percent').value);
    const base = Number.parseFloat(lastSummary?.sou_base);

    if (!Number.isFinite(percent)) { hint.textContent = ''; return; }
    if (!Number.isFinite(base) || base <= 0) {
        hint.textContent = 'Configura el sueldo para ver a cuánto equivale.';
        return;
    }
    hint.textContent = `${formatCurrency(base * percent / 100)} con el sueldo de este mes.`;
}

/** Sou d'aquest mes concret; buit vol dir "torna al sou per defecte". */
async function editMonthlyIncome() {
    const { year, month } = selectedPeriod();
    const period = `${year}-${String(month).padStart(2, '0')}`;
    const current = lastSummary?.sou_base_origen === 'MES'
        ? Number.parseFloat(lastSummary.sou_base)
        : '';

    const input = prompt(
        `Sueldo para ${period}.
Déjalo vacío para volver al sueldo por defecto.`,
        current === '' ? '' : String(current));
    if (input === null) return;

    try {
        if (input.trim() === '') {
            await deleteMonthlyIncome(period);
        } else {
            const amount = Number.parseFloat(input.replace(',', '.'));
            if (!Number.isFinite(amount) || amount < 0) {
                alert('Introduce un importe positivo.');
                return;
            }
            await setMonthlyIncome(period, { import: amount });
        }
        await loadSummary();
    } catch (error) {
        alert(error.message || 'Error al guardar el sueldo del mes');
    }
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
        document.getElementById('budget-percent').value = budget.percentatge ?? '';
        document.getElementById('budget-mode').value = budget.percentatge != null ? 'PERCENT' : 'AMOUNT';
        document.getElementById('budget-start').value = budget.periode_inici;
        document.getElementById('budget-end').value = budget.periode_fi;
    } else {
        title.textContent = 'Nuevo Presupuesto';
        document.getElementById('budget-id').value = '';
        document.getElementById('budget-mode').value = 'AMOUNT';
        if (presetCategoryId) {
            document.getElementById('budget-category').value = presetCategoryId;
        }
    }

    applyModeToForm();

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
    const byPercent = document.getElementById('budget-mode').value === 'PERCENT';
    const percent = Number.parseFloat(document.getElementById('budget-percent').value);
    const base = Number.parseFloat(lastSummary?.sou_base);

    if (byPercent && (!Number.isFinite(percent) || percent < 0 || percent > 100)) {
        alert('El porcentaje debe estar entre 0 y 100.');
        return;
    }

    // quantitat_limit es NOT NULL en la base de datos, así que un presupuesto
    // por porcentaje guarda el importe ya calculado: la tabla sigue siendo
    // legible por sí sola y el techo real se recalcula en cada consulta.
    const limit = byPercent
        ? (Number.isFinite(base) && base > 0 ? Number((base * percent / 100).toFixed(2)) : 0)
        : parseFloat(document.getElementById('budget-limit').value);

    const data = {
        category: { id: parseInt(document.getElementById('budget-category').value) },
        quantitat_limit: limit,
        periode_inici: document.getElementById('budget-start').value,
        periode_fi: document.getElementById('budget-end').value,
        actiu: true
    };

    if (byPercent) {
        data.percentatge = percent;
    } else if (id) {
        // Solo al actualizar: negativo es el convenio de la API para
        // "quítamelo", que la actualización parcial distingue de "no lo he
        // enviado". Al crear no aplica, y un negativo violaría el CHECK
        // de la columna.
        data.percentatge = -1;
    }

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
