import { getBudgetVsActual, getBudgetAdvice, applyBudgetChanges, formatCurrency, escapeHtml } from '../../api.js';
import {
    SECTION_ORDER, statusPill, statusDot, formatDeviation, monthLabel,
    shortMonthLabel, shiftMonths, selectableMonths, currentPeriod, historySummary, sortHistory,
    forecastBasis, adviceReason, planLabel, netEffect, netEffectText, changesFor, appliedText
} from '../../budgetReview.js';
import { sectionLabel } from '../../categoryOptions.js';
import { t, formatPercent, formatList } from '../../i18n.js';

/**
 * La pestanya Presupuesto de l'anàlisi: el que s'havia planejat contra el
 * que ha passat de debò.
 *
 * Dalt, el mes triat per seccions i blocs; baix, l'historial de cada bloc
 * dels últims mesos, per veure si una desviació és d'un mes o de cada mes.
 * Tot surt d'una sola crida: el rang acaba al mes triat, i el mes triat és
 * l'últim del rang.
 *
 * Amb el mes en curs triat, a més, la previsió de com acabarà i les
 * propostes per al mes que ve (GET /analytics/budget-advice), que es poden
 * aplicar d'un cop. D'un mes passat no hi ha res a preveure.
 */
export async function initBudgetReview(container) {
    const months = selectableMonths();

    container.innerHTML = `
        <div class="flex flex-wrap items-end gap-3 mb-4">
            <div>
                <label class="block text-xs text-slate-500 dark:text-slate-400 mb-1" for="review-month">${t('common.month')}</label>
                <select id="review-month" class="pl-3 pr-10 py-2 border rounded-lg">
                    ${months.map(period => `<option value="${period}">${escapeHtml(monthLabel(period))}</option>`).join('')}
                </select>
            </div>
            <div>
                <label class="block text-xs text-slate-500 dark:text-slate-400 mb-1" for="review-length">${t('review.history')}</label>
                <select id="review-length" class="pl-3 pr-10 py-2 border rounded-lg">
                    <option value="6">${escapeHtml(t('review.last-months', { count: 6 }))}</option>
                    <option value="12">${escapeHtml(t('review.last-months', { count: 12 }))}</option>
                </select>
            </div>
            <p class="text-xs text-slate-500 dark:text-slate-400 flex-1 min-w-0">${t('review.intro')}</p>
        </div>
        <div id="review-content" class="space-y-6">
            <p class="text-sm text-slate-500">${t('common.loading')}</p>
        </div>
    `;

    const load = async () => {
        const content = container.querySelector('#review-content');
        const to = container.querySelector('#review-month').value;
        const length = Number.parseInt(container.querySelector('#review-length').value, 10);
        try {
            const isCurrent = to === currentPeriod();
            const [review, advice] = await Promise.all([
                getBudgetVsActual(shiftMonths(to, length - 1), to),
                isCurrent ? getBudgetAdvice() : Promise.resolve(null)
            ]);
            content.innerHTML = renderReview(review, advice);
            if (advice) setUpProposals(content, advice);
        } catch (error) {
            content.innerHTML = `<div class="p-3 rounded-lg text-sm bg-red-100 text-red-700 dark:bg-red-500/20 dark:text-red-300">${escapeHtml(t('review.load-failed', { message: error.message }))}</div>`;
        }
    };

    container.querySelector('#review-month').addEventListener('change', load);
    container.querySelector('#review-length').addEventListener('change', load);
    container.querySelector('#review-content').addEventListener('click', toggleRow);

    await load();
}

function renderReview(review, advice) {
    const months = review.mesos || [];
    const month = months[months.length - 1];
    if (!month) return `<p class="text-sm text-slate-500">${t('review.no-data')}</p>`;

    return `
        ${notices(month)}
        <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
            ${orderedSections(month).map(sectionCard).join('')}
        </div>
        ${advice ? forecastCard(advice) + proposalsCard(advice) : ''}
        <div class="bg-white dark:bg-slate-800 rounded-xl p-4 shadow-sm border border-slate-200 dark:border-slate-700">
            <h3 class="text-lg font-semibold mb-1">${escapeHtml(t('review.month-blocks', { month: monthLabel(month.periode) }))}</h3>
            <p class="text-xs text-slate-500 dark:text-slate-400 mb-3">${t('review.expand-hint')}</p>
            ${blocksTable(month)}
        </div>
        <div class="bg-white dark:bg-slate-800 rounded-xl p-4 shadow-sm border border-slate-200 dark:border-slate-700">
            <h3 class="text-lg font-semibold mb-1">${t('review.history')}</h3>
            <p class="text-xs text-slate-500 dark:text-slate-400 mb-3">${t('review.history-hint')}</p>
            ${historyTable(review)}
        </div>
    `;
}

function notices(month) {
    const items = [];
    if (!month.te_pla) {
        items.push(`<div class="p-3 rounded-lg text-sm bg-amber-100 text-amber-800 dark:bg-amber-500/20 dark:text-amber-300">
            ${escapeHtml(t('review.no-plan-notice', { month: monthLabel(month.periode) }))}
            <a href="#budgets" class="underline">${t('review.plan-link')}</a>.
        </div>`);
    }
    if (month.mes_en_curs) {
        items.push(`<p class="text-sm text-slate-500 dark:text-slate-400">${t('review.current-month-notice')}</p>`);
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
                <div class="text-sm text-slate-500 dark:text-slate-400">${escapeHtml(sectionLabel(section.tipus))}</div>
                ${statusPill(section.estat, section.tipus)}
            </div>
            <div class="text-2xl font-bold">${formatCurrency(section.real)}</div>
            <div class="text-xs text-slate-500 dark:text-slate-400">
                ${escapeHtml(t('review.of-planned', { plan: formatCurrency(section.pla), deviation: formatDeviation(section.desviacio) }))}
            </div>
            ${fund > 0 ? `<div class="text-xs text-slate-500 dark:text-slate-400 mt-1">${escapeHtml(t('budgets.fund-spent', { amount: formatCurrency(fund) }))}</div>` : ''}
        </div>
    `;
}

function blocksTable(month) {
    const sections = orderedSections(month).filter(section => (section.blocs || []).length > 0);
    if (sections.length === 0) {
        return `<p class="text-sm text-slate-500">${t('review.nothing-this-month')}</p>`;
    }
    return `
        <div class="table-responsive">
            <table class="table w-full text-sm">
                <thead>
                    <tr>
                        <th class="text-left">${t('review.block')}</th>
                        <th class="text-right">${t('review.planned')}</th>
                        <th class="text-right">${t('review.actual')}</th>
                        <th class="text-right">${t('review.deviation')}</th>
                        <th class="text-left">${t('review.status')}</th>
                    </tr>
                </thead>
                <tbody>
                    ${sections.map(section => `
                        <tr><td colspan="5" class="pt-3 text-xs font-semibold uppercase tracking-wide text-slate-500 dark:text-slate-400">
                            ${escapeHtml(sectionLabel(section.tipus))}
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
        ? `<button type="button" class="text-slate-500 hover:text-primary mr-1" data-toggle="${node.categoria_id}"
                   aria-expanded="false" title="${t('review.show-subcategories')}">▸</button>`
        : '<span class="inline-block w-3 mr-1"></span>';

    const row = `
        <tr data-node="${node.categoria_id}" ${depth > 0 ? `data-parent="${parent}" data-ancestors="${ancestors.join(' ')}" class="hidden"` : ''}>
            <td style="padding-left: ${0.5 + depth * 1.25}rem">
                ${toggle}<span class="${depth === 0 ? 'font-medium' : ''}">${escapeHtml(node.nom)}</span>
                ${fund > 0 ? `<div class="text-xs text-slate-500 dark:text-slate-400 ml-4">${escapeHtml(t('budgets.fund-spent', { amount: formatCurrency(fund) }))}</div>` : ''}
            </td>
            <td class="text-right whitespace-nowrap">${formatCurrency(node.pla)}</td>
            <td class="text-right whitespace-nowrap">${formatCurrency(node.real)}</td>
            <td class="text-right whitespace-nowrap">${formatDeviation(node.desviacio)}${node.desviacio_percent != null
                ? ` <span class="text-xs text-slate-500">(${Number(node.desviacio_percent) > 0 ? '+' : ''}${formatPercent(node.desviacio_percent, 0)})</span>` : ''}</td>
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
        return `<p class="text-sm text-slate-500">${t('review.no-history')}</p>`;
    }

    let lastSection = null;
    const rows = blocks.map(block => {
        const header = block.seccio !== lastSection
            ? `<tr><td colspan="${months.length + 2}" class="pt-3 text-xs font-semibold uppercase tracking-wide text-slate-500 dark:text-slate-400">
                   ${escapeHtml(sectionLabel(block.seccio))}
               </td></tr>`
            : '';
        lastSection = block.seccio;
        const cells = (block.mesos || []).map(cell => {
            const title = cell.pla == null ? ''
                : t('review.cell-title', {
                    month: monthLabel(cell.periode), plan: formatCurrency(cell.pla),
                    actual: formatCurrency(cell.real), deviation: formatDeviation(cell.desviacio)
                });
            return `<td class="text-center">${statusDot(cell.estat, title)}</td>`;
        }).join('');
        return `${header}
            <tr data-history="${block.categoria_id}">
                <td class="font-medium whitespace-nowrap">${escapeHtml(block.nom)}</td>
                ${cells}
                <td class="text-xs text-slate-600 dark:text-slate-300">
                    ${escapeHtml(historySummary(block))}
                    ${block.estat_acumulat ? `<div class="mt-1">${t('review.cumulative')} ${statusPill(block.estat_acumulat, block.seccio)}</div>` : ''}
                </td>
            </tr>`;
    }).join('');

    return `
        <div class="table-responsive">
            <table class="table w-full text-sm">
                <thead>
                    <tr>
                        <th class="text-left">${t('review.block')}</th>
                        ${months.map(month => `<th class="text-center" title="${escapeHtml(month.mes_en_curs
                            ? t('review.in-progress-title', { month: monthLabel(month.periode) })
                            : monthLabel(month.periode))}">
                            ${escapeHtml(shortMonthLabel(month.periode))}${month.mes_en_curs ? '*' : ''}</th>`).join('')}
                        <th class="text-left">${t('review.summary')}</th>
                    </tr>
                </thead>
                <tbody>${rows}</tbody>
            </table>
        </div>
        <div class="flex flex-wrap gap-3 text-xs text-slate-500 dark:text-slate-400 mt-3">
            <span class="flex items-center gap-1">${statusDot('BE')} ${t('review.legend-good')}</span>
            <span class="flex items-center gap-1">${statusDot('AVIS')} ${t('review.legend-close')}</span>
            <span class="flex items-center gap-1">${statusDot('DESVIAT')} ${t('review.legend-off')}</span>
            <span class="flex items-center gap-1">${statusDot('CARREC_PUNTUAL')} ${t('review.legend-one-off')}</span>
            <span class="flex items-center gap-1">${statusDot('SENSE_PLA')} ${t('review.legend-none')}</span>
            <span>${t('review.legend-current')}</span>
        </div>
    `;
}

// ============ COM ACABARÀ EL MES ============

function forecastCard(advice) {
    const rows = (advice.previsio || []).map(block => forecastRows(block, 0, [])).join('');
    return `
        <div class="bg-white dark:bg-slate-800 rounded-xl p-4 shadow-sm border border-slate-200 dark:border-slate-700" id="forecast-card">
            <h3 class="text-lg font-semibold mb-1">${escapeHtml(t('review.forecast-title', { month: monthLabel(advice.periode) }))}</h3>
            <p class="text-xs text-slate-500 dark:text-slate-400 mb-3">
                ${escapeHtml(t('review.forecast-hint', { day: advice.dia, days: advice.dies_mes }))}
            </p>
            ${rows ? `
            <div class="table-responsive">
                <table class="table w-full text-sm">
                    <thead>
                        <tr>
                            <th class="text-left">${t('review.block')}</th>
                            <th class="text-right">${t('review.spent')}</th>
                            <th class="text-right">${t('review.forecast')}</th>
                            <th class="text-right">${t('review.plan')}</th>
                            <th class="text-left">${t('review.heading-for')}</th>
                            <th class="text-right" title="${t('review.can-spend-title')}">${t('review.can-spend')}</th>
                        </tr>
                    </thead>
                    <tbody>${rows}</tbody>
                </table>
            </div>` : `<p class="text-sm text-slate-500">${t('review.no-variable')}</p>`}
        </div>
    `;
}

/** Com blockRows: els fills, amagats fins que s'obre el bloc. */
function forecastRows(node, depth, ancestors) {
    const children = node.subcategories || [];
    const parent = ancestors[ancestors.length - 1];
    const perDay = Number(node.disponible_per_dia) || 0;
    const toggle = children.length > 0
        ? `<button type="button" class="text-slate-500 hover:text-primary mr-1" data-toggle="${node.categoria_id}"
                   aria-expanded="false" title="${t('review.show-subcategories')}">▸</button>`
        : '<span class="inline-block w-3 mr-1"></span>';
    const row = `
        <tr data-forecast="${node.categoria_id}" ${depth > 0 ? `data-parent="${parent}" data-ancestors="${ancestors.join(' ')}" class="hidden"` : ''}>
            <td style="padding-left: ${0.5 + depth * 1.25}rem">${toggle}<span class="${depth === 0 ? 'font-medium' : ''}">${escapeHtml(node.nom)}</span></td>
            <td class="text-right whitespace-nowrap">${formatCurrency(node.real)}</td>
            <td class="text-right whitespace-nowrap font-medium" title="${escapeHtml(forecastBasis(node.base_previsio))}">${formatCurrency(node.previsio)}</td>
            <td class="text-right whitespace-nowrap">${formatCurrency(node.pla)}</td>
            <td>${statusPill(node.estat_previst, 'VARIABLE')}</td>
            <td class="text-right whitespace-nowrap text-xs">${Number(node.pla) > 0
                ? escapeHtml(perDay > 0 ? t('review.per-day', { amount: formatCurrency(perDay) }) : t('review.nothing-left'))
                : ''}</td>
        </tr>
    `;
    const childAncestors = [...ancestors, node.categoria_id];
    return row + children.map(child => forecastRows(child, depth + 1, childAncestors)).join('');
}

// ============ LES PROPOSTES PER AL MES QUE VE ============

function proposalsCard(advice) {
    const recommendations = advice.recomanacions || [];
    const sample = formatList((advice.mesos_mostra || []).slice().reverse().map(monthLabel));
    const target = monthLabel(advice.mes_desti);

    let body;
    if ((advice.mesos_mostra || []).length < 2) {
        body = `<p class="text-sm text-slate-500">${t('review.need-two-months')}</p>`;
    } else if (recommendations.length === 0) {
        body = `<p class="text-sm text-slate-500">${t('review.no-changes')}</p>`;
    } else {
        body = `
            <div class="table-responsive">
                <table class="table w-full text-sm">
                    <thead>
                        <tr>
                            <th></th>
                            <th class="text-left">${t('review.block')}</th>
                            <th class="text-right">${t('review.now')}</th>
                            <th class="text-left">${t('review.recent-months-column')}</th>
                            <th class="text-right">${t('review.proposal')}</th>
                            <th class="text-left">${t('review.why')}</th>
                        </tr>
                    </thead>
                    <tbody>
                        ${recommendations.map(recommendation => `
                            <tr data-proposal="${recommendation.categoria_id}">
                                <td><input type="checkbox" class="proposal-check" value="${recommendation.categoria_id}" checked
                                           aria-label="${escapeHtml(t('review.apply-proposal-label', { name: recommendation.nom }))}"></td>
                                <td class="font-medium whitespace-nowrap">${escapeHtml(recommendation.nom)}</td>
                                <td class="text-right whitespace-nowrap">${planLabel(recommendation.pla_actual, recommendation.percentatge_actual)}</td>
                                <td class="text-xs text-slate-500 dark:text-slate-400 whitespace-nowrap">
                                    ${(recommendation.mesos || []).slice().reverse()
                                        .map(month => `${escapeHtml(shortMonthLabel(month.periode))} ${formatCurrency(month.real)}`).join(' · ')}
                                </td>
                                <td class="text-right whitespace-nowrap font-medium ${recommendation.tipus === 'PUJAR' ? 'text-amber-800 dark:text-amber-300' : 'text-emerald-700 dark:text-emerald-300'}">
                                    ${planLabel(recommendation.proposta, recommendation.percentatge_proposat)}
                                    <div class="text-xs font-normal">${formatDeviation(recommendation.diferencia)}</div>
                                </td>
                                <td class="text-xs text-slate-600 dark:text-slate-300">${escapeHtml(adviceReason(recommendation))}</td>
                            </tr>`).join('')}
                    </tbody>
                </table>
            </div>
            <div class="flex flex-wrap items-center justify-between gap-3 mt-3" id="proposals-footer">
                <p class="text-sm text-slate-600 dark:text-slate-300" id="proposals-effect"></p>
                <button type="button" class="btn btn-primary" id="apply-proposals">
                    ${escapeHtml(t('review.apply-ticked', { month: target }))}
                </button>
            </div>
            <p class="text-sm mt-2 hidden" id="proposals-result" role="status"></p>
        `;
    }

    return `
        <div class="bg-white dark:bg-slate-800 rounded-xl p-4 shadow-sm border border-slate-200 dark:border-slate-700" id="proposals-card">
            <h3 class="text-lg font-semibold mb-1">${escapeHtml(t('review.proposals-title', { month: target }))}</h3>
            <p class="text-xs text-slate-500 dark:text-slate-400 mb-3">
                ${escapeHtml(t('review.proposals-hint', { months: sample || t('review.recent-months') }))}
            </p>
            ${body}
        </div>
    `;
}

function setUpProposals(content, advice) {
    const card = content.querySelector('#proposals-card');
    const button = card?.querySelector('#apply-proposals');
    if (!button) return;

    const selectedIds = () => [...card.querySelectorAll('.proposal-check:checked')].map(check => check.value);
    const refresh = () => {
        const ids = selectedIds();
        card.querySelector('#proposals-effect').textContent = ids.length > 0
            ? netEffectText(netEffect(advice.recomanacions, ids))
            : t('review.none-ticked');
        button.disabled = ids.length === 0;
    };
    card.addEventListener('change', event => {
        if (event.target.classList.contains('proposal-check')) refresh();
    });
    refresh();

    button.addEventListener('click', async () => {
        const ids = selectedIds();
        const target = monthLabel(advice.mes_desti);
        const source = monthLabel(advice.periode);
        if (!confirm(t('review.apply-confirm', { target, source, count: ids.length }))) return;

        button.disabled = true;
        const result = card.querySelector('#proposals-result');
        try {
            const applied = await applyBudgetChanges(advice.mes_desti, changesFor(advice.recomanacions, ids));
            result.textContent = appliedText(applied, target);
            result.className = 'text-sm mt-2 text-emerald-700 dark:text-emerald-300';
            card.querySelectorAll('.proposal-check').forEach(check => { check.disabled = true; });
            card.querySelector('#proposals-footer').classList.add('hidden');
        } catch (error) {
            result.textContent = t('analysis.apply-failed', { message: error.message });
            result.className = 'text-sm mt-2 text-red-700 dark:text-red-300';
            button.disabled = false;
        }
    });
}
