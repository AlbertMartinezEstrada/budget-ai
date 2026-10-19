import { getDecisions, applyBudgetChanges, formatCurrency, escapeHtml } from '../../api.js';
import {
    monthName, startingValue, simulate, goalComparison, projectionText, simulationChanges,
    frequencyLabel, recurringGroups, trendText
} from '../../decisions.js';
import { chartTheme } from '../../generalAnalysis.js';
import { appliedText } from '../../budgetReview.js';
import { t } from '../../i18n.js';

/**
 * La pestanya Decisiones de l'anàlisi: provar un canvi abans d'aplicar-lo,
 * veure què costen a l'any les coses fixes i què s'està disparant.
 *
 * Les dades surten d'una crida (GET /analytics/decisions). La simulació es fa
 * al navegador i es refà a cada tecla; aplicar-la fa servir el mateix
 * POST /budgets/apply-changes que les propostes de la pestanya Presupuesto.
 */

const card = 'bg-white dark:bg-slate-800 rounded-3xl p-4 shadow-xs border border-slate-200 dark:border-slate-700';

export async function initDecisions(container) {
    container.innerHTML = `<p class="text-sm text-slate-500">${t('common.loading')}</p>`;
    let data;
    try {
        data = await getDecisions();
    } catch (error) {
        container.innerHTML = `<div class="p-3 rounded-2xl text-sm bg-red-100 text-red-700 dark:bg-red-500/20 dark:text-red-300">
            ${escapeHtml(t('decisions.load-failed', { message: error.message }))}</div>`;
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

    let savingLine;
    if (saving === null || saving === undefined) {
        savingLine = t('decisions.no-saving-data');
    } else if (Number(simulator.estalvi_pla) > 0) {
        savingLine = t('decisions.saving-now-plan', {
            amount: formatCurrency(saving), plan: formatCurrency(simulator.estalvi_pla)
        });
    } else {
        savingLine = t('decisions.saving-now', { amount: formatCurrency(saving) });
    }

    return `
        <div class="${card}" id="simulator">
            <h3 class="text-lg font-semibold mb-1">${t('decisions.what-if')}</h3>
            <p class="text-xs text-slate-500 dark:text-slate-400 mb-3">
                ${t('decisions.what-if-hint')} ${escapeHtml(savingLine)}
            </p>
            ${items.length === 0
                ? `<p class="text-sm text-slate-500">${t('decisions.no-items')}</p>`
                : `
            <div class="table-responsive">
                <table class="table w-full text-sm">
                    <thead>
                        <tr>
                            <th class="text-left">${t('decisions.item')}</th>
                            <th class="text-right">${t('decisions.plan-now')}</th>
                            <th class="text-right">${t('decisions.average')}</th>
                            <th class="text-right">${t('decisions.new')}</th>
                            <th class="text-right">${t('decisions.change')}</th>
                        </tr>
                    </thead>
                    <tbody>
                        ${items.map(item => `
                            <tr data-sim-row="${item.categoria_id}">
                                <td>
                                    <span class="font-medium">${escapeHtml(item.nom)}</span>
                                    <span class="text-xs text-slate-500 dark:text-slate-400">${escapeHtml(item.bloc || '')}</span>
                                </td>
                                <td class="text-right whitespace-nowrap">${item.te_pla_propi ? formatCurrency(item.pla) : `<span class="text-xs text-slate-500">${t('decisions.no-plan')}</span>`}</td>
                                <td class="text-right whitespace-nowrap">${formatCurrency(item.mitjana_real)}</td>
                                <td class="text-right">
                                    <input type="number" min="0" step="5" inputmode="decimal"
                                           class="sim-input w-24 px-2 py-1 border rounded-lg text-right bg-white text-slate-900 dark:bg-slate-700 dark:text-slate-100 dark:border-slate-600"
                                           data-category="${item.categoria_id}" value="${startingValue(item)}"
                                           aria-label="${escapeHtml(t('decisions.new-plan-label', { name: item.nom }))}">
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
                <button type="button" class="btn btn-outline" id="sim-reset">${t('decisions.reset')}</button>
                <button type="button" class="btn btn-primary" id="sim-apply" disabled>${escapeHtml(t('decisions.apply', { month: target }))}</button>
            </div>
            <p class="text-sm mt-2 hidden" id="sim-result" role="status"></p>`}
        </div>
    `;
}

function goalsBlock(goals) {
    if (!goals || goals.length === 0) {
        return `<p class="text-xs text-slate-500 dark:text-slate-400 mt-4">
            ${t('decisions.no-goals')} <a href="#goals" class="underline">${t('decisions.go-to-goals')}</a></p>`;
    }
    return `
        <div class="mt-5">
            <h4 class="font-semibold mb-1">${t('decisions.your-goals')}</h4>
            <p class="text-xs text-slate-500 dark:text-slate-400 mb-2">${t('decisions.goals-hint')}</p>
            <ul class="space-y-3">
                ${goals.map(goal => {
                    const target = Number.parseFloat(goal.objectiu) || 0;
                    const progress = target > 0 ? Math.min(100, ((Number.parseFloat(goal.actual) || 0) / target) * 100) : 0;
                    return `<li data-goal="${goal.id}">
                        <div class="flex flex-wrap justify-between gap-2 text-sm">
                            <span class="font-medium">${escapeHtml(goal.nom)}</span>
                            <span class="text-slate-500 dark:text-slate-400">
                                ${escapeHtml(t('decisions.goal-progress', {
                                    current: formatCurrency(goal.actual), target: formatCurrency(goal.objectiu),
                                    missing: formatCurrency(goal.falta)
                                }))}${goal.data_objectiu
                                    ? ` · ${escapeHtml(t('decisions.goal-deadline', { month: monthName(String(goal.data_objectiu).slice(0, 7)) }))}`
                                    : ''}
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
            if (result.extraMonthly > 0) {
                effect.textContent = t('decisions.effect-more', {
                    amount: formatCurrency(result.extraMonthly), yearly: formatCurrency(result.extraYearly)
                });
            } else if (result.extraMonthly < 0) {
                effect.textContent = t('decisions.effect-less', {
                    amount: formatCurrency(-result.extraMonthly), yearly: formatCurrency(-result.extraYearly)
                });
            } else {
                effect.textContent = t('decisions.effect-none');
            }
        }

        const monthlySaving = Number.parseFloat(simulator.estalvi_mensual) || 0;
        for (const goal of data.metes || []) {
            const element = container.querySelector(`[data-goal="${goal.id}"] [data-goal-projection]`);
            if (!element) continue;
            const comparison = goalComparison(goal, monthlySaving, result.extraMonthly);
            // «hi arribes en 6 mesos (abril del 2027) · a temps»
            const describe = (projection) => projectionText(projection) + (projection.onTime === null ? ''
                : ` · ${t(projection.onTime ? 'decisions.on-time' : 'decisions.late')}`);
            let text = t('decisions.now', { projection: describe(comparison.now) });
            if (result.extraMonthly !== 0) {
                const sooner = comparison.monthsSooner;
                const projection = describe(comparison.simulated);
                if (sooner > 0) text += ' ' + t('decisions.sooner', { projection, count: sooner });
                else if (sooner < 0) text += ' ' + t('decisions.later', { projection, count: -sooner });
                else text += ' ' + t('decisions.with-changes', { projection });
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
        if (!confirm(t('decisions.apply-confirm', { target, source, count: changes.length }))) return;

        applyButton.disabled = true;
        const result = container.querySelector('#sim-result');
        try {
            const applied = await applyBudgetChanges(data.mes_desti, changes);
            result.textContent = appliedText(applied, target);
            result.className = 'text-sm mt-2 text-emerald-700 dark:text-emerald-300';
            container.querySelector('#sim-footer').classList.add('hidden');
            inputs.forEach(input => { input.disabled = true; });
        } catch (error) {
            result.textContent = t('analysis.apply-failed', { message: error.message });
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
            <h3 class="text-lg font-semibold mb-1">${t('decisions.fixed-yearly')}</h3>
            <p class="text-xs text-slate-500 dark:text-slate-400 mb-3">
                ${escapeHtml(t('decisions.fixed-yearly-hint', { monthly: formatCurrency(13), yearly: formatCurrency(156) }))}
                <a href="#recurring" class="underline">${t('decisions.fixed-yearly-link')}</a>.
            </p>
            ${groups.length === 0 ? `<p class="text-sm text-slate-500">${t('decisions.no-recurring')}</p>` : `
            <div class="text-2xl font-bold">${formatCurrency(recurring.total_anual)} <span class="text-sm font-normal text-slate-500 dark:text-slate-400">${t('decisions.per-year')}</span></div>
            <div class="text-xs text-slate-500 dark:text-slate-400 mb-3">${escapeHtml(t('decisions.monthly-total', { amount: formatCurrency(recurring.total_mensual) }))}</div>
            <div class="space-y-3">
                ${groups.map(group => `
                    <div data-recurring-group="${escapeHtml(group.bloc)}">
                        <div class="flex justify-between text-xs font-semibold uppercase tracking-wide text-slate-500 dark:text-slate-400">
                            <span>${escapeHtml(group.bloc)}</span><span>${escapeHtml(t('decisions.per-year-short', { amount: formatCurrency(group.anual) }))}</span>
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
            <h3 class="text-lg font-semibold mb-1">${t('decisions.rising')}</h3>
            <p class="text-xs text-slate-500 dark:text-slate-400 mb-3">${t('decisions.rising-hint')}</p>
            ${!trends || trends.length === 0
                ? `<p class="text-sm text-slate-500">${t('decisions.nothing-rising')}</p>`
                : `<ul class="space-y-3">
                ${trends.map(trend => {
                    const months = trend.mesos || [];
                    const max = Math.max(...months.map(month => Number.parseFloat(month.real) || 0), 1);
                    return `<li data-trend="${trend.categoria_id}" class="flex items-end gap-3">
                        <div class="flex items-end gap-0.5 h-10" role="img"
                             aria-label="${escapeHtml(months.map(month => `${monthName(month.periode)}: ${formatCurrency(month.real)}`).join(', '))}">
                            ${months.map((month, index) => {
                                const height = Math.max(2, ((Number.parseFloat(month.real) || 0) / max) * 40);
                                // Els tres últims, els que pugen, en color; els d'abans, apagats.
                                const recent = index >= months.length - 3;
                                return `<span class="w-2 rounded-t-lg ${recent ? '' : 'bg-slate-300 dark:bg-slate-600'}"
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
