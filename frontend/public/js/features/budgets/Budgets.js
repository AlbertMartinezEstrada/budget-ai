import {
    getBudgets, getBudget, createBudget, updateBudget, deleteBudget,
    getBudgetMonthlySummary, setMonthlyIncome, deleteMonthlyIncome,
    copyPreviousMonthBudgets, getCategories, formatCurrency, escapeHtml
} from '../../api.js';

// Tailwind no pot generar classes construïdes en temps d'execució
// (`bg-${color}-500`), així que s'enumeren senceres.
const BAR_CLASSES = { low: 'bg-green-500', medium: 'bg-orange-500', high: 'bg-red-500' };
const TEXT_CLASSES = { low: 'text-green-600', medium: 'text-orange-600', high: 'text-red-600' };

// Les tres seccions es pinten diferent a propòsit: la primera cosa que s'ha de
// veure en obrir la pantalla és d'on venen els diners, on acaba el que està
// compromès i on comença el que es pot moure.
const SECTIONS = {
    FIXED: {
        titol: 'Gastos fijos',
        descripcio: 'Importe conocido cada mes. Se reparten primero, sobre el total de ingresos.',
        accent: 'border-l-4 border-blue-500',
        pastilla: 'bg-blue-100 text-blue-700 dark:bg-blue-500/20 dark:text-blue-300',
        barra: 'bg-blue-500',
        buit: 'Ningún bloque es fijo todavía. Un bloque cuenta como fijo cuando todas sus subcategorías están marcadas como «fijo» en Categorías.'
    },
    VARIABLE: {
        titol: 'Gastos variables',
        descripcio: 'Se reparten sobre lo que queda después de los fijos.',
        accent: 'border-l-4 border-violet-500',
        pastilla: 'bg-violet-100 text-violet-700 dark:bg-violet-500/20 dark:text-violet-300',
        barra: 'bg-violet-500',
        buit: 'Todavía no hay bloques variables.'
    },
    // Los ingresos no se reparten: son de donde sale todo lo demás, así que su
    // total es justo lo que las otras dos secciones tienen para repartir. Por
    // eso van primero y no llevan bote ni barra de reparto.
    INCOME: {
        titol: 'Ingresos',
        descripcio: 'De aquí sale todo. El sueldo es un bloque más, al lado de un regalo o un trabajo puntual.',
        accent: 'border-l-4 border-emerald-500',
        pastilla: 'bg-emerald-100 text-emerald-700 dark:bg-emerald-500/20 dark:text-emerald-300',
        barra: 'bg-emerald-500',
        buit: 'Ningún bloque marcado como ingresos.',
        esIngres: true
    }
};

const MONTH_NAMES = ['Enero', 'Febrero', 'Marzo', 'Abril', 'Mayo', 'Junio',
    'Julio', 'Agosto', 'Septiembre', 'Octubre', 'Noviembre', 'Diciembre'];

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
                <h2 class="text-2xl font-bold text-slate-800 dark:text-slate-100">Presupuestos</h2>
                <p class="text-sm text-gray-500 dark:text-slate-400 mt-1">
                    Los ingresos bajan en cascada: primero fijos y variables, luego los bloques, luego sus subsecciones.
                </p>
            </div>
            <div class="flex items-center gap-2">
                <select id="budget-year" class="px-3 py-2 border rounded-lg bg-white dark:bg-slate-800 dark:border-slate-600"></select>
                <select id="budget-month" class="px-3 py-2 border rounded-lg bg-white dark:bg-slate-800 dark:border-slate-600">
                    ${MONTH_NAMES.map((m, i) => `<option value="${i + 1}">${m}</option>`).join('')}
                </select>
                <button id="copy-month-btn" class="px-3 py-2 border rounded-lg hover:bg-gray-50 dark:hover:bg-slate-700 dark:border-slate-600 flex items-center gap-2"
                        title="Duplicar aquí las asignaciones del mes anterior">
                    <span class="material-symbols-outlined">content_copy</span>
                    Copiar mes anterior
                </button>
                <button id="add-budget-btn" class="bg-primary text-white px-4 py-2 rounded-lg hover:bg-primary/90 flex items-center gap-2">
                    <span class="material-symbols-outlined">add</span>
                    Asignar
                </button>
            </div>
        </div>

        <div id="salary-header" class="bg-white dark:bg-slate-800 rounded-xl p-5 shadow-sm border border-slate-200 dark:border-slate-700 mb-6"></div>

        <div id="budgets-list" class="space-y-6"></div>

        <!-- Modal -->
        <div id="budget-modal" class="fixed inset-0 bg-black/50 hidden items-center justify-center z-50 p-4">
            <div class="bg-white dark:bg-slate-800 rounded-xl p-6 w-full max-w-md max-h-full overflow-y-auto">
                <h3 class="text-xl font-bold mb-1" id="modal-title">Asignar dinero</h3>
                <p class="text-sm text-gray-500 dark:text-slate-400 mb-4" id="modal-subtitle"></p>
                <form id="budget-form" class="space-y-4">
                    <input type="hidden" id="budget-id">
                    <div>
                        <label class="block text-sm font-medium mb-1" for="budget-category">Categoría</label>
                        <select id="budget-category" required class="w-full px-3 py-2 border rounded-lg bg-white dark:bg-slate-700 dark:border-slate-600"></select>
                    </div>
                    <div class="rounded-lg bg-slate-50 dark:bg-slate-700/50 p-3 text-sm" id="budget-pot">
                        <div class="text-xs text-gray-500 dark:text-slate-400">Se reparte sobre</div>
                        <div class="font-semibold" id="budget-pot-label">—</div>
                    </div>
                    <div>
                        <label class="block text-sm font-medium mb-1" for="budget-mode">Cómo lo fijas</label>
                        <select id="budget-mode" class="w-full px-3 py-2 border rounded-lg bg-white dark:bg-slate-700 dark:border-slate-600">
                            <option value="AMOUNT">Importe exacto</option>
                            <option value="PERCENT">Porcentaje de lo que hay encima</option>
                        </select>
                    </div>
                    <div id="budget-limit-wrapper">
                        <label class="block text-sm font-medium mb-1" for="budget-limit">Importe mensual</label>
                        <input type="number" id="budget-limit" step="0.01" min="0" class="w-full px-3 py-2 border rounded-lg bg-white dark:bg-slate-700 dark:border-slate-600">
                        <p class="text-xs text-gray-500 dark:text-slate-400 mt-1" id="budget-limit-hint"></p>
                    </div>
                    <div id="budget-percent-wrapper" style="display: none;">
                        <label class="block text-sm font-medium mb-1" for="budget-percent">Porcentaje</label>
                        <input type="number" id="budget-percent" step="0.01" min="0" max="100" class="w-full px-3 py-2 border rounded-lg bg-white dark:bg-slate-700 dark:border-slate-600">
                        <p class="text-xs text-gray-500 dark:text-slate-400 mt-1" id="budget-percent-hint"></p>
                    </div>
                    <div class="grid grid-cols-2 gap-4">
                        <div>
                            <label class="block text-sm font-medium mb-1" for="budget-start">Inicio</label>
                            <input type="date" id="budget-start" required class="w-full px-3 py-2 border rounded-lg bg-white dark:bg-slate-700 dark:border-slate-600">
                        </div>
                        <div>
                            <label class="block text-sm font-medium mb-1" for="budget-end">Fin</label>
                            <input type="date" id="budget-end" required class="w-full px-3 py-2 border rounded-lg bg-white dark:bg-slate-700 dark:border-slate-600">
                        </div>
                    </div>
                    <div class="flex gap-2 justify-end">
                        <button type="button" id="cancel-btn" class="px-4 py-2 border rounded-lg hover:bg-gray-50 dark:hover:bg-slate-700 dark:border-slate-600">Cancelar</button>
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
    document.getElementById('copy-month-btn').addEventListener('click', copyPreviousMonth);
    document.getElementById('cancel-btn').addEventListener('click', () => closeModal());
    document.getElementById('budget-form').addEventListener('submit', handleSubmit);
    document.getElementById('budgets-list').addEventListener('click', handleListClick);
    document.getElementById('salary-header').addEventListener('click', handleHeaderClick);
    document.getElementById('budget-year').addEventListener('change', loadSummary);
    document.getElementById('budget-month').addEventListener('change', loadSummary);
    document.getElementById('budget-modal').addEventListener('click', (e) => {
        if (e.target.id === 'budget-modal') closeModal();
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

const num = (value) => {
    const parsed = Number.parseFloat(value);
    return Number.isFinite(parsed) ? parsed : null;
};

// "de" + "el sueldo" es "del sueldo". Los nombres de los botes se guardan como
// sintagma ("el sueldo", "Gast mensual") porque también se usan sueltos.
const de = (label) => label.startsWith('el ') ? `del ${label.slice(3)}` : `de ${label}`;

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

        // Al desplegable, les subcategories surten indentades sota el seu bloc
        // perquè es vegi l'estructura sense haver-la de recordar.
        const groups = categories.filter(c => categories.some(x => x.parent_id === c.id));
        const groupIds = new Set(groups.map(g => g.id));
        const orphans = categories.filter(c => !groupIds.has(c.id) && !c.parent_id);

        const options = [];
        for (const group of groups) {
            options.push(`<option value="${group.id}">${escapeHtml(group.nom)} (bloque)</option>`);
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
        indexPots(summary);
        renderHeader(summary);
        renderSections(summary, budgets);
    } catch (error) {
        console.error('Error loading budgets:', error);
        container.innerHTML = '<p class="text-red-500">Error al cargar presupuestos</p>';
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
        const label = section.tipus === 'FIXED'
            ? 'los ingresos'
            : section.tipus === 'INCOME'
                // Los ingresos no cuelgan de ningún bote: no se reparten.
                ? 'lo previsto'
                : 'lo que queda para variables';

        for (const node of section.grups || []) {
            walkPots(node, label, section.base);
        }
    }
}

function walkPots(node, potLabel, potAmount) {
    potByCategory.set(node.categoria.id, {
        label: potLabel,
        base: num(node.base_assignacio) ?? potAmount
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
    const childLabel = explicit ? node.categoria.nom : potLabel;
    const childPot = explicit ? num(node.cost_vida_pla) : potAmount;

    for (const child of node.subcategories || []) {
        walkPots(child, childLabel, childPot);
    }
}

/**
 * Capçalera: els ingressos del mes, quant se n'ha repartit i quant queda lliure.
 *
 * És el bot del qual pengen tots els altres, així que va a dalt de tot i sol:
 * sense la base, cap percentatge de la pantalla es pot interpretar.
 */
function renderHeader(summary) {
    const real = num(summary.ingressos_reals) || 0;
    const forecast = num(summary.ingressos_previstos) || 0;
    // Lo que hay para repartir es la suma de los ingresos. El sueldo no es la
    // base del presupuesto: es uno de los bloques de ingreso, al lado de un
    // regalo o de un trabajo puntual.
    const base = num(summary.total_disponible);
    const fromIncome = summary.total_disponible_origen === 'INGRESSOS';
    const assigned = num(summary.total_assignat) || 0;
    const share = num(summary.percentatge_assignat);
    const free = base != null ? base - assigned : null;

    if (base == null || base <= 0) {
        document.getElementById('salary-header').innerHTML = `
            <div class="text-center py-2">
                <div class="text-lg font-semibold mb-1">No hay ingresos este mes</div>
                <p class="text-sm text-gray-500 dark:text-slate-400 mb-3">
                    Sin ellos no hay nada que repartir. Ponle una previsión a la nómina
                    en el bloque de <strong>Ingresos</strong>, o fija un sueldo de referencia.
                </p>
                <button data-action="edit-income" class="bg-primary text-white px-4 py-2 rounded-lg hover:bg-primary/90">
                    Fijar sueldo de referencia
                </button>
            </div>`;
        return;
    }

    const level = share == null ? 'low' : share > 100 ? 'high' : share > 90 ? 'medium' : 'low';
    const width = Math.min(base > 0 ? (assigned / base) * 100 : 0, 100);

    document.getElementById('salary-header').innerHTML = `
        <div class="flex flex-wrap justify-between items-start gap-4 mb-4">
            <div>
                <div class="text-xs uppercase tracking-wide text-gray-500 dark:text-slate-400">Ingresos del mes</div>
                <div class="text-3xl font-bold">${formatCurrency(base)}</div>
                <div class="text-xs text-gray-500 dark:text-slate-400">
                    ${fromIncome
                        ? `suma de los bloques de ingreso${real > 0 ? ` · ${formatCurrency(real)} ya recibidos` : ' (previsión)'}`
                        : `sueldo de referencia${summary.sou_base_origen === 'MES' ? ' de este mes' : ' por defecto'}
                           · <button data-action="edit-income" class="text-primary hover:underline">cambiar</button>`}
                </div>
            </div>
            <div class="text-right">
                <div class="text-xs text-gray-500 dark:text-slate-400">Previsto / recibido</div>
                <div class="text-lg font-semibold">
                    ${formatCurrency(forecast)} <span class="text-gray-400">/</span> ${formatCurrency(real)}
                </div>
                <div class="text-xs text-gray-500 dark:text-slate-400">
                    ${real > forecast && forecast > 0
                        ? 'ha entrado más de lo previsto'
                        : real === 0 ? 'todavía sin movimientos importados' : ''}
                </div>
            </div>
        </div>

        <div class="h-3 bg-gray-200 dark:bg-slate-700 rounded-full overflow-hidden mb-2">
            <div class="h-full ${BAR_CLASSES[level]} transition-all" style="width: ${width}%"></div>
        </div>
        <div class="flex flex-wrap justify-between gap-2 text-sm">
            <span class="${TEXT_CLASSES[level]} font-medium">
                Repartido ${formatCurrency(assigned)}${share != null ? ` · ${share.toFixed(1)}%` : ''}
            </span>
            <span class="text-gray-600 dark:text-slate-300">
                ${free >= 0
                    ? `Sin asignar <strong>${formatCurrency(free)}</strong>`
                    : `<strong class="text-red-600">${formatCurrency(-free)} de más</strong>`}
            </span>
        </div>
    `;
}

function renderSections(summary, budgets) {
    const container = document.getElementById('budgets-list');
    const sections = summary.seccions || [];

    if (sections.length === 0) {
        container.innerHTML = `
            <p class="text-gray-500 dark:text-slate-400 text-center py-8">No hay categorías todavía.</p>`;
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
        .map(section => renderSection(section, budgetByCategory, num(summary.total_disponible)))
        .join('');
}

/**
 * @param available lo que queda para repartir. Solo lo usa la sección de
 *        ingresos, que es de donde sale: su cifra de cabecera no es lo previsto
 *        ni lo recibido, sino lo que aporta cada bloque, que es el mayor de los dos.
 */
function renderSection(section, budgetByCategory, available) {
    const style = SECTIONS[section.tipus] || SECTIONS.VARIABLE;
    const pot = num(section.base);
    const assigned = num(section.assignat) || 0;
    const real = num(section.real) || 0;
    const share = num(section.percentatge_del_sou);
    const left = num(section.restant);
    const nodes = section.grups || [];

    const width = pot > 0 ? Math.min((assigned / pot) * 100, 100) : 0;

    return `
        <section class="bg-white dark:bg-slate-800 rounded-xl shadow-sm border border-slate-200 dark:border-slate-700 ${style.accent} overflow-hidden">
            <header class="p-5 pb-4 border-b border-slate-200 dark:border-slate-700">
                <div class="flex flex-wrap justify-between items-start gap-3">
                    <div>
                        <h3 class="text-xl font-bold">${style.titol}</h3>
                        <p class="text-xs text-gray-500 dark:text-slate-400 mt-0.5">${style.descripcio}</p>
                    </div>
                    <div class="text-right">
                        <div class="text-2xl font-bold">${formatCurrency(style.esIngres ? (available || 0) : assigned)}</div>
                        <div class="text-xs text-gray-500 dark:text-slate-400">
                            ${style.esIngres
                                ? `previsto ${formatCurrency(assigned)} · recibido ${formatCurrency(real)}`
                                : share != null ? `${share.toFixed(1)}% de los ingresos` : 'sin ingresos definidos'}
                        </div>
                    </div>
                </div>

                ${!style.esIngres && pot != null ? `
                    <div class="mt-3">
                        <div class="h-2 bg-gray-200 dark:bg-slate-700 rounded-full overflow-hidden">
                            <div class="h-full ${style.barra} transition-all" style="width: ${width}%"></div>
                        </div>
                        <div class="flex flex-wrap justify-between gap-2 text-xs mt-1.5">
                            <span class="text-gray-600 dark:text-slate-300">
                                Hay <strong>${formatCurrency(pot)}</strong>
                                ${section.tipus === 'FIXED' ? 'disponibles este mes' : 'después de los fijos'}
                            </span>
                            <span class="${left < 0 ? 'text-red-600 font-medium' : 'text-gray-600 dark:text-slate-300'}">
                                ${left < 0
                                    ? `${formatCurrency(-left)} por encima de lo que hay`
                                    : section.tipus === 'FIXED'
                                        // Lo que no se llevan los fijos no queda libre: es exactamente
                                        // el bote con el que empiezan los variables.
                                        ? `${formatCurrency(left)} pasan a variables`
                                        : `quedan ${formatCurrency(left)} por repartir`}
                            </span>
                        </div>
                    </div>` : ''}
            </header>

            <div class="p-4 space-y-3">
                ${nodes.length === 0
                    ? `<p class="text-sm text-gray-500 dark:text-slate-400 py-2">${style.buit}</p>`
                    : byRelevance(nodes).map(node => renderBlock(node, budgetByCategory, style)).join('')}
            </div>
        </section>
    `;
}

/** Un bloc de primer nivell: Trade Republic, Gast mensual, Allotjament… */
function renderBlock(node, budgetByCategory, style) {
    const category = node.categoria;
    const plan = num(node.cost_vida_pla) || 0;
    const real = num(node.cost_vida_real) || 0;
    const children = node.subcategories || [];
    const expanded = expandedGroups.has(category.id);
    const left = num(node.restant);

    const percent = plan > 0 ? Math.min((real / plan) * 100, 100) : 0;
    const level = plan > 0 && real > plan ? 'high' : percent > 80 ? 'medium' : 'low';

    return `
        <div class="rounded-lg border border-slate-200 dark:border-slate-700 p-4">
            <div class="flex flex-wrap justify-between items-start gap-3 mb-3">
                <div class="flex items-start gap-2 min-w-0">
                    ${children.length > 0 ? `
                        <button data-action="toggle" data-id="${category.id}"
                                class="p-1 -ml-1 hover:bg-gray-100 dark:hover:bg-slate-700 rounded shrink-0"
                                title="${expanded ? 'Plegar' : 'Desplegar'}">
                            <span class="material-symbols-outlined text-base">${expanded ? 'expand_less' : 'expand_more'}</span>
                        </button>` : '<span class="w-6 shrink-0"></span>'}
                    <div class="min-w-0">
                        <h4 class="font-semibold truncate">${escapeHtml(category.nom)}</h4>
                        <div class="flex flex-wrap items-center gap-1.5 mt-1">
                            ${shareBadge(node, style)}
                            ${children.length > 0
                                ? `<span class="text-xs text-gray-500 dark:text-slate-400">${children.length} subsecciones</span>`
                                : ''}
                            ${node.carrec_puntual_aquest_mes
                                ? '<span class="text-xs text-orange-600" title="Este mes ha caído un cargo fijo puntual">⚑ cargo este mes</span>'
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
                                   ${plan > 0 ? `previsto ${formatCurrency(plan)} · recibido ${formatCurrency(real)}` : 'recibido'}
                               </div>`
                            : `<div class="text-lg font-bold">${plan > 0 ? formatCurrency(plan) : '—'}</div>
                               <div class="text-xs text-gray-500 dark:text-slate-400">asignado</div>`}
                    </div>
                    ${actionButtons(node, budgetByCategory)}
                </div>
            </div>

            ${!style.esIngres && plan > 0 ? `
                <div class="h-2 bg-gray-200 dark:bg-slate-700 rounded-full overflow-hidden">
                    <div class="h-full ${BAR_CLASSES[level]} transition-all" style="width: ${percent}%"></div>
                </div>
                <div class="flex flex-wrap justify-between gap-2 text-xs mt-1.5">
                    <span class="${TEXT_CLASSES[level]} font-medium">
                        Gastado ${formatCurrency(real)} de ${formatCurrency(plan)}
                    </span>
                    ${left != null
                        ? `<span class="${left < 0 ? 'text-red-600' : 'text-gray-500 dark:text-slate-400'}">
                               ${left < 0
                                    ? `${formatCurrency(-left)} repartidos de más entre las subsecciones`
                                    : `${formatCurrency(left)} sin repartir dentro`}
                           </span>`
                        : `<span class="text-gray-500 dark:text-slate-400">caja ${formatCurrency(num(node.caixa_real) || 0)}</span>`}
                </div>` : style.esIngres ? `
                <p class="text-xs text-gray-500 dark:text-slate-400">
                    Lo que entra por este bloque. Cuenta lo recibido o lo previsto, lo que sea mayor.
                </p>` : `
                <p class="text-xs text-gray-500 dark:text-slate-400">
                    Sin asignar. ${children.length > 0
                        ? 'Su total sale de sumar lo que tengan sus subsecciones.'
                        : 'Ponle un importe o un porcentaje para que cuente en el reparto.'}
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
    const plan = num(node.cost_vida_pla) || 0;
    const real = num(node.cost_vida_real) || 0;
    const fixed = category.tipus_cost === 'FIXED';

    const percent = plan > 0 ? Math.min((real / plan) * 100, 100) : 0;
    const level = plan > 0 && real > plan ? 'high' : percent > 80 ? 'medium' : 'low';

    // Una subsecció buida s'apaga en comptes d'ocupar el mateix que una amb
    // diners: hi ha catorze i només compten les que tenen alguna cosa.
    const idle = plan === 0 && real === 0;

    return `
        <div class="flex flex-wrap items-center justify-between gap-2 py-2 ${idle ? 'opacity-60' : ''}">
            <div class="min-w-0 flex-1">
                <div class="flex flex-wrap items-center gap-1.5">
                    <span class="text-sm truncate">${escapeHtml(category.nom)}</span>
                    ${fixed ? '<span class="text-xs px-1.5 py-0.5 rounded-full bg-blue-100 text-blue-700 dark:bg-blue-500/20 dark:text-blue-300">fijo</span>' : ''}
                    ${shareBadge(node, style)}
                    ${num(node.aporta_al_disponible) > 0
                        ? `<span class="text-xs px-1.5 py-0.5 rounded-full bg-emerald-100 text-emerald-800 dark:bg-emerald-500/20 dark:text-emerald-300"
                                 title="Lo que esta categoría pone en el total a repartir: lo recibido o lo previsto, lo que sea mayor.">
                               aporta ${formatCurrency(num(node.aporta_al_disponible))}
                           </span>`
                        : ''}
                </div>
                ${!style.esIngres && plan > 0 ? `
                    <div class="h-1.5 bg-gray-200 dark:bg-slate-700 rounded-full overflow-hidden mt-1.5 max-w-xs">
                        <div class="h-full ${BAR_CLASSES[level]}" style="width: ${percent}%"></div>
                    </div>` : ''}
            </div>
            <div class="flex items-center gap-2 shrink-0">
                <div class="text-right text-sm">
                    ${style.esIngres
                        ? `<div class="font-medium ${real > 0 ? 'text-green-600' : ''}">${formatCurrency(Math.max(real, plan))}</div>
                           <div class="text-xs text-gray-500 dark:text-slate-400">
                               ${plan > 0 ? `previsto ${formatCurrency(plan)} · recibido ${formatCurrency(real)}` : 'recibido'}
                           </div>`
                        : plan > 0
                            ? `<div class="font-medium">${formatCurrency(plan)}</div>
                               <div class="text-xs ${TEXT_CLASSES[level]}">gastado ${formatCurrency(real)}</div>`
                            : real > 0
                                ? `<div class="font-medium text-orange-600">${formatCurrency(real)}</div>
                                   <div class="text-xs text-gray-500 dark:text-slate-400">gastado sin asignar</div>`
                                : '<div class="text-xs text-gray-500 dark:text-slate-400">sin asignar</div>'}
                </div>
                ${actionButtons(node, budgetByCategory)}
            </div>
        </div>
    `;
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
    return [...(nodes || [])].sort((a, b) => {
        const planA = num(a.cost_vida_pla) || 0;
        const planB = num(b.cost_vida_pla) || 0;
        if (planA !== planB) return planB - planA;
        // A igualtat, el que s'hi ha gastat: un gasto sense pla és justament
        // el que val la pena mirar.
        return (num(b.cost_vida_real) || 0) - (num(a.cost_vida_real) || 0);
    });
}

/**
 * La pastilla que diu de què és percentatge la xifra.
 *
 * És la peça que abans faltava: "30%" no vol dir res si no se sap si és del
 * sou, del bot de variables o del bloc que té a sobre.
 */
function shareBadge(node, style) {
    const declared = num(node.percentatge);
    const effective = num(node.percentatge_efectiu);
    const share = declared ?? effective;
    // Un "≈0% de Gast mensual" a cada categoria buida no informa de res i tapa
    // les que sí que tenen diners.
    if (share == null || (declared == null && share === 0)) return '';

    const pot = potByCategory.get(node.categoria.id);
    const label = pot ? pot.label : 'lo que hay encima';

    // El "≈" distingeix el percentatge que ha triat l'usuari del que surt de
    // dividir un import exacte: el primer es manté si canvia el sou, el segon no.
    return `<span class="text-xs px-2 py-0.5 rounded-full ${style.pastilla}">
                ${declared == null ? '≈' : ''}${share.toFixed(share % 1 === 0 ? 0 : 1)}% ${escapeHtml(de(label))}
            </span>`;
}

function actionButtons(node, budgetByCategory) {
    const budget = budgetByCategory.get(node.categoria.id);
    if (budget) {
        return `
            <button data-action="edit" data-id="${budget.id}" class="p-1 hover:bg-gray-100 dark:hover:bg-slate-700 rounded" title="Cambiar la asignación">
                <span class="material-symbols-outlined text-sm">edit</span>
            </button>
            <button data-action="delete" data-id="${budget.id}" class="p-1 hover:bg-red-50 dark:hover:bg-red-500/10 text-red-500 rounded" title="Quitar la asignación">
                <span class="material-symbols-outlined text-sm">delete</span>
            </button>`;
    }
    return `
        <button data-action="create" data-category="${node.categoria.id}" class="p-1 hover:bg-gray-100 dark:hover:bg-slate-700 rounded" title="Asignar dinero">
            <span class="material-symbols-outlined text-sm">add</span>
        </button>`;
}

// ============ FORMULARI ============

/** El bot sobre el qual es reparteix la categoria que hi ha seleccionada. */
function selectedPot() {
    const id = Number.parseInt(document.getElementById('budget-category').value, 10);
    return potByCategory.get(id) || { label: 'lo que hay encima', base: null };
}

function refreshPot() {
    const pot = selectedPot();
    document.getElementById('budget-pot-label').textContent = pot.base != null
        ? `${formatCurrency(pot.base)} · ${pot.label}`
        : `${pot.label} (sin cifra: faltan los ingresos del mes)`;
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

    const percent = num(document.getElementById('budget-percent').value);
    const amount = num(document.getElementById('budget-limit').value);

    if (pot.base == null) {
        percentHint.textContent = 'Sin ingresos definidos no se puede calcular el importe.';
        amountHint.textContent = '';
        return;
    }

    percentHint.textContent = percent == null
        ? ''
        : `Son ${formatCurrency(pot.base * percent / 100)} ${de(pot.label)} (${formatCurrency(pot.base)}).`;

    amountHint.textContent = amount == null || pot.base <= 0
        ? ''
        : `Es el ${(amount / pot.base * 100).toFixed(1)}% ${de(pot.label)}.`;
}

/**
 * Duplica aquí el repartiment del mes anterior.
 *
 * Els percentatges es recalculen sols sobre el bot del mes nou: si el sou
 * canvia, el repartiment s'hi ajusta sense tocar res.
 */
async function copyPreviousMonth() {
    const { year, month } = selectedPeriod();
    const monthName = MONTH_NAMES[month - 1];

    if (!confirm(`Copiar a ${monthName} las asignaciones del mes anterior.
Lo que ya tenga asignación este mes no se toca.`)) return;

    try {
        const result = await copyPreviousMonthBudgets(year, month);
        await loadSummary();
        alert(result.copiats > 0
            ? `Copiadas ${result.copiats} asignaciones de ${result.origen}.`
            : `No había nada que copiar de ${result.origen}, o ya lo tenías todo asignado.`);
    } catch (error) {
        alert(error.message || 'Error al copiar el mes anterior');
    }
}

/** Sou d'aquest mes concret; buit vol dir "torna al sou per defecte". */
async function editMonthlyIncome() {
    const { year, month } = selectedPeriod();
    const period = `${year}-${String(month).padStart(2, '0')}`;
    const current = lastSummary?.sou_base_origen === 'MES'
        ? num(lastSummary.sou_base)
        : '';

    const input = prompt(
        `Sueldo para ${period}.
Déjalo vacío para volver al sueldo por defecto.`,
        current === '' || current == null ? '' : String(current));
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
        document.getElementById('modal-title').textContent = 'Cambiar la asignación';
        document.getElementById('budget-id').value = budget.id;
        document.getElementById('budget-category').value = budget.category?.id;
        document.getElementById('budget-limit').value = budget.quantitat_limit;
        document.getElementById('budget-percent').value = budget.percentatge ?? '';
        document.getElementById('budget-mode').value = budget.percentatge != null ? 'PERCENT' : 'AMOUNT';
        document.getElementById('budget-start').value = budget.periode_inici;
        document.getElementById('budget-end').value = budget.periode_fi;
    } else {
        document.getElementById('modal-title').textContent = 'Asignar dinero';
        document.getElementById('budget-id').value = '';
        document.getElementById('budget-mode').value = 'AMOUNT';
        if (presetCategoryId) {
            document.getElementById('budget-category').value = presetCategoryId;
        }
    }

    document.getElementById('modal-subtitle').textContent =
        'Un porcentaje se mide siempre sobre el nivel que tiene encima, no sobre el total de ingresos.';

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
            alert(error.message || 'Error al cargar presupuesto');
        }
        return;
    }

    if (action === 'delete') {
        if (!confirm('¿Quitar esta asignación?')) return;
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
    const percent = num(document.getElementById('budget-percent').value);
    const pot = selectedPot();

    if (byPercent && (percent == null || percent < 0 || percent > 100)) {
        alert('El porcentaje debe estar entre 0 y 100.');
        return;
    }

    // quantitat_limit es NOT NULL en la base de datos, así que un presupuesto
    // por porcentaje guarda el importe ya calculado sobre el bote de su nivel:
    // la tabla sigue siendo legible por sí sola y el importe real se recalcula
    // en cada consulta.
    const limit = byPercent
        ? (pot.base != null && pot.base > 0 ? Number((pot.base * percent / 100).toFixed(2)) : 0)
        : num(document.getElementById('budget-limit').value);

    if (!byPercent && (limit == null || limit < 0)) {
        alert('Introduce un importe positivo.');
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
        alert(error.message || 'Error al guardar presupuesto');
    }
}
