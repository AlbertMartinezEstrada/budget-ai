import {
    getDebts, createDebt, updateDebt, deleteDebt, getCategories, formatCurrency, escapeHtml
} from '../../api.js';

/**
 * Deutes i préstecs, en els dos sentits.
 *
 * Aquesta vista només porta el compte de qui deu què i com s'ha de tornar. El
 * que ja s'ha retornat no s'escriu aquí: surt dels moviments vinculats al
 * deute, que es vinculen des de Transaccions. Així no hi ha dues xifres que
 * puguin deixar de quadrar.
 */

const DIRECTIONS = {
    DEC: {
        titol: 'Jo dec',
        buit: 'No deus res a ningú.',
        retorn: 'retornat',
        verb: 'Tornar'
    },
    EM_DEUEN: {
        titol: 'Em deuen',
        buit: 'Ningú et deu res.',
        retorn: 'cobrat',
        verb: 'Cobrar'
    }
};

const FREQUENCIES = {
    SETMANAL: { etiqueta: 'a la setmana', mesos: 0, setmanes: 1 },
    MENSUAL: { etiqueta: 'al mes', mesos: 1, setmanes: 0 },
    TRIMESTRAL: { etiqueta: 'al trimestre', mesos: 3, setmanes: 0 }
};

const INSTALLMENT_STATUS = {
    PAGAT: { etiqueta: 'pagat', classe: 'text-green-600' },
    PARCIAL: { etiqueta: 'a mitges', classe: 'text-orange-600' },
    PENDENT: { etiqueta: 'pendent', classe: 'text-gray-500 dark:text-slate-400' },
    ENDARRERIT: { etiqueta: 'endarrerit', classe: 'text-red-600' }
};

/** On es reserva per defecte la quota d'un deute nou que dec. */
const DEFAULT_REPAYMENT_LEAF = 'Pagament de deutes';

let debts = [];
let reservableLeaves = [];
const expandedDebts = new Set();

export async function initDebts(container) {
    container.innerHTML = `
        <div class="page-header flex flex-wrap justify-between items-center gap-3 mb-2">
            <h2 class="text-2xl font-bold text-slate-800 dark:text-slate-100">Deutes i préstecs</h2>
            <button id="add-debt-btn" class="bg-primary text-white px-4 py-2 rounded-lg hover:bg-primary/90 flex items-center gap-2">
                <span class="material-symbols-outlined">add</span>
                Nou deute
            </button>
        </div>
        <p class="text-sm text-slate-500 dark:text-slate-400 mb-6 max-w-3xl">
            Un préstec no és ni un ingrés ni una despesa: és el mateix diner que entra i torna a sortir.
            Al pressupost, el que et deixen entra a <strong>Préstecs rebuts</strong> i el que tornes surt de
            <strong>Pagament de deutes</strong>. Vincula cada moviment al seu deute des de Transaccions i aquí
            veuràs quant queda.
        </p>

        <div id="debt-totals" class="grid grid-cols-1 md:grid-cols-2 gap-4 mb-6"></div>
        <div id="debts-list" class="space-y-8">
            <div class="text-center py-8 text-slate-500 dark:text-slate-400">Carregant...</div>
        </div>

        <div id="debt-modal" class="fixed inset-0 bg-black/50 hidden items-center justify-center z-50 p-4">
            <div class="bg-white dark:bg-slate-800 rounded-xl p-6 w-full max-w-md max-h-full overflow-y-auto">
                <h3 class="text-xl font-bold mb-4" id="debt-modal-title">Nou deute</h3>
                <form id="debt-form" class="space-y-4">
                    <input type="hidden" id="debt-id">
                    <div>
                        <label class="block text-sm font-medium mb-1" for="debt-direction">Qui el deu</label>
                        <select id="debt-direction" class="form-control">
                            <option value="DEC">Me'ls han deixat: els he de tornar</option>
                            <option value="EM_DEUEN">Els he deixat jo: me'ls han de tornar</option>
                        </select>
                    </div>
                    <div>
                        <label class="block text-sm font-medium mb-1" for="debt-name">A qui, o per a què</label>
                        <input type="text" id="debt-name" class="form-control" required
                               placeholder="Pares, entrada del pis">
                    </div>
                    <div class="grid grid-cols-2 gap-3">
                        <div>
                            <label class="block text-sm font-medium mb-1" for="debt-amount">Import</label>
                            <input type="number" id="debt-amount" class="form-control" step="0.01" min="0.01" required>
                        </div>
                        <div>
                            <label class="block text-sm font-medium mb-1" for="debt-date">Data del préstec</label>
                            <input type="date" id="debt-date" class="form-control" required>
                        </div>
                    </div>
                    <div>
                        <label class="block text-sm font-medium mb-1" for="debt-plan">Com es torna</label>
                        <select id="debt-plan" class="form-control">
                            <option value="LLIURE">Sense calendari: quan es pugui</option>
                            <option value="UNIC">Tot de cop, un dia concret</option>
                            <option value="QUOTES">A quotes</option>
                        </select>
                    </div>
                    <div id="debt-single-fields" class="hidden">
                        <label class="block text-sm font-medium mb-1" for="debt-single-date">Dia que es torna</label>
                        <input type="date" id="debt-single-date" class="form-control">
                    </div>
                    <div id="debt-installment-fields" class="hidden space-y-3">
                        <div class="grid grid-cols-2 gap-3">
                            <div>
                                <label class="block text-sm font-medium mb-1" for="debt-installment">Quota</label>
                                <input type="number" id="debt-installment" class="form-control" step="0.01" min="0.01">
                            </div>
                            <div>
                                <label class="block text-sm font-medium mb-1" for="debt-frequency">Cada</label>
                                <select id="debt-frequency" class="form-control">
                                    <option value="MENSUAL">Mes</option>
                                    <option value="SETMANAL">Setmana</option>
                                    <option value="TRIMESTRAL">Trimestre</option>
                                </select>
                            </div>
                        </div>
                        <div>
                            <label class="block text-sm font-medium mb-1" for="debt-first-date">Primera quota</label>
                            <input type="date" id="debt-first-date" class="form-control">
                        </div>
                    </div>
                    <p id="debt-plan-preview" class="text-sm text-slate-500 dark:text-slate-400 hidden"></p>
                    <div id="debt-category-field">
                        <label class="block text-sm font-medium mb-1" for="debt-category">On es reserva la quota al pressupost</label>
                        <select id="debt-category" class="form-control"></select>
                        <p class="text-xs text-slate-500 dark:text-slate-400 mt-1">
                            Cada mes, la quota que toca es reserva sola en aquesta categoria: són diners compromesos.
                        </p>
                    </div>
                    <div>
                        <label class="block text-sm font-medium mb-1" for="debt-notes">Notes</label>
                        <input type="text" id="debt-notes" class="form-control" placeholder="Opcional">
                    </div>
                    <p id="debt-form-error" class="text-error text-sm hidden"></p>
                    <div class="flex gap-2 justify-end">
                        <button type="button" id="debt-cancel" class="btn btn-outline">Cancel·lar</button>
                        <button type="submit" class="btn btn-primary">Desar</button>
                    </div>
                </form>
            </div>
        </div>
    `;

    document.getElementById('add-debt-btn').addEventListener('click', () => openModal());
    document.getElementById('debt-cancel').addEventListener('click', closeModal);
    document.getElementById('debt-modal').addEventListener('click', (event) => {
        if (event.target.id === 'debt-modal') closeModal();
    });
    document.getElementById('debt-form').addEventListener('submit', handleSubmit);
    document.getElementById('debts-list').addEventListener('click', handleListClick);
    for (const fieldId of ['debt-direction', 'debt-plan', 'debt-amount', 'debt-installment',
        'debt-frequency', 'debt-first-date', 'debt-single-date']) {
        document.getElementById(fieldId).addEventListener('input', refreshPlanFields);
    }

    try {
        reservableLeaves = expenseLeaves(await getCategories());
        fillCategorySelect();
    } catch (error) {
        console.error('Error carregant categories:', error);
    }
    await loadDebts();
}

async function loadDebts() {
    try {
        debts = await getDebts();
        render();
    } catch (error) {
        console.error('Error carregant deutes:', error);
        document.getElementById('debts-list').innerHTML = `
            <div class="text-center py-8 text-red-500">Error carregant deutes: ${escapeHtml(error.message)}</div>
        `;
    }
}

/**
 * Les fulles on té sentit reservar una quota: les de despesa.
 *
 * Una fulla d'ingressos no reserva res —el backend hi mira el que entra— i un
 * grup es tornaria a sumar pels seus fills, així que cap dels dos s'ofereix.
 */
function expenseLeaves(categories) {
    const byId = new Map(categories.map(category => [category.id, category]));
    const parentIds = new Set(categories.map(category => category.parent_id).filter(Boolean));
    const rootOf = (category) => {
        let current = category;
        while (current.parent_id && byId.has(current.parent_id)) current = byId.get(current.parent_id);
        return current;
    };
    return categories
        .filter(category => !parentIds.has(category.id))
        .filter(category => rootOf(category).tipus_cost !== 'INCOME')
        .sort((first, second) => first.nom.localeCompare(second.nom, 'ca'));
}

function fillCategorySelect() {
    document.getElementById('debt-category').innerHTML = [
        '<option value="-1">No reservar-la</option>',
        ...reservableLeaves.map(category =>
            `<option value="${category.id}">${escapeHtml(category.nom)}</option>`)
    ].join('');
}

// ============ LLISTA ============

function render() {
    renderTotals();

    const list = document.getElementById('debts-list');
    list.innerHTML = Object.entries(DIRECTIONS).map(([direction, labels]) => {
        const ofDirection = debts.filter(debt => debt.direccio === direction);
        return `
            <section>
                <h3 class="text-lg font-semibold text-slate-800 dark:text-slate-100 mb-3">${labels.titol}</h3>
                ${ofDirection.length === 0
                    ? `<p class="text-sm text-slate-500 dark:text-slate-400">${labels.buit}</p>`
                    : `<div class="grid grid-cols-1 lg:grid-cols-2 gap-4 items-start">
                           ${ofDirection.map(renderCard).join('')}
                       </div>`}
            </section>
        `;
    }).join('');
}

function renderTotals() {
    const totalOf = (direction, field) => debts
        .filter(debt => debt.direccio === direction)
        .reduce((sum, debt) => sum + (Number.parseFloat(debt[field]) || 0), 0);

    document.getElementById('debt-totals').innerHTML = Object.entries(DIRECTIONS).map(([direction, labels]) => {
        const pending = totalOf(direction, 'pendent');
        const overdue = totalOf(direction, 'endarrerit');
        return `
            <div class="bg-white dark:bg-slate-800 rounded-xl p-4 shadow-sm border">
                <div class="text-sm text-slate-500 dark:text-slate-400">${labels.titol}</div>
                <div class="text-2xl font-bold ${direction === 'DEC' ? 'text-red-600' : 'text-green-600'}">
                    ${formatCurrency(pending)}
                </div>
                <div class="text-xs ${overdue > 0 ? 'text-red-600' : 'text-slate-500 dark:text-slate-400'}">
                    ${overdue > 0 ? `${formatCurrency(overdue)} endarrerits` : 'al dia'}
                </div>
            </div>
        `;
    }).join('');
}

function renderCard(debt) {
    const labels = DIRECTIONS[debt.direccio] || DIRECTIONS.DEC;
    const amount = Number.parseFloat(debt.import) || 0;
    const repaid = Number.parseFloat(debt.retornat) || 0;
    const pending = Number.parseFloat(debt.pendent) || 0;
    const overdue = Number.parseFloat(debt.endarrerit) || 0;
    const progress = amount > 0 ? Math.min((repaid / amount) * 100, 100) : 0;
    const expanded = expandedDebts.has(debt.id);

    return `
        <div class="bg-white dark:bg-slate-800 rounded-xl p-4 shadow-sm border ${debt.saldat ? 'opacity-60' : ''}">
            <div class="flex justify-between items-start gap-2 mb-2">
                <div class="min-w-0">
                    <h4 class="font-semibold truncate">${escapeHtml(debt.nom)}</h4>
                    <div class="text-xs text-slate-500 dark:text-slate-400">
                        ${formatCurrency(amount)} el ${escapeHtml(formatDate(debt.data))}
                    </div>
                </div>
                <div class="flex items-center gap-1 shrink-0">
                    ${statusBadge(debt, overdue)}
                    <button data-action="edit" data-id="${debt.id}" class="p-1 hover:bg-gray-100 dark:hover:bg-slate-700 rounded" title="Editar">
                        <span class="material-symbols-outlined text-sm">edit</span>
                    </button>
                    <button data-action="delete" data-id="${debt.id}" class="p-1 hover:bg-red-50 dark:hover:bg-red-500/10 text-red-500 rounded" title="Esborrar">
                        <span class="material-symbols-outlined text-sm">delete</span>
                    </button>
                </div>
            </div>

            <div class="text-xl font-bold mb-1">
                ${formatCurrency(pending)}
                <span class="text-sm font-normal text-slate-500 dark:text-slate-400">per ${labels.verb.toLowerCase()}</span>
            </div>
            <div class="w-full bg-gray-200 dark:bg-slate-700 rounded-full h-2 mb-1">
                <div class="bg-primary h-2 rounded-full" style="width: ${progress}%"></div>
            </div>
            <div class="text-xs text-slate-500 dark:text-slate-400 mb-3">
                ${formatCurrency(repaid)} ${labels.retorn} de ${formatCurrency(amount)}
            </div>

            <div class="text-sm space-y-1">
                <div>${escapeHtml(describePlan(debt))}</div>
                ${nextPaymentLine(debt, labels)}
                ${debt.direccio === 'DEC' && debt.category && debt.forma_retorn !== 'LLIURE'
                    ? `<div class="text-xs text-slate-500 dark:text-slate-400">
                           Es reserva a «${escapeHtml(debt.category.nom)}» al pressupost.
                       </div>`
                    : ''}
                ${debt.notes ? `<div class="text-xs text-slate-500 dark:text-slate-400">${escapeHtml(debt.notes)}</div>` : ''}
            </div>

            <button data-action="toggle" data-id="${debt.id}" class="mt-3 text-sm text-primary hover:underline flex items-center gap-1">
                <span class="material-symbols-outlined text-base">${expanded ? 'expand_less' : 'expand_more'}</span>
                ${expanded ? 'Amagar' : 'Calendari i moviments'}
            </button>
            ${expanded ? renderDetails(debt) : ''}
        </div>
    `;
}

function statusBadge(debt, overdue) {
    if (debt.saldat) {
        return '<span class="text-xs px-2 py-0.5 rounded-full bg-emerald-100 text-emerald-800 dark:bg-emerald-500/20 dark:text-emerald-300">saldat</span>';
    }
    if (overdue > 0) {
        return `<span class="text-xs px-2 py-0.5 rounded-full bg-red-100 text-red-700 dark:bg-red-500/20 dark:text-red-300">
                    ${formatCurrency(overdue)} endarrerits
                </span>`;
    }
    return '';
}

function describePlan(debt) {
    if (debt.forma_retorn === 'UNIC') {
        return `Tot de cop el ${formatDate(debt.data_primer_pagament)}`;
    }
    if (debt.forma_retorn === 'QUOTES') {
        const frequency = FREQUENCIES[debt.frequencia] || FREQUENCIES.MENSUAL;
        const count = (debt.calendari || []).length;
        return `${formatCurrency(debt.quota)} ${frequency.etiqueta} des del ${formatDate(debt.data_primer_pagament)}`
            + ` · ${count} ${count === 1 ? 'pagament' : 'pagaments'}`;
    }
    return 'Sense calendari';
}

function nextPaymentLine(debt, labels) {
    const next = debt.proper_pagament;
    if (!next || debt.saldat) return '';
    const late = next.data < todayInputValue();
    return `<div class="${late ? 'text-red-600' : ''}">
                ${labels.verb} ${formatCurrency(next.import)} ${late ? 'des del' : 'el'} ${escapeHtml(formatDate(next.data))}
            </div>`;
}

function renderDetails(debt) {
    const schedule = debt.calendari || [];
    const movements = debt.moviments || [];

    return `
        <div class="mt-3 pt-3 border-t border-slate-200 dark:border-slate-700 space-y-4">
            ${schedule.length > 0 ? `
                <div>
                    <div class="text-xs font-semibold uppercase text-slate-500 dark:text-slate-400 mb-1">Calendari</div>
                    <div class="max-h-96 overflow-y-auto divide-y divide-slate-100 dark:divide-slate-700/50">
                        ${schedule.map(installment => {
                            const status = INSTALLMENT_STATUS[installment.estat] || INSTALLMENT_STATUS.PENDENT;
                            return `
                                <div class="flex gap-2 py-1 text-sm">
                                    <span class="flex-1">${escapeHtml(formatDate(installment.data))}</span>
                                    <span class="w-24 text-right">${formatCurrency(installment.import)}</span>
                                    <span class="${status.classe} w-24 text-right">${status.etiqueta}</span>
                                </div>`;
                        }).join('')}
                    </div>
                </div>` : ''}
            <div>
                <div class="text-xs font-semibold uppercase text-slate-500 dark:text-slate-400 mb-1">Moviments vinculats</div>
                ${movements.length === 0
                    ? `<p class="text-sm text-slate-500 dark:text-slate-400">
                           Encara cap. Des de Transaccions, edita un moviment i tria aquest deute.
                       </p>`
                    : `<div class="divide-y divide-slate-100 dark:divide-slate-700/50">
                           ${movements.map(renderMovement).join('')}
                       </div>`}
            </div>
        </div>
    `;
}

function renderMovement(movement) {
    const isIncome = movement.type === 'INCOME';
    return `
        <div class="flex gap-2 py-1 text-sm">
            <span class="shrink-0 w-24">${escapeHtml(formatDate(movement.data))}</span>
            <span class="flex-1 truncate text-slate-500 dark:text-slate-400">
                ${escapeHtml(movement.descripcio_curta || movement.empresa || '')}
            </span>
            <span class="shrink-0 font-medium ${isIncome ? 'text-green-600' : 'text-red-600'}">
                ${isIncome ? '+' : '−'}${formatCurrency(movement.cost)}
            </span>
        </div>
    `;
}

async function handleListClick(event) {
    const button = event.target.closest('button[data-action]');
    if (!button) return;

    const id = Number.parseInt(button.dataset.id, 10);
    if (!Number.isInteger(id)) return;
    const debt = debts.find(candidate => candidate.id === id);

    if (button.dataset.action === 'toggle') {
        if (expandedDebts.has(id)) expandedDebts.delete(id);
        else expandedDebts.add(id);
        render();
        return;
    }

    if (button.dataset.action === 'edit' && debt) {
        openModal(debt);
        return;
    }

    if (button.dataset.action === 'delete') {
        if (!confirm('Esborrar aquest deute? Els moviments vinculats es queden, només perden el vincle.')) return;
        button.disabled = true;
        try {
            await deleteDebt(id);
            expandedDebts.delete(id);
            await loadDebts();
        } catch (error) {
            alert(error.message || 'No s\'ha pogut esborrar el deute.');
            button.disabled = false;
        }
    }
}

// ============ FORMULARI ============

function openModal(debt = null) {
    const form = document.getElementById('debt-form');
    form.reset();
    document.getElementById('debt-form-error').classList.add('hidden');
    document.getElementById('debt-modal-title').textContent = debt ? 'Editar deute' : 'Nou deute';

    document.getElementById('debt-id').value = debt?.id || '';
    document.getElementById('debt-direction').value = debt?.direccio || 'DEC';
    document.getElementById('debt-name').value = debt?.nom || '';
    document.getElementById('debt-amount').value = debt ? Number.parseFloat(debt.import) : '';
    document.getElementById('debt-date').value = debt?.data || todayInputValue();
    document.getElementById('debt-plan').value = debt?.forma_retorn || 'LLIURE';
    document.getElementById('debt-single-date').value =
        debt?.forma_retorn === 'UNIC' ? debt.data_primer_pagament : '';
    document.getElementById('debt-installment').value =
        debt?.forma_retorn === 'QUOTES' ? Number.parseFloat(debt.quota) : '';
    document.getElementById('debt-frequency').value = debt?.frequencia || 'MENSUAL';
    document.getElementById('debt-first-date').value =
        debt?.forma_retorn === 'QUOTES' ? debt.data_primer_pagament : '';
    document.getElementById('debt-notes').value = debt?.notes || '';

    // Un deute nou que dec es reserva per defecte a "Pagament de deutes":
    // és on anirà la devolució, i sense reserva el pressupost donaria per
    // lliures uns diners que ja estan compromesos.
    const defaultLeaf = reservableLeaves.find(category => category.nom === DEFAULT_REPAYMENT_LEAF);
    const categoryId = debt ? debt.category?.id : defaultLeaf?.id;
    document.getElementById('debt-category').value = categoryId ? String(categoryId) : '-1';

    refreshPlanFields();

    const modal = document.getElementById('debt-modal');
    modal.classList.remove('hidden');
    modal.classList.add('flex');
}

function closeModal() {
    const modal = document.getElementById('debt-modal');
    modal.classList.add('hidden');
    modal.classList.remove('flex');
}

/** Ensenya els camps de la forma de retorn triada i el calendari que en sortirà. */
function refreshPlanFields() {
    const plan = document.getElementById('debt-plan').value;
    const direction = document.getElementById('debt-direction').value;

    document.getElementById('debt-single-fields').classList.toggle('hidden', plan !== 'UNIC');
    document.getElementById('debt-installment-fields').classList.toggle('hidden', plan !== 'QUOTES');
    // Només es reserva el que dec, i només si hi ha calendari: sense dates no
    // se sap a quin mes toca.
    document.getElementById('debt-category-field').classList.toggle('hidden', direction !== 'DEC' || plan === 'LLIURE');

    const preview = document.getElementById('debt-plan-preview');
    const text = plan === 'QUOTES' ? previewInstallments() : '';
    preview.textContent = text;
    preview.classList.toggle('hidden', !text);
}

/**
 * "10 pagaments; l'últim, de 100 €, el 05/07/2027".
 *
 * És el mateix càlcul que fa el backend, repetit aquí només per ensenyar-lo
 * abans de desar: el calendari de debò és el que torna el servidor.
 */
function previewInstallments() {
    const amount = Number.parseFloat(document.getElementById('debt-amount').value);
    const installment = Number.parseFloat(document.getElementById('debt-installment').value);
    const firstDate = document.getElementById('debt-first-date').value;
    if (!(amount > 0) || !(installment > 0) || !firstDate) return '';

    const count = Math.ceil(amount / installment - 1e-9);
    if (count > 600) return 'Massa pagaments: posa una quota més gran.';
    const last = Math.round((amount - installment * (count - 1)) * 100) / 100;
    const frequency = FREQUENCIES[document.getElementById('debt-frequency').value] || FREQUENCIES.MENSUAL;
    const lastDate = addPeriods(firstDate, frequency, count - 1);

    return count === 1
        ? `Un sol pagament, el ${formatDate(lastDate)}.`
        : `${count} pagaments; l'últim, de ${formatCurrency(last)}, el ${formatDate(lastDate)}.`;
}

/**
 * Suma períodes a una data "AAAA-MM-DD" com ho fa el backend.
 *
 * Els mesos es compten des del primer pagament i el dia es retalla a l'últim
 * del mes: a JavaScript, el 31 de gener més un mes dona el 3 de març.
 */
function addPeriods(isoDate, frequency, periods) {
    const [year, month, day] = isoDate.split('-').map(Number);
    if (frequency.setmanes) {
        const date = new Date(year, month - 1, day + 7 * periods);
        return toIsoDate(date);
    }
    const targetMonth = new Date(year, month - 1 + frequency.mesos * periods, 1);
    const lastDay = new Date(targetMonth.getFullYear(), targetMonth.getMonth() + 1, 0).getDate();
    targetMonth.setDate(Math.min(day, lastDay));
    return toIsoDate(targetMonth);
}

async function handleSubmit(event) {
    event.preventDefault();
    const error = document.getElementById('debt-form-error');
    error.classList.add('hidden');

    const id = document.getElementById('debt-id').value;
    const direction = document.getElementById('debt-direction').value;
    const plan = document.getElementById('debt-plan').value;

    // Els noms han de coincidir amb els @JsonProperty de Debt.
    const payload = {
        nom: document.getElementById('debt-name').value.trim(),
        direccio: direction,
        import: Number.parseFloat(document.getElementById('debt-amount').value),
        data: document.getElementById('debt-date').value,
        forma_retorn: plan,
        notes: document.getElementById('debt-notes').value.trim(),
        // -1 vol dir "sense categoria": en una actualització parcial, no
        // enviar-la la deixaria com estava.
        category: { id: -1 }
    };
    if (plan === 'UNIC') {
        payload.data_primer_pagament = document.getElementById('debt-single-date').value || null;
    }
    if (plan === 'QUOTES') {
        payload.quota = Number.parseFloat(document.getElementById('debt-installment').value) || null;
        payload.frequencia = document.getElementById('debt-frequency').value;
        payload.data_primer_pagament = document.getElementById('debt-first-date').value || null;
    }
    const categoryId = Number.parseInt(document.getElementById('debt-category').value, 10);
    if (direction === 'DEC' && plan !== 'LLIURE' && categoryId > 0) {
        payload.category = { id: categoryId };
    }

    const submitButton = event.target.querySelector('button[type="submit"]');
    submitButton.disabled = true;
    try {
        if (id) {
            await updateDebt(Number.parseInt(id, 10), payload);
        } else {
            await createDebt(payload);
        }
        closeModal();
        await loadDebts();
    } catch (failure) {
        error.textContent = failure.message || 'No s\'ha pogut desar el deute.';
        error.classList.remove('hidden');
    } finally {
        submitButton.disabled = false;
    }
}

// ============ DATES ============

function toIsoDate(date) {
    const month = String(date.getMonth() + 1).padStart(2, '0');
    const day = String(date.getDate()).padStart(2, '0');
    return `${date.getFullYear()}-${month}-${day}`;
}

/** Avui com a "AAAA-MM-DD". toISOString() passaria a UTC i podria restar un dia. */
function todayInputValue() {
    return toIsoDate(new Date());
}

function formatDate(isoDate) {
    if (!isoDate || !/^\d{4}-\d{2}-\d{2}$/.test(isoDate)) return isoDate || '-';
    const [year, month, day] = isoDate.split('-').map(Number);
    return new Date(year, month - 1, day).toLocaleDateString('ca-ES');
}
