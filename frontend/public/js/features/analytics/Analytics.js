import { escapeHtml } from '../../api.js';
import { t } from '../../i18n.js';
import { initBudgetReview } from './BudgetReview.js';
import { initGeneral } from './General.js';
import { initDecisions } from './Decisions.js';

/**
 * Les pestanyes de l'anàlisi. Cadascuna té la seva adreça (#analytics,
 * #analytics/presupuesto): recarregar la pàgina o tornar enrere hi torna.
 */
const TABS = [
    { id: 'general', label: 'analysis.tab-general', hash: '#analytics', description: 'analysis.general-description' },
    {
        id: 'presupuesto',
        label: 'analysis.tab-budget',
        hash: '#analytics/presupuesto',
        description: 'analysis.budget-description'
    },
    {
        id: 'decisiones',
        label: 'analysis.tab-decisions',
        hash: '#analytics/decisiones',
        description: 'analysis.decisions-description'
    }
];

/** @param tab la part de l'adreça després de #analytics/; buida, General */
export async function initAnalytics(container, tab = '') {
    const active = TABS.find(candidate => candidate.id === tab) || TABS[0];

    container.innerHTML = `
        <div class="page-header mb-4">
            <h2 class="text-2xl font-bold text-slate-800 dark:text-slate-100">${t('nav.analytics')}</h2>
            <p class="text-sm text-slate-500 dark:text-slate-400">${escapeHtml(t(active.description))}</p>
        </div>
        <nav class="flex gap-1 border-b border-slate-200 dark:border-slate-700 mb-6" role="tablist">
            ${TABS.map(candidate => `
                <a href="${candidate.hash}" role="tab" data-tab="${candidate.id}"
                   aria-selected="${candidate === active}"
                   class="px-4 py-2 -mb-px border-b-2 text-sm ${candidate === active
                       ? 'border-primary text-primary font-medium'
                       : 'border-transparent text-slate-500 dark:text-slate-400 hover:text-primary'}">
                    ${escapeHtml(t(candidate.label))}
                </a>`).join('')}
        </nav>
        <div id="analytics-tab"></div>
    `;

    const tabContainer = container.querySelector('#analytics-tab');
    if (active.id === 'presupuesto') {
        await initBudgetReview(tabContainer);
        return;
    }
    if (active.id === 'decisiones') {
        await initDecisions(tabContainer);
        return;
    }
    await initGeneral(tabContainer);
}
