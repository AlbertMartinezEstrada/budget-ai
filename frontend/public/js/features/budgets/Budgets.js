import {
    getBudgets, getBudget, createBudget, updateBudget, deleteBudget,
    getBudgetMonthlySummary, setMonthlyIncome, deleteMonthlyIncome,
    copyPreviousMonthBudgets, getCategories, formatCurrency, escapeHtml,
    getFixedCosts, createFixedCost, updateFixedCost, deleteFixedCost
} from '../../api.js';
import { categoryOptions, sectionLabel, EXPENSE_SECTIONS } from '../../categoryOptions.js';
import { t, formatPercent, formatMonth, formatMonthName } from '../../i18n.js';

// Tailwind no pot generar classes construïdes en temps d'execució
// (`bg-${color}-500`), així que s'enumeren senceres.
const BAR_CLASSES = { low: 'bg-green-500', medium: 'bg-orange-500', high: 'bg-red-500' };
const TEXT_CLASSES = { low: 'text-green-700', medium: 'text-orange-700', high: 'text-red-600' };

// Les tres seccions es pinten diferent a propòsit: la primera cosa que s'ha de
// veure en obrir la pantalla és d'on venen els diners, on acaba el que està
// compromès i on comença el que es pot moure.
const SECTIONS = {
    FIXED: {
        descripcio: 'budgets.section-fixed-description',
        accent: 'border-l-4 border-blue-500',
        pastilla: 'bg-blue-100 text-blue-700 dark:bg-blue-500/20 dark:text-blue-300',
        barra: 'bg-blue-500',
        buit: 'budgets.section-fixed-empty'
    },
    VARIABLE: {
        descripcio: 'budgets.section-variable-description',
        accent: 'border-l-4 border-violet-500',
        pastilla: 'bg-violet-100 text-violet-700 dark:bg-violet-500/20 dark:text-violet-300',
        barra: 'bg-violet-500',
        buit: 'budgets.section-variable-empty'
    },
    // El ahorro se reparte del mismo bote que los variables (con su porcentaje,
    // como siempre), pero no es gasto: cuenta lo que va a sus categorías, esté
    // marcado como traspaso o no. Lo que se paga después desde una cuenta de
    // ahorro es «gastado del fondo» y sale aparte.
    SAVINGS: {
        descripcio: 'budgets.section-savings-description',
        accent: 'border-l-4 border-teal-500',
        pastilla: 'bg-teal-100 text-teal-700 dark:bg-teal-500/20 dark:text-teal-300',
        barra: 'bg-teal-500',
        buit: 'budgets.section-savings-empty',
        esEstalvi: true
    },
    // Los ingresos no se reparten: son de donde sale todo lo demás, así que su
    // total es justo lo que las otras dos secciones tienen para repartir. Por
    // eso van primero y no llevan bote ni barra de reparto.
    INCOME: {
        descripcio: 'budgets.section-income-description',
        accent: 'border-l-4 border-emerald-500',
        pastilla: 'bg-emerald-100 text-emerald-700 dark:bg-emerald-500/20 dark:text-emerald-300',
        barra: 'bg-emerald-500',
        buit: 'budgets.section-income-empty',
        esIngres: true
    }
};

const FREQUENCIES = {
    MENSUAL: 'frequency.monthly',
    TRIMESTRAL: 'frequency.quarterly',
    ANUAL: 'frequency.yearly',
    SETMANAL: 'frequency.weekly',
    DIARIA: 'frequency.daily'
};

/**
 * Els bots dels quals penja un percentatge. Cadascun té dues formes perquè
 * «de» s'ajunta amb l'article de manera diferent a cada idioma: «dels
 * ingressos», «de los ingresos», «das receitas», «of income».
 */
const POT_KEYS = {
    income: { name: 'budgets.pot-income', of: 'budgets.pot-income-of' },
    forecast: { name: 'budgets.pot-forecast', of: 'budgets.pot-forecast-of' },
    afterFixed: { name: 'budgets.pot-after-fixed', of: 'budgets.pot-after-fixed-of' },
    above: { name: 'budgets.pot-above', of: 'budgets.pot-above-of' }
};
const POT_ABOVE = { kind: 'above' };

/** El nom del bot: «els ingressos» o el nom del bloc. */
const potName = (pot) => pot.kind === 'category' ? pot.name : t(POT_KEYS[pot.kind].name);

/** «dels ingressos», «de Gast mensual», «d'Estalvis»: en català, s'apostrofa davant de vocal. */
function potOf(pot) {
    if (pot.kind !== 'category') return t(POT_KEYS[pot.kind].of);
    return t(/^h?[aeiouàèéíòóú]/i.test(pot.name) ? 'budgets.pot-category-of-vowel' : 'budgets.pot-category-of', { name: pot.name });
}

const selectedMonthLabel = () => {
    const { year, month } = selectedPeriod();
    return formatMonth(year, month);
};

let categories = [];
// Els blocs desplegats es recorden entre recàrregues perquè guardar un
// pressupost no plegui el que l'usuari estava mirant.
const expandedGroups = new Set();
let lastSummary = null;

// Sobre quin bot es mesura el percentatge de cada categoria, i com se'n diu.
// El backend ho envia a cada node; aquí s'indexa per poder-ho ensenyar al
// formulari, que només sap l'identificador de la categoria.
let potByCategory = new Map();

export async function initBudgets(container) {
    const now = new Date();

    container.innerHTML = `
        <div class="page-header flex flex-wrap justify-between items-center gap-3 mb-6">
            <div>
                <h2 class="text-2xl font-bold text-slate-800 dark:text-slate-100">${t('nav.budgets')}</h2>
                <p class="text-sm text-gray-500 dark:text-slate-400 mt-1">${t('budgets.subtitle')}</p>
            </div>
            <div class="flex items-center gap-2">
                <select id="budget-year" class="px-3 py-2 border rounded-lg bg-white dark:bg-slate-800 dark:border-slate-600"></select>
                <select id="budget-month" class="px-3 py-2 border rounded-lg bg-white dark:bg-slate-800 dark:border-slate-600">
                    ${Array.from({ length: 12 }, (unused, monthIndex) => `<option value="${monthIndex + 1}">${formatMonthName(monthIndex + 1)}</option>`).join('')}
                </select>
                <button id="fixed-costs-btn" class="px-3 py-2 border rounded-lg hover:bg-gray-50 dark:hover:bg-slate-700 dark:border-slate-600 flex items-center gap-2"
                        title="${t('budgets.recurring-button-title')}">
                    <span class="material-symbols-outlined">event_repeat</span>
                    ${t('budgets.recurring-button')}
                </button>
                <button id="copy-month-btn" class="px-3 py-2 border rounded-lg hover:bg-gray-50 dark:hover:bg-slate-700 dark:border-slate-600 flex items-center gap-2"
                        title="${t('budgets.copy-month-title')}">
                    <span class="material-symbols-outlined">content_copy</span>
                    ${t('budgets.copy-month')}
                </button>
                <button id="add-budget-btn" class="bg-primary text-white px-4 py-2 rounded-lg hover:bg-primary/90 flex items-center gap-2">
                    <span class="material-symbols-outlined">add</span>
                    ${t('budgets.assign')}
                </button>
            </div>
        </div>

        <div id="salary-header" class="bg-white dark:bg-slate-800 rounded-xl p-5 shadow-sm border border-slate-200 dark:border-slate-700 mb-6"></div>

        <div id="budgets-list" class="space-y-6"></div>

        <!-- Costos fixos -->
        <div id="fixed-costs-modal" class="fixed inset-0 bg-black/50 hidden items-center justify-center z-50 p-4">
            <div class="bg-white dark:bg-slate-800 rounded-xl p-6 w-full max-w-2xl max-h-full overflow-y-auto">
                <div class="flex justify-between items-start gap-4 mb-1">
                    <h3 class="text-xl font-bold">${t('budgets.recurring-title')}</h3>
                    <button type="button" id="fixed-costs-close" class="p-1 hover:bg-gray-100 dark:hover:bg-slate-700 rounded" title="${t('common.close')}">
                        <span class="material-symbols-outlined">close</span>
                    </button>
                </div>
                <p class="text-sm text-gray-500 dark:text-slate-400 mb-4" id="fixed-costs-subtitle"></p>

                <div id="fixed-costs-list" class="space-y-2 mb-4"></div>

                <form id="fixed-cost-form" class="rounded-lg bg-slate-50 dark:bg-slate-700/50 p-4 space-y-3">
                    <input type="hidden" id="fixed-cost-id">
                    <div class="font-semibold text-sm" id="fixed-cost-form-title">${t('budgets.recurring-add-title')}</div>
                    <div class="grid grid-cols-1 sm:grid-cols-2 gap-3">
                        <div>
                            <label class="block text-sm font-medium mb-1" for="fixed-cost-name">${t('common.name')}</label>
                            <input type="text" id="fixed-cost-name" required maxlength="150" class="w-full px-3 py-2 border rounded-lg bg-white dark:bg-slate-700 dark:border-slate-600">
                        </div>
                        <div>
                            <label class="block text-sm font-medium mb-1" for="fixed-cost-category">${t('common.category')}</label>
                            <select id="fixed-cost-category" required class="w-full px-3 py-2 border rounded-lg bg-white dark:bg-slate-700 dark:border-slate-600"></select>
                        </div>
                        <div>
                            <label class="block text-sm font-medium mb-1" for="fixed-cost-amount">${t('common.amount')}</label>
                            <input type="number" id="fixed-cost-amount" required step="0.01" min="0" class="w-full px-3 py-2 border rounded-lg bg-white dark:bg-slate-700 dark:border-slate-600">
                        </div>
                        <div>
                            <label class="block text-sm font-medium mb-1" for="fixed-cost-frequency">${t('budgets.how-often')}</label>
                            <select id="fixed-cost-frequency" class="w-full px-3 py-2 border rounded-lg bg-white dark:bg-slate-700 dark:border-slate-600">
                                ${Object.entries(FREQUENCIES).map(([value, key]) => `<option value="${value}">${t(key)}</option>`).join('')}
                            </select>
                        </div>
                    </div>
                    <p class="text-xs text-gray-500 dark:text-slate-400" id="fixed-cost-hint"></p>
                    <div class="flex gap-2 justify-end">
                        <button type="button" id="fixed-cost-cancel" class="px-4 py-2 border rounded-lg hover:bg-gray-50 dark:hover:bg-slate-700 dark:border-slate-600 hidden">${t('common.cancel')}</button>
                        <button type="submit" class="bg-primary text-white px-4 py-2 rounded-lg hover:bg-primary/90" id="fixed-cost-submit">${t('common.add')}</button>
                    </div>
                </form>
            </div>
        </div>

        <!-- Modal -->
        <div id="budget-modal" class="fixed inset-0 bg-black/50 hidden items-center justify-center z-50 p-4">
            <div class="bg-white dark:bg-slate-800 rounded-xl p-6 w-full max-w-md max-h-full overflow-y-auto">
                <h3 class="text-xl font-bold mb-1" id="modal-title">${t('budgets.assign-title')}</h3>
                <p class="text-sm text-gray-500 dark:text-slate-400 mb-4" id="modal-subtitle"></p>
                <form id="budget-form" class="space-y-4">
                    <input type="hidden" id="budget-id">
                    <div>
                        <label class="block text-sm font-medium mb-1" for="budget-category">${t('common.category')}</label>
                        <select id="budget-category" required class="w-full px-3 py-2 border rounded-lg bg-white dark:bg-slate-700 dark:border-slate-600"></select>
                    </div>
                    <div class="rounded-lg bg-slate-50 dark:bg-slate-700/50 p-3 text-sm" id="budget-pot">
                        <div class="text-xs text-gray-500 dark:text-slate-400">${t('budgets.pot-label')}</div>
                        <div class="font-semibold" id="budget-pot-label">—</div>
                    </div>
                    <div>
                        <label class="block text-sm font-medium mb-1" for="budget-mode">${t('budgets.mode')}</label>
                        <select id="budget-mode" class="w-full px-3 py-2 border rounded-lg bg-white dark:bg-slate-700 dark:border-slate-600">
                            <option value="AMOUNT">${t('budgets.mode-amount')}</option>
                            <option value="PERCENT">${t('budgets.mode-percent')}</option>
                        </select>
                    </div>
                    <div id="budget-limit-wrapper">
                        <label class="block text-sm font-medium mb-1" for="budget-limit">${t('budgets.monthly-amount')}</label>
                        <input type="number" id="budget-limit" step="0.01" min="0" class="w-full px-3 py-2 border rounded-lg bg-white dark:bg-slate-700 dark:border-slate-600">
                        <p class="text-xs text-gray-500 dark:text-slate-400 mt-1" id="budget-limit-hint"></p>
                    </div>
                    <div id="budget-percent-wrapper" style="display: none;">
                        <label class="block text-sm font-medium mb-1" for="budget-percent">${t('budgets.percent')}</label>
                        <input type="number" id="budget-percent" step="0.01" min="0" max="100" class="w-full px-3 py-2 border rounded-lg bg-white dark:bg-slate-700 dark:border-slate-600">
                        <p class="text-xs text-gray-500 dark:text-slate-400 mt-1" id="budget-percent-hint"></p>
                    </div>
                    <div class="grid grid-cols-2 gap-4">
                        <div>
                            <label class="block text-sm font-medium mb-1" for="budget-start">${t('budgets.start')}</label>
                            <input type="date" id="budget-start" required class="w-full px-3 py-2 border rounded-lg bg-white dark:bg-slate-700 dark:border-slate-600">
                        </div>
                        <div>
                            <label class="block text-sm font-medium mb-1" for="budget-end">${t('budgets.end')}</label>
                            <input type="date" id="budget-end" required class="w-full px-3 py-2 border rounded-lg bg-white dark:bg-slate-700 dark:border-slate-600">
                        </div>
                    </div>
                    <div class="flex gap-2 justify-end">
                        <button type="button" id="cancel-btn" class="px-4 py-2 border rounded-lg hover:bg-gray-50 dark:hover:bg-slate-700 dark:border-slate-600">${t('common.cancel')}</button>
                        <button type="submit" class="bg-primary text-white px-4 py-2 rounded-lg hover:bg-primary/90">${t('common.save')}</button>
                    </div>
                </form>
            </div>
        </div>
    `;

    const yearSelect = document.getElementById('budget-year');
    for (let year = now.getFullYear(); year >= now.getFullYear() - 5; year--) {
        const option = document.createElement('option');
        option.value = year;
        option.textContent = year;
        yearSelect.appendChild(option);
    }
    // El valor s'assigna després de crear les opcions, no abans.
    yearSelect.value = now.getFullYear();
    document.getElementById('budget-month').value = now.getMonth() + 1;

    await loadCategories();
    await loadSummary();

    document.getElementById('add-budget-btn').addEventListener('click', () => openModal());
    document.getElementById('copy-month-btn').addEventListener('click', copyPreviousMonth);
    document.getElementById('fixed-costs-btn').addEventListener('click', openFixedCosts);
    document.getElementById('fixed-costs-close').addEventListener('click', closeFixedCosts);
    document.getElementById('fixed-costs-modal').addEventListener('click', (event) => {
        if (event.target.id === 'fixed-costs-modal') closeFixedCosts();
    });
    document.getElementById('fixed-costs-list').addEventListener('click', handleFixedCostListClick);
    document.getElementById('fixed-cost-form').addEventListener('submit', handleFixedCostSubmit);
    document.getElementById('fixed-cost-cancel').addEventListener('click', resetFixedCostForm);
    document.getElementById('cancel-btn').addEventListener('click', () => closeModal());
    document.getElementById('budget-form').addEventListener('submit', handleSubmit);
    document.getElementById('budgets-list').addEventListener('click', handleListClick);
    document.getElementById('salary-header').addEventListener('click', handleHeaderClick);
    document.getElementById('budget-year').addEventListener('change', loadSummary);
    document.getElementById('budget-month').addEventListener('change', loadSummary);
    document.getElementById('budget-modal').addEventListener('click', (event) => {
        if (event.target.id === 'budget-modal') closeModal();
    });
    document.getElementById('budget-mode').addEventListener('change', applyModeToForm);
    document.getElementById('budget-category').addEventListener('change', refreshPot);
    document.getElementById('budget-percent').addEventListener('input', updateHints);
    document.getElementById('budget-limit').addEventListener('input', updateHints);
}

function selectedPeriod() {
    return {
        year: Number.parseInt(document.getElementById('budget-year').value, 10),
        month: Number.parseInt(document.getElementById('budget-month').value, 10)
    };
}

const toNumber = (value) => {
    const parsed = Number.parseFloat(value);
    return Number.isFinite(parsed) ? parsed : null;
};

/**
 * Si un presupuesto está vigente en el mes que se está mirando.
 *
 * Mismo criterio que el backend: cuenta cualquier periodo que solape el mes, no
 * solo el que coincida exacto, para que uno trimestral o anual también salga.
 */
function coversMonth(budget) {
    const { year, month } = selectedPeriod();
    const first = `${year}-${String(month).padStart(2, '0')}-01`;
    const last = `${year}-${String(month).padStart(2, '0')}-31`;
    return budget.periode_inici <= last && budget.periode_fi >= first;
}

async function loadCategories() {
    try {
        categories = await getCategories();

        // Aquí sí que es pot triar un bloc: un pressupost posat al bloc és el
        // sostre del conjunt.
        document.getElementById('budget-category').innerHTML = categoryOptions(categories, { groups: true });
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
        indexPots(summary);
        renderHeader(summary);
        renderSections(summary, budgets);
    } catch (error) {
        console.error('Error loading budgets:', error);
        container.innerHTML = `<p class="text-red-600">${t('budgets.load-failed')}</p>`;
    }
}

/**
 * De quin bot penja cada categoria.
 *
 * Un percentatge sense saber de què és un percentatge no vol dir res: "30%" pot
 * ser 810 € o 216 € segons el nivell. El formulari ho ha de poder dir abans de
 * desar, i per això es recorre l'arbre una vegada i es guarda.
 */
function indexPots(summary) {
    potByCategory = new Map();

    for (const section of summary.seccions || []) {
        const pot = section.tipus === 'FIXED'
            ? { kind: 'income' }
            : section.tipus === 'INCOME'
                // Los ingresos no cuelgan de ningún bote: no se reparten.
                ? { kind: 'forecast' }
                // Variables y ahorro se reparten el mismo bote.
                : { kind: 'afterFixed' };

        for (const node of section.grups || []) {
            walkPots(node, pot, section.base);
        }
    }
}

function walkPots(node, parentPot, potAmount) {
    potByCategory.set(node.categoria.id, {
        pot: parentPot,
        base: toNumber(node.base_assignacio) ?? potAmount,
        // El que val el mes segons els seus recurrents, sigui fixa o variable.
        // És el punt de partida quan es canvia l'import només d'aquest mes.
        fixedCost: toNumber(node.prorrateig_mensual)
    });

    // Els fills es reparteixen el que li ha tocat al pare, sempre que el pare
    // tingui una xifra pròpia. Si no en té, hereten el seu mateix bot: és el
    // que fa el backend, i el formulari ha de dir el mateix que el càlcul.
    //
    // Un percentatge sense base tampoc compta com a xifra pròpia: el backend
    // no en pot treure cap import, així que el node es queda sense assignació.
    const explicit = node.percentatge != null
        ? node.base_assignacio != null
        : node.quantitat_limit != null;
    const childPot = explicit ? { kind: 'category', name: node.categoria.nom } : parentPot;
    const childAmount = explicit ? toNumber(node.cost_vida_pla) : potAmount;

    for (const child of node.subcategories || []) {
        walkPots(child, childPot, childAmount);
    }
}

/**
 * Capçalera: els ingressos del mes, quant se n'ha repartit i quant queda lliure.
 *
 * És el bot del qual pengen tots els altres, així que va a dalt de tot i sol:
 * sense la base, cap percentatge de la pantalla es pot interpretar.
 */
function renderHeader(summary) {
    const real = toNumber(summary.ingressos_reals) || 0;
    const forecast = toNumber(summary.ingressos_previstos) || 0;
    // Lo que hay para repartir es la suma de los ingresos. El sueldo no es la
    // base del presupuesto: es uno de los bloques de ingreso, al lado de un
    // regalo o de un trabajo puntual.
    const base = toNumber(summary.total_disponible);
    const fromIncome = summary.total_disponible_origen === 'INGRESSOS';
    const assigned = toNumber(summary.total_assignat) || 0;
    const share = toNumber(summary.percentatge_assignat);
    const free = base != null ? base - assigned : null;

    if (base == null || base <= 0) {
        document.getElementById('salary-header').innerHTML = `
            <div class="text-center py-2">
                <div class="text-lg font-semibold mb-1">${t('budgets.no-income-title')}</div>
                <p class="text-sm text-gray-500 dark:text-slate-400 mb-3">${t('budgets.no-income-hint')}</p>
                <button data-action="edit-income" class="bg-primary text-white px-4 py-2 rounded-lg hover:bg-primary/90">
                    ${t('budgets.set-salary')}
                </button>
            </div>`;
        return;
    }

    const level = share == null ? 'low' : share > 100 ? 'high' : share > 90 ? 'medium' : 'low';
    const width = Math.min(base > 0 ? (assigned / base) * 100 : 0, 100);

    document.getElementById('salary-header').innerHTML = `
        <div class="flex flex-wrap justify-between items-start gap-4 mb-4">
            <div>
                <div class="text-xs uppercase tracking-wide text-gray-500 dark:text-slate-400">${t('budgets.month-income')}</div>
                <div class="text-3xl font-bold">${formatCurrency(base)}</div>
                <div class="text-xs text-gray-500 dark:text-slate-400">
                    ${fromIncome
                        ? `${t('budgets.income-from-blocks')}${real > 0 ? ` · ${escapeHtml(t('budgets.already-received', { amount: formatCurrency(real) }))}` : ` ${t('budgets.forecast-only')}`}
                           · <button data-action="edit-income" class="text-primary hover:underline">${t('budgets.change-salary')}</button>`
                        : `${t(summary.sou_base_origen === 'MES' ? 'budgets.reference-salary-month' : 'budgets.reference-salary-default')}
                           · <button data-action="edit-income" class="text-primary hover:underline">${t('budgets.change')}</button>`}
                </div>
            </div>
            <div class="text-right">
                <div class="text-xs text-gray-500 dark:text-slate-400">${t('budgets.forecast-received')}</div>
                <div class="text-lg font-semibold">
                    ${formatCurrency(forecast)} <span class="text-gray-500">/</span> ${formatCurrency(real)}
                </div>
                <div class="text-xs text-gray-500 dark:text-slate-400">
                    ${real > forecast && forecast > 0
                        ? t('budgets.more-than-forecast')
                        : real === 0 ? t('budgets.no-transactions-yet') : ''}
                </div>
            </div>
        </div>

        <div class="h-3 bg-gray-200 dark:bg-slate-700 rounded-full overflow-hidden mb-2">
            <div class="h-full ${BAR_CLASSES[level]} transition-all" style="width: ${width}%"></div>
        </div>
        <div class="flex flex-wrap justify-between gap-2 text-sm">
            <span class="${TEXT_CLASSES[level]} font-medium">
                ${escapeHtml(t('budgets.assigned-total', { amount: formatCurrency(assigned) }))}${share != null ? ` · ${formatPercent(share)}` : ''}
            </span>
            <span class="text-gray-600 dark:text-slate-300">
                ${free >= 0
                    ? `${t('budgets.unassigned-label')} <strong>${formatCurrency(free)}</strong>`
                    : `<strong class="text-red-600">${escapeHtml(t('budgets.over', { amount: formatCurrency(-free) }))}</strong>`}
            </span>
        </div>
    `;
}

function renderSections(summary, budgets) {
    const container = document.getElementById('budgets-list');
    const sections = summary.seccions || [];

    if (sections.length === 0) {
        container.innerHTML = `
            <p class="text-gray-500 dark:text-slate-400 text-center py-8">${t('categories.empty')}</p>`;
        return;
    }

    // Un mateix bloc pot tenir diversos pressupostos al llarg del temps, i
    // només interessa el que aplica al mes que s'està mirant.
    //
    // Sense filtrar pel període, el botó d'editar d'un bloc obria el
    // pressupost d'un altre mes: el mes de la pantalla deia "sense assignar" i
    // el formulari, en canvi, sortia ple. Pitjor encara, el percentatge es
    // desava calculat sobre el bot del mes que es mirava i no sobre el del seu.
    const budgetByCategory = new Map();
    for (const budget of budgets || []) {
        if (budget.category && coversMonth(budget)) {
            budgetByCategory.set(budget.category.id, budget);
        }
    }

    container.innerHTML = sections
        .map(section => renderSection(section, budgetByCategory, toNumber(summary.total_disponible)))
        .join('');
}

/**
 * @param available lo que queda para repartir. Solo lo usa la sección de
 *        ingresos, que es de donde sale: su cifra de cabecera no es lo previsto
 *        ni lo recibido, sino lo que aporta cada bloque, que es el mayor de los dos.
 */
function renderSection(section, budgetByCategory, available) {
    const style = SECTIONS[section.tipus] || SECTIONS.VARIABLE;
    const pot = toNumber(section.base);
    const assigned = toNumber(section.assignat) || 0;
    const real = toNumber(section.real) || 0;
    const share = toNumber(section.percentatge_del_sou);
    const left = toNumber(section.restant);
    const nodes = section.grups || [];

    const width = pot > 0 ? Math.min((assigned / pot) * 100, 100) : 0;

    return `
        <section class="bg-white dark:bg-slate-800 rounded-xl shadow-sm border border-slate-200 dark:border-slate-700 ${style.accent} overflow-hidden">
            <header class="p-5 pb-4 border-b border-slate-200 dark:border-slate-700">
                <div class="flex flex-wrap justify-between items-start gap-3">
                    <div>
                        <h3 class="text-xl font-bold">${escapeHtml(sectionLabel(section.tipus))}</h3>
                        <p class="text-xs text-gray-500 dark:text-slate-400 mt-0.5">${t(style.descripcio)}</p>
                    </div>
                    <div class="text-right">
                        <div class="text-2xl font-bold">${formatCurrency(style.esIngres ? (available || 0) : assigned)}</div>
                        <div class="text-xs text-gray-500 dark:text-slate-400">
                            ${style.esIngres
                                ? escapeHtml(t('budgets.forecast-and-received', { forecast: formatCurrency(assigned), received: formatCurrency(real) }))
                                : share != null
                                    ? escapeHtml(t('budgets.share-of-income', { percent: formatPercent(share) }))
                                    : t('budgets.no-income-defined')}
                        </div>
                        ${style.esEstalvi ? `
                            <div class="text-xs text-teal-700 dark:text-teal-300">
                                ${escapeHtml(t('budgets.saved-amount', { amount: formatCurrency(real) }))}${toNumber(section.gastat_del_fons) > 0
                                    ? ` · ${escapeHtml(t('budgets.fund-spent', { amount: formatCurrency(toNumber(section.gastat_del_fons)) }))}`
                                    : ''}
                            </div>` : ''}
                    </div>
                </div>

                ${!style.esIngres && pot != null ? `
                    <div class="mt-3">
                        <div class="h-2 bg-gray-200 dark:bg-slate-700 rounded-full overflow-hidden">
                            <div class="h-full ${style.barra} transition-all" style="width: ${width}%"></div>
                        </div>
                        <div class="flex flex-wrap justify-between gap-2 text-xs mt-1.5">
                            <span class="text-gray-600 dark:text-slate-300">
                                ${t('budgets.there-is')} <strong>${formatCurrency(pot)}</strong>
                                ${t(section.tipus === 'FIXED' ? 'budgets.available-this-month' : 'budgets.after-fixed')}
                            </span>
                            <span class="${left < 0 ? 'text-red-600 font-medium' : 'text-gray-600 dark:text-slate-300'}">
                                ${escapeHtml(left < 0
                                    ? t('budgets.over-pot', { amount: formatCurrency(-left) })
                                    : section.tipus === 'FIXED'
                                        // Lo que no se llevan los fijos no queda libre: es exactamente
                                        // el bote con el que empiezan los variables.
                                        ? t('budgets.passes-to-variable', { amount: formatCurrency(left) })
                                        // El bote es el mismo para variables y ahorro: lo que queda
                                        // es de los dos, y sale igual en las dos secciones.
                                        : t('budgets.left-to-share', { amount: formatCurrency(left) }))}
                            </span>
                        </div>
                    </div>` : ''}
            </header>

            <div class="p-4 space-y-3">
                ${nodes.length === 0
                    ? `<p class="text-sm text-gray-500 dark:text-slate-400 py-2">${t(style.buit)}</p>`
                    : byRelevance(nodes).map(node => renderBlock(node, budgetByCategory, style)).join('')}
            </div>
        </section>
    `;
}

/**
 * El que s'ha gastat de debò: els moviments importats del mes.
 *
 * No és cost_vida_real. Aquell compta un fix pel seu prorrateig encara que no
 * hi hagi cap moviment, així que un lloguer de 800 € sortia "gastat 800 de 800"
 * abans de pujar l'extracte. Aquí el cost fix és el pla, i el gasto el posa el
 * CSV. Als ingressos, caixa_real és el que ha entrat.
 */
const spentOf = (node) => toNumber(node.caixa_real) || 0;

/** Un bloc de primer nivell: Estalvis, Gast mensual, Allotjament… */
function renderBlock(node, budgetByCategory, style) {
    const category = node.categoria;
    const plan = toNumber(node.cost_vida_pla) || 0;
    const real = spentOf(node);
    const children = node.subcategories || [];
    const expanded = expandedGroups.has(category.id);
    const left = toNumber(node.restant);

    const percent = plan > 0 ? Math.min((real / plan) * 100, 100) : 0;
    const level = plan > 0 && real > plan ? 'high' : percent > 80 ? 'medium' : 'low';
    // Ahorrar más de lo previsto no es pasarse: la barra del ahorro no se pone roja.
    const barClass = style.esEstalvi ? style.barra : BAR_CLASSES[level];
    const textClass = style.esEstalvi ? 'text-teal-700 dark:text-teal-300' : TEXT_CLASSES[level];

    return `
        <div class="rounded-lg border border-slate-200 dark:border-slate-700 p-4">
            <div class="flex flex-wrap justify-between items-start gap-3 mb-3">
                <div class="flex items-start gap-2 min-w-0">
                    ${children.length > 0 ? `
                        <button data-action="toggle" data-id="${category.id}"
                                class="p-1 -ml-1 hover:bg-gray-100 dark:hover:bg-slate-700 rounded shrink-0"
                                title="${t(expanded ? 'budgets.collapse' : 'budgets.expand')}">
                            <span class="material-symbols-outlined text-base">${expanded ? 'expand_less' : 'expand_more'}</span>
                        </button>` : '<span class="w-6 shrink-0"></span>'}
                    <div class="min-w-0">
                        <h4 class="font-semibold truncate">${escapeHtml(category.nom)}</h4>
                        <div class="flex flex-wrap items-center gap-1.5 mt-1">
                            ${shareBadge(node, style)}
                            ${children.length > 0
                                ? `<span class="text-xs text-gray-500 dark:text-slate-400">${escapeHtml(t('budgets.subsection-count', { count: children.length }))}</span>`
                                : ''}
                            ${node.carrec_puntual_aquest_mes
                                ? `<span class="text-xs text-orange-700" title="${t('budgets.one-off-charge-title')}">${t('budgets.one-off-charge')}</span>`
                                : ''}
                        </div>
                    </div>
                </div>
                <div class="flex items-center gap-2 shrink-0">
                    <div class="text-right">
                        ${style.esIngres
                            // En un ingreso la cifra que cuenta es lo que aporta al
                            // reparto: lo recibido o lo previsto, lo que sea mayor.
                            // Enseñar solo lo recibido dejaba el bloque a 0 € cuando
                            // la sección de encima ya decía que aportaba miles.
                            ? `<div class="text-lg font-bold">${formatCurrency(Math.max(real, plan))}</div>
                               <div class="text-xs text-gray-500 dark:text-slate-400">
                                   ${plan > 0
                                       ? escapeHtml(t('budgets.forecast-and-received', { forecast: formatCurrency(plan), received: formatCurrency(real) }))
                                       : t('budgets.received')}
                               </div>`
                            : `<div class="text-lg font-bold">${plan > 0 ? formatCurrency(plan) : '—'}</div>
                               <div class="text-xs text-gray-500 dark:text-slate-400">${t('budgets.assigned')}</div>`}
                    </div>
                    ${actionButtons(node, budgetByCategory)}
                </div>
            </div>

            ${!style.esIngres && plan > 0 ? `
                <div class="h-2 bg-gray-200 dark:bg-slate-700 rounded-full overflow-hidden">
                    <div class="h-full ${barClass} transition-all" style="width: ${percent}%"></div>
                </div>
                <div class="flex flex-wrap justify-between gap-2 text-xs mt-1.5">
                    <span class="${textClass} font-medium">
                        ${escapeHtml(t(style.esEstalvi ? 'budgets.saved-of' : 'budgets.spent-of', { real: formatCurrency(real), plan: formatCurrency(plan) }))}
                        ${fundSpent(node)}
                    </span>
                    ${left != null
                        ? `<span class="${left < 0 ? 'text-red-600' : 'text-gray-500 dark:text-slate-400'}">
                               ${escapeHtml(left < 0
                                    ? t('budgets.over-subsections', { amount: formatCurrency(-left) })
                                    : t('budgets.unshared-inside', { amount: formatCurrency(left) }))}
                           </span>`
                        : ''}
                </div>` : style.esIngres ? `
                <p class="text-xs text-gray-500 dark:text-slate-400">${t('budgets.income-block-note')}</p>` : `
                <p class="text-xs text-gray-500 dark:text-slate-400">
                    ${t(children.length > 0 ? 'budgets.unassigned-with-children' : 'budgets.unassigned-leaf')}
                </p>`}

            ${children.length > 0 && expanded ? `
                <div class="mt-4 pt-3 border-t border-slate-200 dark:border-slate-700 divide-y divide-slate-100 dark:divide-slate-700/50">
                    ${byRelevance(children).map(child => renderLeaf(child, budgetByCategory, style)).join('')}
                </div>` : ''}
        </div>
    `;
}

/** Una subsecció dins d'un bloc: Bars i restaurants, Oci… */
function renderLeaf(node, budgetByCategory, style) {
    const category = node.categoria;
    const plan = toNumber(node.cost_vida_pla) || 0;
    const real = spentOf(node);
    const fixed = category.tipus_cost === 'FIXED';

    const percent = plan > 0 ? Math.min((real / plan) * 100, 100) : 0;
    const level = plan > 0 && real > plan ? 'high' : percent > 80 ? 'medium' : 'low';
    const barClass = style.esEstalvi ? style.barra : BAR_CLASSES[level];
    const textClass = style.esEstalvi ? 'text-teal-700 dark:text-teal-300' : TEXT_CLASSES[level];

    // Una subsecció buida s'apaga en comptes d'ocupar el mateix que una amb
    // diners: hi ha catorze i només compten les que tenen alguna cosa.
    const idle = plan === 0 && real === 0;

    return `
        <div class="flex flex-wrap items-center justify-between gap-2 py-2 ${idle ? 'opacity-60' : ''}">
            <div class="min-w-0 flex-1">
                <div class="flex flex-wrap items-center gap-1.5">
                    <span class="text-sm truncate">${escapeHtml(category.nom)}</span>
                    ${fixed ? `<span class="text-xs px-1.5 py-0.5 rounded-full bg-blue-100 text-blue-700 dark:bg-blue-500/20 dark:text-blue-300">${t('categories.badge-fixed')}</span>` : ''}
                    ${toNumber(node.quotes_deutes) > 0
                        ? `<span class="text-xs px-1.5 py-0.5 rounded-full bg-amber-100 text-amber-800 dark:bg-amber-500/20 dark:text-amber-300"
                                 title="${t('budgets.debt-quotas-title')}">
                               ${escapeHtml(t('budgets.debt-quotas', { amount: formatCurrency(toNumber(node.quotes_deutes)) }))}
                           </span>`
                        : ''}
                    ${shareBadge(node, style)}
                    ${toNumber(node.aporta_al_disponible) > 0
                        ? `<span class="text-xs px-1.5 py-0.5 rounded-full bg-emerald-100 text-emerald-800 dark:bg-emerald-500/20 dark:text-emerald-300"
                                 title="${t('budgets.contributes-title')}">
                               ${escapeHtml(t('budgets.contributes', { amount: formatCurrency(toNumber(node.aporta_al_disponible)) }))}
                           </span>`
                        : ''}
                </div>
                ${!style.esIngres && plan > 0 ? `
                    <div class="h-1.5 bg-gray-200 dark:bg-slate-700 rounded-full overflow-hidden mt-1.5 max-w-xs">
                        <div class="h-full ${barClass}" style="width: ${percent}%"></div>
                    </div>` : ''}
                ${fundSpent(node) ? `<div class="text-xs mt-1">${fundSpent(node, { inline: false })}</div>` : ''}
            </div>
            <div class="flex items-center gap-2 shrink-0">
                <div class="text-right text-sm">
                    ${style.esIngres
                        ? `<div class="font-medium ${real > 0 ? 'text-green-700' : ''}">${formatCurrency(Math.max(real, plan))}</div>
                           <div class="text-xs text-gray-500 dark:text-slate-400">
                               ${plan > 0
                                   ? escapeHtml(t('budgets.forecast-and-received', { forecast: formatCurrency(plan), received: formatCurrency(real) }))
                                   : t('budgets.received')}
                           </div>`
                        : plan > 0
                            ? `<div class="font-medium">${formatCurrency(plan)}</div>
                               <div class="text-xs ${textClass}">${escapeHtml(t(style.esEstalvi ? 'budgets.saved-amount' : 'budgets.spent-amount', { amount: formatCurrency(real) }))}</div>`
                            : real > 0
                                ? `<div class="font-medium ${style.esEstalvi ? 'text-teal-700 dark:text-teal-300' : 'text-orange-700'}">${formatCurrency(real)}</div>
                                   <div class="text-xs text-gray-500 dark:text-slate-400">${t(style.esEstalvi ? 'budgets.saved-unassigned' : 'budgets.spent-unassigned')}</div>`
                                : `<div class="text-xs text-gray-500 dark:text-slate-400">${t('budgets.not-assigned')}</div>`}
                </div>
                ${actionButtons(node, budgetByCategory)}
            </div>
        </div>
    `;
}

/**
 * Lo que se ha pagado con el dinero apartado: un regalo con lo de «Regals i
 * altres». Es gasto, pero del fondo y no del mes, así que no resta de lo
 * ahorrado: sale al lado.
 */
function fundSpent(node, { inline = true } = {}) {
    const spent = toNumber(node.gastat_del_fons) || 0;
    if (spent <= 0) return '';
    return `<span class="text-gray-500 dark:text-slate-400 font-normal"
                  title="${t('budgets.fund-spent-title')}">
                ${inline ? '· ' : ''}${escapeHtml(t('budgets.fund-spent', { amount: formatCurrency(spent) }))}
            </span>`;
}

/**
 * Els nodes amb diners assignats van primer.
 *
 * El backend els ordena per nom, que és l'ordre correcte per triar-ne un. Però
 * aquí la pregunta és una altra —on van els diners—, i amb catorze
 * subseccions, dotze de les quals a zero, les tres que importen quedaven
 * enterrades enmig de la llista.
 */
function byRelevance(nodes) {
    return [...(nodes || [])].sort((firstNode, secondNode) => {
        const firstPlan = toNumber(firstNode.cost_vida_pla) || 0;
        const secondPlan = toNumber(secondNode.cost_vida_pla) || 0;
        if (firstPlan !== secondPlan) return secondPlan - firstPlan;
        // A igualtat, el que s'hi ha gastat: un gasto sense pla és justament
        // el que val la pena mirar.
        return spentOf(secondNode) - spentOf(firstNode);
    });
}

/**
 * La pastilla que diu de què és percentatge la xifra.
 *
 * És la peça que abans faltava: "30%" no vol dir res si no se sap si és del
 * sou, del bot de variables o del bloc que té a sobre.
 */
function shareBadge(node, style) {
    const declared = toNumber(node.percentatge);
    const effective = toNumber(node.percentatge_efectiu);
    const share = declared ?? effective;
    // Un "≈0% de Gast mensual" a cada categoria buida no informa de res i tapa
    // les que sí que tenen diners.
    if (share == null || (declared == null && share === 0)) return '';

    const pot = potByCategory.get(node.categoria.id)?.pot || POT_ABOVE;

    // El "≈" distingeix el percentatge que ha triat l'usuari del que surt de
    // dividir un import exacte: el primer es manté si canvia el sou, el segon no.
    return `<span class="text-xs px-2 py-0.5 rounded-full ${style.pastilla}">
                ${declared == null ? '≈' : ''}${formatPercent(share, share % 1 === 0 ? 0 : 1)} ${escapeHtml(potOf(pot))}
            </span>`;
}

function actionButtons(node, budgetByCategory) {
    const budget = budgetByCategory.get(node.categoria.id);
    // Una fulla amb recurrent ja té import sense assignar-li res: el que es fa
    // des d'aquí és canviar-lo només aquest mes, i treure-ho hi torna. Abans
    // només ho feien les fixes; la llum, amb el seu recurrent, semblava buida.
    const hasFixedCost = toNumber(node.prorrateig_mensual) > 0;
    if (budget) {
        return `
            <button data-action="edit" data-id="${budget.id}" class="p-1 hover:bg-gray-100 dark:hover:bg-slate-700 rounded" title="${t('budgets.edit-allocation')}">
                <span class="material-symbols-outlined text-sm">edit</span>
            </button>
            <button data-action="delete" data-id="${budget.id}" data-fixed="${hasFixedCost}" class="p-1 hover:bg-red-50 dark:hover:bg-red-500/10 text-red-600 rounded"
                    title="${t(hasFixedCost ? 'budgets.back-to-recurring' : 'budgets.remove-allocation')}">
                <span class="material-symbols-outlined text-sm">${hasFixedCost ? 'undo' : 'delete'}</span>
            </button>`;
    }
    return `
        <button data-action="create" data-category="${node.categoria.id}" class="p-1 hover:bg-gray-100 dark:hover:bg-slate-700 rounded"
                title="${t(hasFixedCost ? 'budgets.change-this-month' : 'budgets.assign-title')}">
            <span class="material-symbols-outlined text-sm">${hasFixedCost ? 'edit' : 'add'}</span>
        </button>`;
}

// ============ FORMULARI ============

/** El bot sobre el qual es reparteix la categoria que hi ha seleccionada. */
function selectedPot() {
    const id = Number.parseInt(document.getElementById('budget-category').value, 10);
    return potByCategory.get(id) || { pot: POT_ABOVE, base: null };
}

function refreshPot() {
    const pot = selectedPot();
    document.getElementById('budget-pot-label').textContent = pot.base != null
        ? `${formatCurrency(pot.base)} · ${potName(pot.pot)}`
        : t('budgets.pot-no-amount', { pot: potName(pot.pot) });
    updateHints();
}

/** Mostra el camp que toca segons si l'assignació és un import o un percentatge. */
function applyModeToForm() {
    const percent = document.getElementById('budget-mode').value === 'PERCENT';
    document.getElementById('budget-limit-wrapper').style.display = percent ? 'none' : '';
    document.getElementById('budget-percent-wrapper').style.display = percent ? '' : 'none';
    document.getElementById('budget-limit').required = !percent;
    document.getElementById('budget-percent').required = percent;
    updateHints();
}

/** Tradueix la xifra a l'altra unitat mentre s'escriu, per no anar a cegues. */
function updateHints() {
    const pot = selectedPot();
    const percentHint = document.getElementById('budget-percent-hint');
    const amountHint = document.getElementById('budget-limit-hint');

    const percent = toNumber(document.getElementById('budget-percent').value);
    const amount = toNumber(document.getElementById('budget-limit').value);

    if (pot.base == null) {
        percentHint.textContent = t('budgets.no-income-for-amount');
        amountHint.textContent = '';
        return;
    }

    percentHint.textContent = percent == null
        ? ''
        : t('budgets.percent-hint', { amount: formatCurrency(pot.base * percent / 100), of: potOf(pot.pot), base: formatCurrency(pot.base) });

    amountHint.textContent = amount == null || pot.base <= 0
        ? ''
        : t('budgets.amount-hint', { percent: formatPercent(amount / pot.base * 100), of: potOf(pot.pot) });
}

/**
 * Duplica aquí el repartiment del mes anterior.
 *
 * Els percentatges es recalculen sols sobre el bot del mes nou: si el sou
 * canvia, el repartiment s'hi ajusta sense tocar res.
 */
async function copyPreviousMonth() {
    const { year, month } = selectedPeriod();

    if (!confirm(t('budgets.copy-confirm', { month: selectedMonthLabel() }))) return;

    try {
        const result = await copyPreviousMonthBudgets(year, month);
        await loadSummary();
        alert(result.copiats > 0
            ? t('budgets.copied', { count: result.copiats, source: result.origen })
            : t('budgets.nothing-to-copy', { source: result.origen }));
    } catch (error) {
        alert(error.message || t('budgets.copy-failed'));
    }
}

/**
 * Sou d'aquest mes concret; buit vol dir "torna al sou per defecte".
 *
 * El backend el posa també com a previsió de la nòmina del mes, així que no
 * cal entrar-lo dues vegades.
 */
async function editMonthlyIncome() {
    const { year, month } = selectedPeriod();
    const period = `${year}-${String(month).padStart(2, '0')}`;
    const current = lastSummary?.sou_base_origen === 'MES'
        ? toNumber(lastSummary.sou_base)
        : '';

    const input = prompt(t('budgets.salary-prompt', { month: selectedMonthLabel() }),
        current === '' || current == null ? '' : String(current));
    if (input === null) return;

    try {
        if (input.trim() === '') {
            await deleteMonthlyIncome(period);
        } else {
            const amount = Number.parseFloat(input.replace(',', '.'));
            if (!Number.isFinite(amount) || amount < 0) {
                alert(t('budgets.amount-positive'));
                return;
            }
            await setMonthlyIncome(period, { import: amount });
        }
        await loadSummary();
    } catch (error) {
        alert(error.message || t('budgets.salary-save-failed'));
    }
}

function openModal(budget = null, presetCategoryId = null) {
    const modal = document.getElementById('budget-modal');
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
        document.getElementById('modal-title').textContent = t('budgets.edit-allocation');
        document.getElementById('budget-id').value = budget.id;
        document.getElementById('budget-category').value = budget.category?.id;
        document.getElementById('budget-limit').value = budget.quantitat_limit;
        document.getElementById('budget-percent').value = budget.percentatge ?? '';
        document.getElementById('budget-mode').value = budget.percentatge != null ? 'PERCENT' : 'AMOUNT';
        document.getElementById('budget-start').value = budget.periode_inici;
        document.getElementById('budget-end').value = budget.periode_fi;
    } else {
        document.getElementById('modal-title').textContent = t('budgets.assign-title');
        document.getElementById('budget-id').value = '';
        document.getElementById('budget-mode').value = 'AMOUNT';
        if (presetCategoryId) {
            document.getElementById('budget-category').value = presetCategoryId;
        }
    }

    // Si la categoria té cost fix, l'assignació d'aquí el substitueix només
    // aquest mes. Es parteix del mateix import perquè el normal és retocar-lo,
    // no escriure'l de nou.
    const categoryId = Number.parseInt(document.getElementById('budget-category').value, 10);
    const fixedCost = potByCategory.get(categoryId)?.fixedCost;
    if (fixedCost > 0) {
        if (!budget) {
            document.getElementById('modal-title').textContent = t('budgets.change-this-month');
            document.getElementById('budget-limit').value = fixedCost.toFixed(2);
        }
        document.getElementById('modal-subtitle').textContent =
            t('budgets.only-this-month', { month: formatMonth(year, month), amount: formatCurrency(fixedCost) });
    } else {
        document.getElementById('modal-subtitle').textContent = t('budgets.percent-explainer');
    }

    refreshPot();
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

// ============ RECURRENTS ============
//
// La plantilla de cada mes: el que s'hi defineix arriba sol a tots els mesos
// des del que s'està mirant. Canviar l'import d'un sol mes es fa des de la
// fila de la categoria, no d'aquí. Al codi i a l'API es diuen "costos fixos"
// perquè va començar només amb les fulles fixes.

let loadedFixedCosts = [];

async function openFixedCosts() {
    const modal = document.getElementById('fixed-costs-modal');
    document.getElementById('fixed-costs-subtitle').textContent =
        t('budgets.recurring-subtitle', { month: selectedMonthLabel() });

    // Cualquier subcategoría de gasto: antes solo las fijas, y la luz no podía
    // tener el suyo.
    const options = categoryOptions(categories, { sections: EXPENSE_SECTIONS });
    document.getElementById('fixed-cost-category').innerHTML = options
        || `<option value="">${t('budgets.no-expense-subcategories')}</option>`;

    resetFixedCostForm();
    modal.classList.remove('hidden');
    modal.classList.add('flex');
    await loadFixedCosts();
}

function closeFixedCosts() {
    const modal = document.getElementById('fixed-costs-modal');
    modal.classList.add('hidden');
    modal.classList.remove('flex');
}

async function loadFixedCosts() {
    const list = document.getElementById('fixed-costs-list');
    const { year, month } = selectedPeriod();
    try {
        const response = await getFixedCosts(year, month);
        loadedFixedCosts = response.costos || [];
        renderFixedCosts(loadedFixedCosts, toNumber(response.total_mensual) || 0);
    } catch (error) {
        list.innerHTML = `<p class="text-red-600 text-sm">${escapeHtml(error.message || t('recurring.load-failed'))}</p>`;
    }
}

function renderFixedCosts(fixedCosts, monthlyTotal) {
    const list = document.getElementById('fixed-costs-list');
    if (fixedCosts.length === 0) {
        list.innerHTML = `
            <p class="text-sm text-gray-500 dark:text-slate-400 text-center py-4">${t('budgets.no-recurring-this-month')}</p>`;
        return;
    }

    const rows = fixedCosts.map(fixedCost => {
        const frequency = FREQUENCIES[fixedCost.frequencia] ? t(FREQUENCIES[fixedCost.frequencia]) : (fixedCost.frequencia || '');
        const monthly = toNumber(fixedCost.prorrateig_mensual) || 0;
        const isMonthly = fixedCost.frequencia === 'MENSUAL';
        return `
            <div class="flex items-center justify-between gap-3 p-3 rounded-lg border border-slate-200 dark:border-slate-700">
                <div class="min-w-0">
                    <div class="font-medium truncate">${escapeHtml(fixedCost.nom)}</div>
                    <div class="text-xs text-gray-500 dark:text-slate-400">
                        ${escapeHtml(fixedCost.category?.nom || t('budgets.no-category'))}
                        · ${formatCurrency(fixedCost.import)} ${escapeHtml(frequency.toLowerCase())}
                        ${isMonthly ? '' : ` · ${escapeHtml(t('budgets.per-month-amount', { amount: formatCurrency(monthly) }))}`}
                    </div>
                </div>
                <div class="flex items-center gap-1 shrink-0">
                    <span class="font-semibold mr-2">${formatCurrency(monthly)}</span>
                    <button data-action="edit-fixed" data-id="${fixedCost.id}" class="p-1 hover:bg-gray-100 dark:hover:bg-slate-700 rounded" title="${t('budgets.change-from-this-month')}">
                        <span class="material-symbols-outlined text-sm">edit</span>
                    </button>
                    <button data-action="delete-fixed" data-id="${fixedCost.id}" class="p-1 hover:bg-red-50 dark:hover:bg-red-500/10 text-red-600 rounded" title="${t('budgets.remove-from-this-month')}">
                        <span class="material-symbols-outlined text-sm">delete</span>
                    </button>
                </div>
            </div>`;
    }).join('');

    list.innerHTML = rows + `
        <div class="flex justify-between px-3 pt-2 text-sm">
            <span class="text-gray-500 dark:text-slate-400">${t('budgets.monthly-total')}</span>
            <span class="font-bold">${formatCurrency(monthlyTotal)}</span>
        </div>`;
}

function resetFixedCostForm() {
    document.getElementById('fixed-cost-form').reset();
    document.getElementById('fixed-cost-id').value = '';
    document.getElementById('fixed-cost-form-title').textContent = t('budgets.recurring-add-title');
    document.getElementById('fixed-cost-submit').textContent = t('common.add');
    document.getElementById('fixed-cost-cancel').classList.add('hidden');
    document.getElementById('fixed-cost-frequency').value = 'MENSUAL';
    document.getElementById('fixed-cost-hint').textContent = '';
}

async function handleFixedCostListClick(event) {
    const button = event.target.closest('button[data-action]');
    if (!button) return;

    const id = Number.parseInt(button.dataset.id, 10);
    const fixedCost = loadedFixedCosts.find(candidate => candidate.id === id);
    if (!fixedCost) return;
    const { year, month } = selectedPeriod();
    const monthLabel = formatMonth(year, month);

    if (button.dataset.action === 'edit-fixed') {
        document.getElementById('fixed-cost-id').value = fixedCost.id;
        document.getElementById('fixed-cost-name').value = fixedCost.nom;
        document.getElementById('fixed-cost-category').value = fixedCost.category?.id ?? '';
        document.getElementById('fixed-cost-amount').value = fixedCost.import;
        document.getElementById('fixed-cost-frequency').value = fixedCost.frequencia;
        document.getElementById('fixed-cost-form-title').textContent = t('budgets.change-named', { name: fixedCost.nom });
        document.getElementById('fixed-cost-submit').textContent = t('common.save');
        document.getElementById('fixed-cost-cancel').classList.remove('hidden');
        document.getElementById('fixed-cost-hint').textContent = t('budgets.valid-from', { month: monthLabel });
        document.getElementById('fixed-cost-name').focus();
        return;
    }

    if (button.dataset.action === 'delete-fixed') {
        if (!confirm(t('budgets.remove-recurring-confirm', { name: fixedCost.nom, month: monthLabel }))) return;
        try {
            await deleteFixedCost(id, year, month);
            resetFixedCostForm();
            await Promise.all([loadFixedCosts(), loadSummary()]);
        } catch (error) {
            alert(error.message || t('budgets.remove-recurring-failed'));
        }
    }
}

async function handleFixedCostSubmit(event) {
    event.preventDefault();
    const { year, month } = selectedPeriod();
    const id = Number.parseInt(document.getElementById('fixed-cost-id').value, 10);
    const categoryId = Number.parseInt(document.getElementById('fixed-cost-category').value, 10);
    const amount = toNumber(document.getElementById('fixed-cost-amount').value);

    if (!Number.isInteger(categoryId)) {
        alert(t('budgets.create-subcategory-first'));
        return;
    }
    if (amount == null || amount < 0) {
        alert(t('budgets.amount-positive'));
        return;
    }

    const data = {
        nom: document.getElementById('fixed-cost-name').value.trim(),
        category: { id: categoryId },
        import: amount,
        frequencia: document.getElementById('fixed-cost-frequency').value
    };

    try {
        if (Number.isInteger(id)) {
            await updateFixedCost(id, year, month, data);
        } else {
            await createFixedCost(year, month, data);
        }
        resetFixedCostForm();
        await Promise.all([loadFixedCosts(), loadSummary()]);
    } catch (error) {
        alert(error.message || t('recurring.save-failed'));
    }
}

async function handleHeaderClick(event) {
    if (event.target.closest('button[data-action="edit-income"]')) {
        await editMonthlyIncome();
    }
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
            alert(error.message || t('budgets.load-one-failed'));
        }
        return;
    }

    if (action === 'delete') {
        const question = button.dataset.fixed === 'true'
            ? 'budgets.back-to-recurring-confirm'
            : 'budgets.remove-allocation-confirm';
        if (!confirm(t(question))) return;
        try {
            await deleteBudget(id);
            await loadSummary();
        } catch (error) {
            alert(error.message || t('budgets.delete-failed'));
        }
    }
}

async function handleSubmit(event) {
    event.preventDefault();
    const id = document.getElementById('budget-id').value;
    const byPercent = document.getElementById('budget-mode').value === 'PERCENT';
    const percent = toNumber(document.getElementById('budget-percent').value);
    const pot = selectedPot();

    if (byPercent && (percent == null || percent < 0 || percent > 100)) {
        alert(t('budgets.percent-range'));
        return;
    }

    // quantitat_limit es NOT NULL en la base de datos, así que un presupuesto
    // por porcentaje guarda el importe ya calculado sobre el bote de su nivel:
    // la tabla sigue siendo legible por sí sola y el importe real se recalcula
    // en cada consulta.
    const limit = byPercent
        ? (pot.base != null && pot.base > 0 ? Number((pot.base * percent / 100).toFixed(2)) : 0)
        : toNumber(document.getElementById('budget-limit').value);

    if (!byPercent && (limit == null || limit < 0)) {
        alert(t('budgets.amount-positive'));
        return;
    }

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
        alert(error.message || t('budgets.save-failed'));
    }
}
