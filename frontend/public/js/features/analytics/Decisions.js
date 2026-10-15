import { getDecisions, applyBudgetChanges, formatCurrency, escapeHtml } from '../../api.js';
import {
    monthName, startingValue, simulate, goalComparison, projectionText, simulationChanges,
    frequencyLabel, recurringGroups, trendText
} from '../../decisions.js';
import { chartTheme } from '../../generalAnalysis.js';

/**
 * La pestanya Decisiones de l'anàlisi: provar un canvi abans d'aplicar-lo,
 * veure què costen a l'any les coses fixes i què s'està disparant.
 *
 * Les dades surten d'una crida (GET /analytics/decisions). La simulació es fa
 * al navegador i es refà a cada tecla; aplicar-la fa servir el mateix
 * POST /budgets/apply-changes que les propostes de la pestanya Presupuesto.
 */

const card = 'bg-white dark:bg-slate-800 rounded-xl p-4 shadow-sm border border-slate-200 dark:border-slate-700';

export async function initDecisions(container) {
    container.innerHTML = '<p class="text-sm text-slate-500">Cargando…</p>';
    let data;
    try {
        data = await getDecisions();
    } catch (error) {
        container.innerHTML = `<div class="p-3 rounded-lg text-sm bg-red-100 text-red-700 dark:bg-red-500/20 dark:text-red-300">
            No se ha podido cargar: ${escapeHtml(error.message)}</div>`;
        return;
    }

    container.innerHTML = `
        <div class="space-y-6">
            ${simulatorCard(data)}
            <div class="grid grid-cols-1 lg:grid-cols-2 gap-6">
                ${recurringCard(data.recurrents)}
                ${trendsCard(data.tendencies)}
            </div>
        </div>
    `;
    setUpSimulator(container, data);
}

// ============ EL SIMULADOR ============

function simulatorCard(data) {
    const simulator = data.simulador || {};
    const items = simulator.partides || [];
    const target = monthName(data.mes_desti);
    const saving = simulator.estalvi_mensual;

    const savingLine = saving === null || saving === undefined
        ? 'Todavía no hay meses cerrados con movimientos para saber cuánto ahorras.'
        : `Ahora ahorras de media ${formatCurrency(saving)} al mes${Number(simulator.estalvi_pla) > 0
            ? ` (el plan es ${formatCurrency(simulator.estalvi_pla)})` : ''}.`;

    return `
        <div class="${card}" id="simulator">
            <h3 class="text-lg font-semibold mb-1">¿Y si…?</h3>
            <p class="text-xs text-slate-500 dark:text-slate-400 mb-3">
                Cambia lo que planeas gastar en cada partida y mira qué pasa con el ahorro y con tus metas.
                Lo que recortas va a ahorro; lo que subes sale de él. ${escapeHtml(savingLine)}
            </p>
            ${items.length === 0
                ? '<p class="text-sm text-slate-500">No hay gastos variables con plan ni con gasto todavía.</p>'
                : `
            <div class="table-responsive">
                <table class="table w-full text-sm">
                    <thead>
                        <tr>
                            <th class="text-left">Partida</th>
                            <th class="text-right">Plan ahora</th>
                            <th class="text-right">Gastas de media</th>
                            <th class="text-right">Nuevo</th>
                            <th class="text-right">Cambio</th>
                        </tr>
                    </thead>
                    <tbody>
                        ${items.map(item => `
                            <tr data-sim-row="${item.categoria_id}">
                                <td>
                                    <span class="font-medium">${escapeHtml(item.nom)}</span>
                                    <span class="text-xs text-slate-500 dark:text-slate-400">${escapeHtml(item.bloc || '')}</span>
                                </td>
                                <td class="text-right whitespace-nowrap">${item.te_pla_propi ? formatCurrency(item.pla) : '<span class="text-xs text-slate-500">sin plan</span>'}</td>
                                <td class="text-right whitespace-nowrap">${formatCurrency(item.mitjana_real)}</td>
                                <td class="text-right">
                                    <input type="number" min="0" step="5" inputmode="decimal"
                                           class="sim-input w-24 px-2 py-1 border rounded text-right bg-white text-slate-900 dark:bg-slate-700 dark:text-slate-100 dark:border-slate-600"
                                           data-category="${item.categoria_id}" value="${startingValue(item)}"
                                           aria-label="Nuevo plan para ${escapeHtml(item.nom)}">
                                </td>
                                <td class="text-right whitespace-nowrap" data-sim-change="${item.categoria_id}">—</td>
                            </tr>`).join('')}
                    </tbody>
                </table>
            </div>
            <p class="text-sm font-medium mt-3" id="sim-effect" role="status"></p>`}
            ${goalsBlock(data.metes)}
            ${items.length === 0 ? '' : `
            <div class="flex flex-wrap items-center justify-end gap-3 mt-4" id="sim-footer">
                <button type="button" class="btn btn-outline" id="sim-reset">Volver a los importes de ahora</button>
                <button type="button" class="btn btn-primary" id="sim-apply" disabled>Aplicar estos importes a ${escapeHtml(target)}</button>
            </div>
            <p class="text-sm mt-2 hidden" id="sim-result" role="status"></p>`}
        </div>
    `;
}

function goalsBlock(goals) {
    if (!goals || goals.length === 0) {
        return `<p class="text-xs text-slate-500 dark:text-slate-400 mt-4">
            Sin metas pendientes. <a href="#goals" class="underline">Crea una en Metas</a> para ver cuándo llegas.</p>`;
    }
    return `
        <div class="mt-5">
            <h4 class="font-semibold mb-1">Tus metas</h4>
            <p class="text-xs text-slate-500 dark:text-slate-400 mb-2">Cada una, como si todo lo que ahorras al mes fuera a ella.</p>
            <ul class="space-y-3">
                ${goals.map(goal => {
                    const target = Number.parseFloat(goal.objectiu) || 0;
                    const progress = target > 0 ? Math.min(100, ((Number.parseFloat(goal.actual) || 0) / target) * 100) : 0;
                    return `<li data-goal="${goal.id}">
                        <div class="flex flex-wrap justify-between gap-2 text-sm">
                            <span class="font-medium">${escapeHtml(goal.nom)}</span>
                            <span class="text-slate-500 dark:text-slate-400">
                                ${formatCurrency(goal.actual)} de ${formatCurrency(goal.objectiu)} · faltan ${formatCurrency(goal.falta)}
                                ${goal.data_objectiu ? ` · para ${escapeHtml(monthName(String(goal.data_objectiu).slice(0, 7)))}` : ''}
                            </span>
                        </div>
                        <div class="h-2 bg-slate-100 dark:bg-slate-700 rounded-full mt-1">
                            <div class="h-full rounded-full" style="width: ${progress.toFixed(1)}%; background-color: ${chartTheme(document.documentElement.classList.contains('dark')).series[2]}"></div>
                        </div>
                        <div class="text-xs mt-1" data-goal-projection></div>
                    </li>`;
                }).join('')}
            </ul>
        </div>
    `;
}

function setUpSimulator(container, data) {
    const simulator = data.simulador || {};
    const items = simulator.partides || [];
    const inputs = [...container.querySelectorAll('.sim-input')];
    const applyButton = container.querySelector('#sim-apply');

    const values = () => Object.fromEntries(inputs.map(input => [input.dataset.category, input.value]));

    const refresh = () => {
        const result = simulate(items, values());
        for (const row of result.rows) {
            const cell = container.querySelector(`[data-sim-change="${row.categoria_id}"]`);
            if (!cell) continue;
            cell.textContent = row.diferencia === 0 ? '—'
                : `${row.diferencia > 0 ? '+' : '−'}${formatCurrency(Math.abs(row.diferencia))}`;
            cell.className = `text-right whitespace-nowrap ${row.diferencia < 0
                ? 'text-emerald-700 dark:text-emerald-300' : row.diferencia > 0 ? 'text-red-700 dark:text-red-300' : ''}`;
        }

        const effect = container.querySelector('#sim-effect');
        if (effect) {
            effect.textContent = result.extraMonthly > 0
                ? `Ahorrarías ${formatCurrency(result.extraMonthly)} más al mes: ${formatCurrency(result.extraYearly)} al año.`
                : result.extraMonthly < 0
                    ? `Ahorrarías ${formatCurrency(-result.extraMonthly)} menos al mes: ${formatCurrency(-result.extraYearly)} al año.`
                    : 'Cambia algún importe para ver el efecto.';
        }

        const monthlySaving = Number.parseFloat(simulator.estalvi_mensual) || 0;
        for (const goal of data.metes || []) {
            const element = container.querySelector(`[data-goal="${goal.id}"] [data-goal-projection]`);
            if (!element) continue;
            const comparison = goalComparison(goal, monthlySaving, result.extraMonthly);
            const onTime = (projection) => projection.onTime === null ? ''
                : projection.onTime ? ' · a tiempo' : ' · tarde';
            let text = `Ahora: ${projectionText(comparison.now)}${onTime(comparison.now)}.`;
            if (result.extraMonthly !== 0) {
                const sooner = comparison.monthsSooner;
                text += ` Con los cambios: ${projectionText(comparison.simulated)}${onTime(comparison.simulated)}`
                    + (sooner > 0 ? `, ${sooner} ${sooner === 1 ? 'mes' : 'meses'} antes.` : sooner < 0 ? `, ${-sooner} ${sooner === -1 ? 'mes' : 'meses'} más tarde.` : '.');
            }
            element.textContent = text;
            element.className = `text-xs mt-1 ${comparison.simulated.onTime === false ? 'text-red-700 dark:text-red-300' : 'text-slate-600 dark:text-slate-300'}`;
        }

        if (applyButton) applyButton.disabled = simulationChanges(items, values()).length === 0;
    };

    inputs.forEach(input => input.addEventListener('input', refresh));
    container.querySelector('#sim-reset')?.addEventListener('click', () => {
        inputs.forEach(input => {
            const item = items.find(candidate => String(candidate.categoria_id) === input.dataset.category);
            input.value = startingValue(item);
        });
        refresh();
    });

    applyButton?.addEventListener('click', async () => {
        const changes = simulationChanges(items, values());
        const target = monthName(data.mes_desti);
        const source = monthName(data.periode);
        if (!confirm(`Se copiará a ${target} el plan de ${source} (lo que ya tengas en ${target} no se toca) `
            + `y se cambiarán ${changes.length} ${changes.length === 1 ? 'partida' : 'partidas'}. ¿Seguimos?`)) return;

        applyButton.disabled = true;
        const result = container.querySelector('#sim-result');
        try {
            const applied = await applyBudgetChanges(data.mes_desti, changes);
            const skipped = applied.omesos || [];
            result.textContent = `Hecho: ${applied.aplicats} ${applied.aplicats === 1 ? 'cambio' : 'cambios'} en ${target}`
                + (applied.copiats > 0 ? ` y ${applied.copiats} copiados de ${source}` : '') + '.'
                + (skipped.length > 0 ? ` No se ha tocado ${skipped.join(', ')}: tiene un presupuesto de más de un mes.` : '');
            result.className = 'text-sm mt-2 text-emerald-700 dark:text-emerald-300';
            container.querySelector('#sim-footer').classList.add('hidden');
            inputs.forEach(input => { input.disabled = true; });
        } catch (error) {
            result.textContent = `No se ha aplicado nada: ${error.message}`;
            result.className = 'text-sm mt-2 text-red-700 dark:text-red-300';
            applyButton.disabled = false;
        }
    });

    refresh();
}

// ============ ELS RECURRENTS, A L'ANY ============

function recurringCard(recurring) {
    const groups = recurringGroups(recurring?.partides);
    return `
        <div class="${card}" id="recurring-review">
            <h3 class="text-lg font-semibold mb-1">Lo fijo, al año</h3>
            <p class="text-xs text-slate-500 dark:text-slate-400 mb-3">
                Tus recurrentes pasados a lo que cuestan en un año: 13 € al mes no parecen nada; 156 € al año ya hacen pensar.
                Se cambian o se dan de baja en <a href="#recurring" class="underline">Recurrentes</a>.
            </p>
            ${groups.length === 0 ? '<p class="text-sm text-slate-500">Sin recurrentes de gasto.</p>' : `
            <div class="text-2xl font-bold">${formatCurrency(recurring.total_anual)} <span class="text-sm font-normal text-slate-500 dark:text-slate-400">al año</span></div>
            <div class="text-xs text-slate-500 dark:text-slate-400 mb-3">${formatCurrency(recurring.total_mensual)} al mes</div>
            <div class="space-y-3">
                ${groups.map(group => `
                    <div data-recurring-group="${escapeHtml(group.bloc)}">
                        <div class="flex justify-between text-xs font-semibold uppercase tracking-wide text-slate-500 dark:text-slate-400">
                            <span>${escapeHtml(group.bloc)}</span><span>${formatCurrency(group.anual)}/año</span>
                        </div>
                        <ul class="text-sm divide-y divide-slate-100 dark:divide-slate-700">
                            ${group.partides.map(item => `<li class="flex justify-between gap-3 py-1">
                                <span>${escapeHtml(item.nom)}
                                    <span class="text-xs text-slate-500 dark:text-slate-400">${formatCurrency(item.import)} ${escapeHtml(frequencyLabel(item.frequencia))}</span>
                                </span>
                                <span class="whitespace-nowrap font-medium">${formatCurrency(item.anual)}</span>
                            </li>`).join('')}
                        </ul>
                    </div>`).join('')}
            </div>`}
        </div>
    `;
}

// ============ EL QUE PUJA ============

function trendsCard(trends) {
    const theme = chartTheme(document.documentElement.classList.contains('dark'));
    return `
        <div class="${card}" id="trends">
            <h3 class="text-lg font-semibold mb-1">Lo que está subiendo</h3>
            <p class="text-xs text-slate-500 dark:text-slate-400 mb-3">
                Gastos variables que han subido tres meses seguidos y ya están claramente por encima de lo que eran.
            </p>
            ${!trends || trends.length === 0
                ? '<p class="text-sm text-slate-500">Nada se dispara: ningún gasto variable ha subido tres meses seguidos.</p>'
                : `<ul class="space-y-3">
                ${trends.map(trend => {
                    const months = trend.mesos || [];
                    const max = Math.max(...months.map(month => Number.parseFloat(month.real) || 0), 1);
                    return `<li data-trend="${trend.categoria_id}" class="flex items-end gap-3">
                        <div class="flex items-end gap-0.5 h-10" role="img"
                             aria-label="${escapeHtml(months.map(month => `${month.periode}: ${formatCurrency(month.real)}`).join(', '))}">
                            ${months.map((month, index) => {
                                const height = Math.max(2, ((Number.parseFloat(month.real) || 0) / max) * 40);
                                // Els tres últims, els que pugen, en color; els d'abans, apagats.
                                const recent = index >= months.length - 3;
                                return `<span class="w-2 rounded-t ${recent ? '' : 'bg-slate-300 dark:bg-slate-600'}"
                                              style="height: ${height.toFixed(0)}px${recent ? `; background-color: ${theme.series[1]}` : ''}"
                                              title="${escapeHtml(`${monthName(month.periode)}: ${formatCurrency(month.real)}`)}"></span>`;
                            }).join('')}
                        </div>
                        <div class="text-sm">
                            <div><span class="font-medium">${escapeHtml(trend.nom)}</span>
                                <span class="text-xs text-slate-500 dark:text-slate-400">${escapeHtml(trend.bloc || '')}</span></div>
                            <div class="text-xs text-slate-600 dark:text-slate-300">${escapeHtml(trendText(trend))}</div>
                        </div>
                    </li>`;
                }).join('')}
            </ul>`}
        </div>
    `;
}
