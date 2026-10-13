import { getBudgetVsActual, formatCurrency, escapeHtml } from '../../api.js';
import {
    SECTION_ORDER, SECTION_LABELS, statusPill, statusDot, formatDeviation, monthLabel,
    shortMonthLabel, shiftMonths, selectableMonths, historySummary, sortHistory
} from '../../budgetReview.js';

/**
 * La pestanya Presupuesto de l'anàlisi: el que s'havia planejat contra el
 * que ha passat de debò.
 *
 * Dalt, el mes triat per seccions i blocs; baix, l'historial de cada bloc
 * dels últims mesos, per veure si una desviació és d'un mes o de cada mes.
 * Tot surt d'una sola crida: el rang acaba al mes triat, i el mes triat és
 * l'últim del rang.
 */
export async function initBudgetReview(container) {
    const months = selectableMonths();

    container.innerHTML = `
        <div class="flex flex-wrap items-end gap-3 mb-4">
            <div>
                <label class="block text-xs text-slate-500 dark:text-slate-400 mb-1" for="review-month">Mes</label>
                <select id="review-month" class="pl-3 pr-10 py-2 border rounded-lg">
                    ${months.map(period => `<option value="${period}">${escapeHtml(monthLabel(period))}</option>`).join('')}
                </select>
            </div>
            <div>
                <label class="block text-xs text-slate-500 dark:text-slate-400 mb-1" for="review-length">Histórico</label>
                <select id="review-length" class="pl-3 pr-10 py-2 border rounded-lg">
                    <option value="6">Últimos 6 meses</option>
                    <option value="12">Últimos 12 meses</option>
                </select>
            </div>
            <p class="text-xs text-slate-500 dark:text-slate-400 flex-1 min-w-0">
                Real es lo que ha pasado de verdad: lo gastado, lo apartado y lo cobrado.
                En gastos, lo malo es pasarse; en ahorro e ingresos, quedarse corto.
            </p>
        </div>
        <div id="review-content" class="space-y-6">
            <p class="text-sm text-slate-500">Cargando…</p>
        </div>
    `;

    const load = async () => {
        const content = container.querySelector('#review-content');
        const to = container.querySelector('#review-month').value;
        const length = Number.parseInt(container.querySelector('#review-length').value, 10);
        try {
            const review = await getBudgetVsActual(shiftMonths(to, length - 1), to);
            content.innerHTML = renderReview(review);
        } catch (error) {
            content.innerHTML = `<div class="p-3 rounded-lg text-sm bg-red-100 text-red-700 dark:bg-red-500/20 dark:text-red-300">No se ha podido cargar la comparación: ${escapeHtml(error.message)}</div>`;
        }
    };

    container.querySelector('#review-month').addEventListener('change', load);
    container.querySelector('#review-length').addEventListener('change', load);
    container.querySelector('#review-content').addEventListener('click', toggleRow);

    await load();
}

function renderReview(review) {
    const months = review.mesos || [];
    const month = months[months.length - 1];
    if (!month) return '<p class="text-sm text-slate-500">Sin datos.</p>';

    return `
        ${notices(month)}
        <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
            ${orderedSections(month).map(sectionCard).join('')}
        </div>
        <div class="bg-white dark:bg-slate-800 rounded-xl p-4 shadow-sm border border-slate-200 dark:border-slate-700">
            <h3 class="text-lg font-semibold mb-1">${escapeHtml(monthLabel(month.periode))}, bloque a bloque</h3>
            <p class="text-xs text-slate-500 dark:text-slate-400 mb-3">Despliega un bloque para ver sus subcategorías.</p>
            ${blocksTable(month)}
        </div>
        <div class="bg-white dark:bg-slate-800 rounded-xl p-4 shadow-sm border border-slate-200 dark:border-slate-700">
            <h3 class="text-lg font-semibold mb-1">Histórico</h3>
            <p class="text-xs text-slate-500 dark:text-slate-400 mb-3">
                Primero, los bloques que más veces se han salido del plan. El resumen solo cuenta los meses
                cerrados con plan: el mes en curso todavía puede cambiar.
            </p>
            ${historyTable(review)}
        </div>
    `;
}

function notices(month) {
    const items = [];
    if (!month.te_pla) {
        items.push(`<div class="p-3 rounded-lg text-sm bg-amber-100 text-amber-800 dark:bg-amber-500/20 dark:text-amber-300">
            ${escapeHtml(monthLabel(month.periode))} no tiene nada planeado: no hay con qué comparar.
            <a href="#budgets" class="underline">Planifícalo en Presupuestos</a>.
        </div>`);
    }
    if (month.mes_en_curs) {
        items.push(`<p class="text-sm text-slate-500 dark:text-slate-400">
            Mes en curso: el ahorro y los ingresos que faltan aún pueden llegar. Un gasto que ya se ha pasado, ya se ha pasado.
        </p>`);
    }
    return items.join('');
}

const orderedSections = (month) => SECTION_ORDER
    .map(type => (month.seccions || []).find(section => section.tipus === type))
    .filter(Boolean);

function sectionCard(section) {
    const fund = Number(section.gastat_del_fons) || 0;
    return `
        <div class="bg-white dark:bg-slate-800 rounded-xl p-4 shadow-sm border border-slate-200 dark:border-slate-700" data-section="${section.tipus}">
            <div class="flex items-start justify-between gap-2 mb-2">
                <div class="text-sm text-slate-500 dark:text-slate-400">${escapeHtml(SECTION_LABELS[section.tipus] || section.tipus)}</div>
                ${statusPill(section.estat, section.tipus)}
            </div>
            <div class="text-2xl font-bold">${formatCurrency(section.real)}</div>
            <div class="text-xs text-slate-500 dark:text-slate-400">
                de ${formatCurrency(section.pla)} planeado · ${formatDeviation(section.desviacio)}
            </div>
            ${fund > 0 ? `<div class="text-xs text-slate-500 dark:text-slate-400 mt-1">gastado del fondo ${formatCurrency(fund)}</div>` : ''}
        </div>
    `;
}

function blocksTable(month) {
    const sections = orderedSections(month).filter(section => (section.blocs || []).length > 0);
    if (sections.length === 0) {
        return '<p class="text-sm text-slate-500">Ni plan ni movimientos este mes.</p>';
    }
    return `
        <div class="table-responsive">
            <table class="table w-full text-sm">
                <thead>
                    <tr>
                        <th class="text-left">Bloque</th>
                        <th class="text-right">Planeado</th>
                        <th class="text-right">Real</th>
                        <th class="text-right">Desviación</th>
                        <th class="text-left">Estado</th>
                    </tr>
                </thead>
                <tbody>
                    ${sections.map(section => `
                        <tr><td colspan="5" class="pt-3 text-xs font-semibold uppercase tracking-wide text-slate-500 dark:text-slate-400">
                            ${escapeHtml(SECTION_LABELS[section.tipus] || section.tipus)}
                        </td></tr>
                        ${section.blocs.map(block => blockRows(block, section.tipus, 0, [])).join('')}
                    `).join('')}
                </tbody>
            </table>
        </div>
    `;
}

/**
 * La fila d'un node i, amagades, les dels seus fills. Cada fila diu de qui
 * penja (data-parent) i tots els seus avantpassats (data-ancestors): obrir un
 * bloc ensenya els fills; tancar-lo amaga tota la branca.
 */
function blockRows(node, section, depth, ancestors) {
    const children = node.subcategories || [];
    const fund = Number(node.gastat_del_fons) || 0;
    const parent = ancestors[ancestors.length - 1];
    const toggle = children.length > 0
        ? `<button type="button" class="text-slate-400 hover:text-primary mr-1" data-toggle="${node.categoria_id}"
                   aria-expanded="false" title="Ver subcategorías">▸</button>`
        : '<span class="inline-block w-3 mr-1"></span>';

    const row = `
        <tr data-node="${node.categoria_id}" ${depth > 0 ? `data-parent="${parent}" data-ancestors="${ancestors.join(' ')}" class="hidden"` : ''}>
            <td style="padding-left: ${0.5 + depth * 1.25}rem">
                ${toggle}<span class="${depth === 0 ? 'font-medium' : ''}">${escapeHtml(node.nom)}</span>
                ${fund > 0 ? `<div class="text-xs text-slate-500 dark:text-slate-400 ml-4">gastado del fondo ${formatCurrency(fund)}</div>` : ''}
            </td>
            <td class="text-right whitespace-nowrap">${formatCurrency(node.pla)}</td>
            <td class="text-right whitespace-nowrap">${formatCurrency(node.real)}</td>
            <td class="text-right whitespace-nowrap">${formatDeviation(node.desviacio)}${node.desviacio_percent != null
                ? ` <span class="text-xs text-slate-400">(${Number(node.desviacio_percent) > 0 ? '+' : ''}${Number(node.desviacio_percent).toFixed(0)} %)</span>` : ''}</td>
            <td>${statusPill(node.estat, section)}</td>
        </tr>
    `;
    const childAncestors = [...ancestors, node.categoria_id];
    return row + children.map(child => blockRows(child, section, depth + 1, childAncestors)).join('');
}

function toggleRow(event) {
    const button = event.target.closest('[data-toggle]');
    if (!button) return;
    const id = button.dataset.toggle;
    const table = button.closest('table');
    const expand = button.getAttribute('aria-expanded') !== 'true';

    button.setAttribute('aria-expanded', String(expand));
    button.textContent = expand ? '▾' : '▸';
    if (expand) {
        table.querySelectorAll(`tr[data-parent="${id}"]`).forEach(row => row.classList.remove('hidden'));
        return;
    }
    // En tancar, tota la branca, i els fills tornen a quedar tancats.
    table.querySelectorAll('tr[data-ancestors]').forEach(row => {
        if (!row.dataset.ancestors.split(' ').includes(id)) return;
        row.classList.add('hidden');
        const childToggle = row.querySelector('[data-toggle]');
        if (childToggle) {
            childToggle.setAttribute('aria-expanded', 'false');
            childToggle.textContent = '▸';
        }
    });
}

function historyTable(review) {
    const months = review.mesos || [];
    const blocks = sortHistory(review.blocs);
    if (blocks.length === 0) {
        return '<p class="text-sm text-slate-500">Todavía no hay meses con plan o con movimientos.</p>';
    }

    let lastSection = null;
    const rows = blocks.map(block => {
        const header = block.seccio !== lastSection
            ? `<tr><td colspan="${months.length + 2}" class="pt-3 text-xs font-semibold uppercase tracking-wide text-slate-500 dark:text-slate-400">
                   ${escapeHtml(SECTION_LABELS[block.seccio] || block.seccio)}
               </td></tr>`
            : '';
        lastSection = block.seccio;
        const cells = (block.mesos || []).map(cell => {
            const title = cell.pla == null ? ''
                : `${monthLabel(cell.periode)}: plan ${formatCurrency(cell.pla)} · real ${formatCurrency(cell.real)} · ${formatDeviation(cell.desviacio)}`;
            return `<td class="text-center">${statusDot(cell.estat, title)}</td>`;
        }).join('');
        return `${header}
            <tr data-history="${block.categoria_id}">
                <td class="font-medium whitespace-nowrap">${escapeHtml(block.nom)}</td>
                ${cells}
                <td class="text-xs text-slate-600 dark:text-slate-300">
                    ${escapeHtml(historySummary(block))}
                    ${block.estat_acumulat ? `<div class="mt-1">Acumulado: ${statusPill(block.estat_acumulat, block.seccio)}</div>` : ''}
                </td>
            </tr>`;
    }).join('');

    return `
        <div class="table-responsive">
            <table class="table w-full text-sm">
                <thead>
                    <tr>
                        <th class="text-left">Bloque</th>
                        ${months.map(month => `<th class="text-center" title="${escapeHtml(monthLabel(month.periode))}${month.mes_en_curs ? ' (en curso)' : ''}">
                            ${escapeHtml(shortMonthLabel(month.periode))}${month.mes_en_curs ? '*' : ''}</th>`).join('')}
                        <th class="text-left">Resumen</th>
                    </tr>
                </thead>
                <tbody>${rows}</tbody>
            </table>
        </div>
        <div class="flex flex-wrap gap-3 text-xs text-slate-500 dark:text-slate-400 mt-3">
            <span class="flex items-center gap-1">${statusDot('BE')} dentro / cumplido</span>
            <span class="flex items-center gap-1">${statusDot('AVIS')} por poco</span>
            <span class="flex items-center gap-1">${statusDot('DESVIAT')} pasado / corto</span>
            <span class="flex items-center gap-1">${statusDot('CARREC_PUNTUAL')} cargo trimestral o anual</span>
            <span class="flex items-center gap-1">${statusDot('SENSE_PLA')} sin plan o en curso</span>
            <span>* mes en curso</span>
        </div>
    `;
}
