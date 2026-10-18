import { getGeneralAnalysis, getCategoryMovements, formatCurrency, escapeHtml } from '../../api.js';
import { t, formatNumber, formatPercent, formatDate } from '../../i18n.js';
import {
    PERIOD_KINDS, periodOptions, periodRange, rangeLabel, SUMMARY_METRICS, changeOf, formatChange,
    structureShares, STRUCTURE_SERIES, EVOLUTION_SERIES, chartTheme, unclassifiedIsHigh, barWidths,
    trimEmptyLeading, axisMonth, structureAxisMax
} from '../../generalAnalysis.js';

/**
 * La pestanya General de l'anàlisi: un període (mes, trimestre, any o els
 * últims 12 mesos) amb context.
 *
 * Tot surt d'una sola crida (GET /analytics/general), amb el criteri de
 * MoneyFlows com la resta de l'aplicació. Els moviments d'una categoria es
 * demanen en desplegar-la (GET /analytics/category-movements).
 *
 * Els gràfics són de Chart.js (vendor/chart.js, global Chart). Si no s'ha
 * carregat, les dades segueixen a les taules: cap xifra depèn del gràfic.
 */

/** Els gràfics vius, per destruir-los abans de pintar-ne de nous. */
let charts = [];

const card = 'bg-white dark:bg-slate-800 rounded-xl p-4 shadow-sm border border-slate-200 dark:border-slate-700';
const TONE_CLASSES = {
    good: 'text-emerald-700 dark:text-emerald-300',
    bad: 'text-red-700 dark:text-red-300',
    neutral: 'text-slate-500 dark:text-slate-400'
};

export async function initGeneral(container) {
    container.innerHTML = `
        <div class="flex flex-wrap items-end gap-3 mb-4">
            <div>
                <label class="block text-xs text-slate-500 dark:text-slate-400 mb-1" for="general-kind">${t('general.period')}</label>
                <select id="general-kind" class="pl-3 pr-10 py-2 border rounded-lg">
                    ${PERIOD_KINDS.map(kind => `<option value="${kind.id}">${t(kind.label)}</option>`).join('')}
                </select>
            </div>
            <div id="general-value-wrapper">
                <label class="block text-xs text-slate-500 dark:text-slate-400 mb-1" for="general-value">&nbsp;</label>
                <select id="general-value" class="pl-3 pr-10 py-2 border rounded-lg"></select>
            </div>
            <p class="text-sm text-slate-500 dark:text-slate-400 flex-1 min-w-0" id="general-range"></p>
        </div>
        <div id="general-content" class="space-y-6">
            <p class="text-sm text-slate-500">${t('common.loading')}</p>
        </div>
    `;

    const kindSelect = container.querySelector('#general-kind');
    const valueSelect = container.querySelector('#general-value');
    const content = container.querySelector('#general-content');

    const fillValues = () => {
        const options = periodOptions(kindSelect.value);
        valueSelect.innerHTML = options.map(option =>
            `<option value="${escapeHtml(option.value)}">${escapeHtml(option.label)}</option>`).join('');
        container.querySelector('#general-value-wrapper').classList.toggle('hidden', options.length === 0);
    };

    const load = async () => {
        const { from, to } = periodRange(kindSelect.value, valueSelect.value);
        try {
            const result = await getGeneralAnalysis(from, to);
            container.querySelector('#general-range').textContent = result.anterior
                ? t('general.range-versus', {
                    range: rangeLabel(result.des_de, result.fins_a),
                    previous: rangeLabel(result.anterior.des_de, result.anterior.fins_a)
                })
                : rangeLabel(result.des_de, result.fins_a);
            render(content, result);
        } catch (error) {
            content.innerHTML = `<div class="p-3 rounded-lg text-sm bg-red-100 text-red-700 dark:bg-red-500/20 dark:text-red-300">
                ${escapeHtml(t('general.load-failed', { message: error.message }))}</div>`;
        }
    };

    kindSelect.addEventListener('change', () => { fillValues(); load(); });
    valueSelect.addEventListener('change', load);
    content.addEventListener('click', event => toggleCategory(event, content));

    fillValues();
    await load();
}

function render(content, result) {
    charts.forEach(chart => chart.destroy());
    charts = [];
    content.dataset.from = result.des_de;
    content.dataset.to = result.fins_a;

    content.innerHTML = `
        ${unclassifiedNotice(result.sense_classificar)}
        <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
            ${SUMMARY_METRICS.map(metric => summaryCard(metric, result)).join('')}
        </div>
        <div class="grid grid-cols-1 lg:grid-cols-2 gap-6">
            <div class="${card}">
                <h3 class="text-lg font-semibold mb-1">${t('general.evolution')}</h3>
                <p class="text-xs text-slate-500 dark:text-slate-400 mb-3">${escapeHtml(t('general.evolution-hint', { month: rangeLabel(result.fins_a, result.fins_a) }))}</p>
                <div class="relative h-64"><canvas id="evolution-chart" role="img"
                     aria-label="${t('general.evolution-chart')}"></canvas></div>
                ${evolutionTable(result.evolucio)}
            </div>
            <div class="${card}">
                <h3 class="text-lg font-semibold mb-1">${t('general.structure')}</h3>
                <p class="text-xs text-slate-500 dark:text-slate-400 mb-3">${t('general.structure-hint')}</p>
                <div class="relative" style="height: ${Math.max(120,
                    trimEmptyLeading(result.estructura, ['ingressos', 'fixos', 'variables', 'estalvi']).length * 34 + 70)}px">
                    <canvas id="structure-chart" role="img" aria-label="${t('general.structure-chart')}"></canvas>
                </div>
                ${structureTable(result.estructura)}
            </div>
        </div>
        <div class="${card}">
            <h3 class="text-lg font-semibold mb-1">${t('general.by-category')}</h3>
            <p class="text-xs text-slate-500 dark:text-slate-400 mb-3">${t('general.by-category-hint')}</p>
            ${categoryList(result.categories, 0)}
        </div>
        <div class="grid grid-cols-1 lg:grid-cols-2 gap-6">
            ${merchantCard(t('general.top-spend'), result.comercos?.per_import, 'total')}
            ${merchantCard(t('general.top-visits'), result.comercos?.per_visites, 'moviments')}
        </div>
        ${smallExpensesCard(result.despesa_formiga)}
    `;

    drawCharts(content, result);
}

// ============ LES XIFRES DE DALT ============

function summaryCard(metric, result) {
    const value = result.resum?.[metric.key];
    const previous = changeOf(value, result.anterior?.[metric.key], metric.upIsGood);
    const usual = result.habitual ? changeOf(value, result.habitual[metric.key], metric.upIsGood) : null;
    const line = (change, label) => change
        ? `<div class="text-xs ${TONE_CLASSES[change.tone]}">${escapeHtml(formatChange(change))}
               <span class="text-slate-500 dark:text-slate-400">${escapeHtml(label)}</span></div>`
        : '';
    const rate = metric.key === 'total_savings' && result.resum?.savings_rate != null
        ? `<div class="text-xs text-slate-500 dark:text-slate-400">${escapeHtml(t('general.savings-rate', { percent: formatPercent(result.resum.savings_rate) }))}</div>`
        : '';
    return `
        <div class="${card}" data-metric="${metric.key}">
            <div class="text-sm text-slate-500 dark:text-slate-400 mb-1">${t(metric.label)}</div>
            <div class="text-2xl font-bold">${formatCurrency(value)}</div>
            ${rate}
            ${result.anterior ? line(previous, t('general.versus', { range: rangeLabel(result.anterior.des_de, result.anterior.fins_a) })) : ''}
            ${line(usual, t('general.versus-usual'))}
        </div>
    `;
}

function unclassifiedNotice(unclassified) {
    const total = Number.parseFloat(unclassified?.total) || 0;
    if (total <= 0) return '';
    const count = Number(unclassified.moviments) || 0;
    const text = unclassified.percentatge != null
        ? t('general.unclassified', {
            amount: formatCurrency(total), count, percent: formatPercent(unclassified.percentatge, 0)
        })
        : t('general.unclassified-no-percent', { amount: formatCurrency(total), count });
    if (!unclassifiedIsHigh(unclassified)) {
        return `<p class="text-xs text-slate-500 dark:text-slate-400" id="unclassified-notice">${escapeHtml(text)}</p>`;
    }
    return `<div class="p-3 rounded-lg text-sm bg-amber-100 text-amber-800 dark:bg-amber-500/20 dark:text-amber-300" id="unclassified-notice">
        ${escapeHtml(text)} ${t('general.unclassified-high')}
        <a href="#transactions" class="underline">${t('general.unclassified-link')}</a>.
    </div>`;
}

// ============ ELS GRÀFICS ============

function drawCharts(content, result) {
    const Chart = window.Chart;
    if (!Chart) return;
    const theme = chartTheme(document.documentElement.classList.contains('dark'));
    Chart.defaults.font.family = getComputedStyle(document.body).fontFamily;
    Chart.defaults.color = theme.muted;

    // Sense els mesos d'abans de fer servir l'aplicació, que semblarien a zero.
    const evolution = trimEmptyLeading(result.evolucio, EVOLUTION_SERIES.map(series => series.key));
    const evolutionCanvas = content.querySelector('#evolution-chart');
    if (evolutionCanvas && evolution.length > 0) {
        charts.push(new Chart(evolutionCanvas, {
            type: 'line',
            data: {
                labels: evolution.map(month => axisMonth(month.period)),
                datasets: EVOLUTION_SERIES.map((series, index) => ({
                    label: t(series.label),
                    data: evolution.map(month => Number.parseFloat(month[series.key]) || 0),
                    borderColor: theme.series[index],
                    backgroundColor: theme.series[index],
                    borderWidth: 2,
                    pointRadius: 4,
                    pointHoverRadius: 6,
                    pointBorderColor: theme.surface,
                    pointBorderWidth: 2,
                    borderCapStyle: 'round',
                    borderJoinStyle: 'round'
                }))
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                interaction: { mode: 'index', intersect: false },
                plugins: {
                    legend: { position: 'bottom', labels: { usePointStyle: true, boxWidth: 8, color: theme.muted } },
                    tooltip: { callbacks: { label: context => `${context.dataset.label}: ${formatCurrency(context.parsed.y)}` } }
                },
                scales: {
                    x: { grid: { display: false }, border: { color: theme.grid }, ticks: { color: theme.muted } },
                    y: {
                        grid: { color: theme.grid }, border: { display: false },
                        ticks: { color: theme.muted, callback: value => compact(value) }
                    }
                }
            }
        }));
    }

    const rows = trimEmptyLeading(result.estructura, ['ingressos', 'fixos', 'variables', 'estalvi'])
        .map(structureShares);
    const structureCanvas = content.querySelector('#structure-chart');
    if (structureCanvas && rows.length > 0) {
        const amountsByMonth = Object.fromEntries((result.estructura || []).map(month => [month.periode, month]));
        charts.push(new Chart(structureCanvas, {
            type: 'bar',
            data: {
                labels: rows.map(row => (row.shares
                    ? axisMonth(row.periode)
                    : t('general.axis-no-income', { month: axisMonth(row.periode) }))),
                datasets: STRUCTURE_SERIES.map((series, index) => ({
                    label: t(series.label),
                    data: rows.map(row => (row.shares ? row.shares[series.key] : 0)),
                    backgroundColor: theme.series[index],
                    // El buit de 2 px entre trams, del color de la superfície.
                    borderColor: theme.surface,
                    borderWidth: 1,
                    borderSkipped: false,
                    maxBarThickness: 24
                }))
            },
            options: {
                indexAxis: 'y',
                responsive: true,
                maintainAspectRatio: false,
                plugins: {
                    legend: { position: 'bottom', labels: { usePointStyle: true, boxWidth: 8, color: theme.muted } },
                    tooltip: {
                        callbacks: {
                            label: context => {
                                const month = amountsByMonth[rows[context.dataIndex].periode];
                                const key = STRUCTURE_SERIES[context.datasetIndex].key;
                                return `${context.dataset.label}: ${formatPercent(context.parsed.x, 1, 0)} (${formatCurrency(month[key])})`;
                            }
                        }
                    }
                },
                scales: {
                    x: {
                        stacked: true, max: structureAxisMax(rows),
                        grid: { color: theme.grid }, border: { display: false },
                        ticks: { color: theme.muted, callback: value => formatPercent(value, 0) }
                    },
                    y: { stacked: true, grid: { display: false }, border: { color: theme.grid }, ticks: { color: theme.muted } }
                }
            }
        }));
    }
}

/** 0, 500, 1 k, 1,5 k: els eixos no necessiten cèntims. */
function compact(value) {
    return formatNumber(Number(value) || 0, { notation: 'compact', maximumFractionDigits: 1 });
}

/** Les mateixes dades en una taula: el gràfic no és l'única manera de llegir-les. */
function evolutionTable(evolution) {
    return `
        <details class="mt-3 text-sm">
            <summary class="cursor-pointer text-xs text-slate-500 dark:text-slate-400">${t('general.as-table')}</summary>
            <div class="table-responsive mt-2">
                <table class="table w-full text-sm">
                    <thead><tr><th class="text-left">${t('common.month')}</th>${EVOLUTION_SERIES.map(series => `<th class="text-right">${t(series.label)}</th>`).join('')}</tr></thead>
                    <tbody>
                        ${(evolution || []).map(month => `<tr>
                            <td>${escapeHtml(rangeLabel(month.period, month.period))}</td>
                            ${EVOLUTION_SERIES.map(series => `<td class="text-right whitespace-nowrap">${formatCurrency(month[series.key])}</td>`).join('')}
                        </tr>`).join('')}
                    </tbody>
                </table>
            </div>
        </details>
    `;
}

function structureTable(structure) {
    return `
        <details class="mt-3 text-sm">
            <summary class="cursor-pointer text-xs text-slate-500 dark:text-slate-400">${t('general.as-table')}</summary>
            <div class="table-responsive mt-2">
                <table class="table w-full text-sm">
                    <thead><tr>
                        <th class="text-left">${t('common.month')}</th><th class="text-right">${t('general.income')}</th>
                        ${STRUCTURE_SERIES.map(series => `<th class="text-right">${t(series.label)}</th>`).join('')}
                        <th class="text-right">${t('general.from-fund')}</th>
                    </tr></thead>
                    <tbody>
                        ${(structure || []).map(month => {
                            const row = structureShares(month);
                            const share = key => (row.shares ? ` <span class="text-xs text-slate-500">${formatPercent(row.shares[key], 1, 0)}</span>` : '');
                            return `<tr>
                                <td>${escapeHtml(rangeLabel(month.periode, month.periode))}${row.excedit
                                    ? ` <span class="text-xs text-red-700 dark:text-red-300">${escapeHtml(t('general.overspent', { percent: formatPercent(row.excedit, 1, 0) }))}</span>` : ''}</td>
                                <td class="text-right whitespace-nowrap">${formatCurrency(month.ingressos)}</td>
                                ${STRUCTURE_SERIES.map(series => `<td class="text-right whitespace-nowrap">${formatCurrency(month[series.key])}${share(series.key)}</td>`).join('')}
                                <td class="text-right whitespace-nowrap">${formatCurrency(month.gastat_del_fons)}</td>
                            </tr>`;
                        }).join('')}
                    </tbody>
                </table>
            </div>
        </details>
    `;
}

// ============ LES CATEGORIES ============

/** Una llista de categories germanes, cadascuna amb la seva barra relativa a la més gran. */
function categoryList(nodes, depth) {
    if (!nodes || nodes.length === 0) {
        return depth === 0 ? `<p class="text-sm text-slate-500">${t('general.no-spending')}</p>` : '';
    }
    const widths = barWidths(nodes);
    return `<ul class="space-y-2 ${depth > 0 ? 'mt-2 pl-4 border-l-2 border-slate-200 dark:border-slate-700' : ''}">
        ${nodes.map((node, index) => {
            const key = node.categoria_id ?? 'none';
            const hasChildren = (node.subcategories || []).length > 0;
            const action = hasChildren ? `data-cat-toggle="${key}"` : `data-cat-movements="${key}"`;
            return `<li data-category="${key}">
                <button type="button" class="w-full text-left" ${action} aria-expanded="false">
                    <div class="flex items-center justify-between gap-3 text-sm">
                        <span class="${depth === 0 ? 'font-medium' : ''}">
                            <span class="text-slate-500 mr-1" aria-hidden="true">▸</span>${escapeHtml(node.nom)}
                        </span>
                        <span class="whitespace-nowrap">
                            <span class="font-medium">${formatCurrency(node.total)}</span>
                            <span class="text-xs text-slate-500 dark:text-slate-400">
                                ${node.percentatge != null ? `${formatPercent(node.percentatge, 0)} · ` : ''}${escapeHtml(t('general.movements-short', { count: node.moviments }))}
                            </span>
                        </span>
                    </div>
                    <div class="h-2 bg-slate-100 dark:bg-slate-700 rounded-full mt-1">
                        <div class="h-full rounded-full" style="width: ${widths[index]}%; background-color: ${chartTheme(document.documentElement.classList.contains('dark')).series[0]}"></div>
                    </div>
                </button>
                <div class="hidden" data-cat-children="${key}">
                    ${hasChildren ? categoryList(node.subcategories, depth + 1) : ''}
                </div>
            </li>`;
        }).join('')}
    </ul>`;
}

async function toggleCategory(event, content) {
    const button = event.target.closest('[data-cat-toggle], [data-cat-movements]');
    if (!button) return;
    const key = button.dataset.catToggle ?? button.dataset.catMovements;
    const children = button.parentElement.querySelector(`:scope > [data-cat-children="${key}"]`);
    const expand = button.getAttribute('aria-expanded') !== 'true';
    button.setAttribute('aria-expanded', String(expand));
    button.querySelector('[aria-hidden]').textContent = expand ? '▾' : '▸';
    children.classList.toggle('hidden', !expand);

    // Els moviments es demanen el primer cop que s'obre la subcategoria.
    if (!expand || button.dataset.catMovements === undefined || children.dataset.loaded) return;
    children.dataset.loaded = 'true';
    children.innerHTML = `<p class="text-xs text-slate-500 mt-2 pl-4">${t('general.loading-movements')}</p>`;
    try {
        const movements = await getCategoryMovements(key === 'none' ? null : key, content.dataset.from, content.dataset.to);
        children.innerHTML = movementList(movements);
    } catch (error) {
        delete children.dataset.loaded;
        children.innerHTML = `<p class="text-xs text-red-700 dark:text-red-300 mt-2 pl-4">${escapeHtml(t('general.movements-failed', { message: error.message }))}</p>`;
    }
}

function movementList(movements) {
    if (!movements || movements.length === 0) return `<p class="text-xs text-slate-500 mt-2 pl-4">${t('general.no-movements')}</p>`;
    return `<div class="mt-2 pl-4 max-h-64 overflow-y-auto">
        <table class="w-full text-xs">
            <tbody class="divide-y divide-slate-100 dark:divide-slate-700">
                ${movements.map(movement => `<tr data-movement>
                    <td class="py-1 pr-2 whitespace-nowrap text-slate-500 dark:text-slate-400">${escapeHtml(formatDate(movement.data) || movement.data)}</td>
                    <td class="py-1 pr-2">${escapeHtml(movement.empresa)}${movement.descripcio && movement.descripcio !== movement.empresa
                        ? ` <span class="text-slate-500">· ${escapeHtml(movement.descripcio)}</span>` : ''}${movement.es_part
                        ? ` <span class="text-slate-500">${t('general.part')}</span>` : ''}</td>
                    <td class="py-1 text-right whitespace-nowrap">${formatCurrency(movement.import)}</td>
                </tr>`).join('')}
            </tbody>
        </table>
    </div>`;
}

// ============ ON ES GASTA ============

function merchantCard(title, merchants, key) {
    const list = merchants || [];
    const widths = barWidths(list, key);
    const color = chartTheme(document.documentElement.classList.contains('dark')).series[0];
    return `
        <div class="${card}" data-merchants="${key}">
            <h3 class="text-lg font-semibold mb-3">${escapeHtml(title)}</h3>
            ${list.length === 0 ? `<p class="text-sm text-slate-500">${t('general.no-spending')}</p>` : `
            <ul class="space-y-2">
                ${list.map((merchant, index) => `<li>
                    <div class="flex items-center justify-between gap-3 text-sm">
                        <span>${escapeHtml(merchant.nom)}</span>
                        <span class="whitespace-nowrap">
                            <span class="font-medium">${key === 'moviments' ? escapeHtml(t('general.times', { count: merchant.moviments })) : formatCurrency(merchant.total)}</span>
                            <span class="text-xs text-slate-500 dark:text-slate-400">
                                ${key === 'moviments' ? formatCurrency(merchant.total) : escapeHtml(t('general.times', { count: merchant.moviments }))}
                            </span>
                        </span>
                    </div>
                    <div class="h-1.5 bg-slate-100 dark:bg-slate-700 rounded-full mt-1">
                        <div class="h-full rounded-full" style="width: ${widths[index]}%; background-color: ${color}"></div>
                    </div>
                </li>`).join('')}
            </ul>`}
        </div>
    `;
}

function smallExpensesCard(small) {
    if (!small) return '';
    const merchants = small.comercos || [];
    return `
        <div class="${card}" id="small-expenses">
            <h3 class="text-lg font-semibold mb-1">${t('general.small')}</h3>
            <p class="text-xs text-slate-500 dark:text-slate-400 mb-3">
                ${escapeHtml(t('general.small-hint', { amount: formatCurrency(small.llindar) }))}
            </p>
            <div class="text-2xl font-bold">${formatCurrency(small.total)}</div>
            <div class="text-xs text-slate-500 dark:text-slate-400 mb-3">
                ${escapeHtml(t('general.small-count', { count: small.moviments }))}${small.percentatge != null
                    ? ` · ${escapeHtml(t('general.small-share', { percent: formatPercent(small.percentatge) }))}` : ''}
            </div>
            ${merchants.length === 0 ? '' : `
            <p class="text-xs text-slate-500 dark:text-slate-400 mb-1">${t('general.small-most')}</p>
            <ul class="text-sm divide-y divide-slate-100 dark:divide-slate-700">
                ${merchants.map(merchant => `<li class="flex justify-between gap-3 py-1">
                    <span>${escapeHtml(merchant.nom)}</span>
                    <span class="whitespace-nowrap">${formatCurrency(merchant.total)}
                        <span class="text-xs text-slate-500 dark:text-slate-400">${escapeHtml(t('general.times', { count: merchant.moviments }))}</span></span>
                </li>`).join('')}
            </ul>`}
        </div>
    `;
}
