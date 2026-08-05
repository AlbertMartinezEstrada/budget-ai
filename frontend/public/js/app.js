// Main Application Entry Point
import { initDashboard } from './features/dashboard/Dashboard.js';
import { loadAppState } from './api.js';
import { initTransactions } from './features/transactions/TransactionList.js';
import { initUpload } from './features/upload/FileUploader.js';
import { initAccounts } from './features/accounts/Accounts.js';
import { initBudgets } from './features/budgets/Budgets.js';
import { initCategories } from './features/categories/Categories.js';
import { initGoals } from './features/goals/FinancialGoals.js';
import { initTransfers } from './features/transfers/Transfers.js';
import { initRecurring } from './features/recurring/RecurringTransactions.js';
import { initAnalytics } from './features/analytics/Analytics.js';
import { initSettings } from './features/settings/Settings.js';

const VIEWS = [
    'dashboard', 'transactions', 'upload', 'accounts', 'budgets',
    'categories', 'goals', 'transfers', 'recurring', 'analytics', 'settings'
];

document.addEventListener('DOMContentLoaded', async () => {
    console.log('Budget AI Frontend Initialized');

    await loadAppState();

    const mainContentArea = document.getElementById('main-content');

    function navigate(view) {
        // Update Active Link in the sidebar
        const sidebarLinks = document.querySelectorAll('.sidebar .nav-link');
        sidebarLinks.forEach(link => {
            // Reset styles for all links
            link.classList.remove('bg-primary/10', 'text-primary');
            link.classList.add('text-slate-600', 'dark:text-slate-400', 'hover:bg-slate-50', 'dark:hover:bg-slate-800');

            // Apply active styles to the correct link
            if (link.dataset.view === view) {
                link.classList.remove('text-slate-600', 'dark:text-slate-400', 'hover:bg-slate-50', 'dark:hover:bg-slate-800');
                link.classList.add('bg-primary/10', 'text-primary');
            }
        });

        // Clear Content
        mainContentArea.innerHTML = '';

        // Load View
        switch(view) {
            case 'dashboard':
                initDashboard(mainContentArea);
                break;
            case 'transactions':
                initTransactions(mainContentArea);
                break;
            case 'upload':
                initUpload(mainContentArea);
                break;
            case 'accounts':
                initAccounts(mainContentArea);
                break;
            case 'budgets':
                initBudgets(mainContentArea);
                break;
            case 'categories':
                initCategories(mainContentArea);
                break;
            case 'goals':
                initGoals(mainContentArea);
                break;
            case 'transfers':
                initTransfers(mainContentArea);
                break;
            case 'recurring':
                initRecurring(mainContentArea);
                break;
            case 'analytics':
                initAnalytics(mainContentArea);
                break;
            case 'settings':
                initSettings(mainContentArea);
                break;
            default:
                initDashboard(mainContentArea); // Default to dashboard
        }
    }

    // Delegació a tot el document: abans només s'enganxaven els listeners als
    // enllaços que existien en carregar la pàgina, així que qualsevol botó
    // creat després dins d'una vista (com el "Veure tot" del tauler) no feia
    // res en clicar-lo.
    document.addEventListener('click', (e) => {
        const trigger = e.target.closest('[data-view]');
        if (!trigger) return;

        e.preventDefault();
        const view = trigger.dataset.view;
        if (!VIEWS.includes(view)) return;

        // Canviar el hash dispara hashchange, que és qui navega de debò.
        if (currentView() === view) {
            navigate(view);
        } else {
            window.location.hash = view;
        }
    });

    // Routing per hash: la URL passa a reflectir la vista, de manera que
    // recarregar manté la pantalla, el botó d'enrere funciona i els enllaços
    // es poden compartir. Abans tot això es perdia a cada recàrrega.
    window.addEventListener('hashchange', () => navigate(currentView()));

    // Initial Load
    navigate(currentView());
});

function currentView() {
    const view = window.location.hash.replace(/^#/, '');
    return VIEWS.includes(view) ? view : 'dashboard';
}
