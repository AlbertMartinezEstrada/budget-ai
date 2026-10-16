// Main Application Entry Point
import { initDashboard } from './features/dashboard/Dashboard.js';
import { loadAppState, getCurrentUser, logout, onUnauthorized } from './api.js';
import { showLogin } from './features/auth/Login.js';
import { initTransactions } from './features/transactions/TransactionList.js';
import { initUpload } from './features/upload/FileUploader.js';
import { initAccounts } from './features/accounts/Accounts.js';
import { initBudgets } from './features/budgets/Budgets.js';
import { initCategories } from './features/categories/Categories.js';
import { initGoals } from './features/goals/FinancialGoals.js';
import { initDebts } from './features/debts/Debts.js';
import { initTransfers } from './features/transfers/Transfers.js';
import { initRecurring } from './features/recurring/RecurringTransactions.js';
import { initAnalytics } from './features/analytics/Analytics.js';
import { initSettings } from './features/settings/Settings.js';

const VIEWS = [
    'dashboard', 'transactions', 'upload', 'accounts', 'budgets',
    'categories', 'goals', 'debts', 'transfers', 'recurring', 'analytics', 'settings'
];

document.addEventListener('DOMContentLoaded', async () => {
    console.log('Budget AI Frontend Initialized');

    // Res no es carrega fins que hi ha sessió: si no n'hi ha, es mostra la
    // pantalla d'entrada i l'aplicació ni tan sols demana dades.
    let user;
    try {
        user = await getCurrentUser();
    } catch (error) {
        // Amb el backend aturat, la pàgina es quedava en blanc sense dir per
        // què. La pantalla d'entrada ensenya el motiu, i entrar ho torna a provar.
        showLogin({ onSuccess: startApp, message: error.message });
        return;
    }
    if (!user) {
        showLogin({ onSuccess: startApp });
        return;
    }

    await startApp();
});

async function startApp() {
    await loadAppState();

    // Si la sessió caduca a mig ús, qualsevol crida que rebi un 401 fa
    // tornar a la pantalla d'entrada en comptes de deixar la vista buida.
    onUnauthorized(() => {
        showLogin({
            message: 'La sessió ha caducat. Torna a entrar.',
            onSuccess: startApp
        });
    });

    const mainContentArea = document.getElementById('main-content');

    /** @param sub el que va després de la vista a l'adreça: #analytics/presupuesto */
    function navigate(view, sub = '') {
        // L'enllaç de la vista oberta, marcat. Els colors són a layout.css: la
        // barra és fosca en tots dos temes.
        document.querySelectorAll('.sidebar .nav-link').forEach(link => {
            link.classList.toggle('active', link.dataset.view === view);
        });

        // El títol de la capçalera i de la pestanya, el de la vista oberta.
        // Abans deia sempre «Resum Financer», també a Transaccions o a Anàlisi.
        const title = viewTitle(view);
        document.getElementById('page-title').textContent = title;
        document.title = `${title} · Budget AI`;

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
            case 'debts':
                initDebts(mainContentArea);
                break;
            case 'transfers':
                initTransfers(mainContentArea);
                break;
            case 'recurring':
                initRecurring(mainContentArea);
                break;
            case 'analytics':
                initAnalytics(mainContentArea, sub);
                break;
            case 'settings':
                initSettings(mainContentArea);
                break;
            default:
                initDashboard(mainContentArea); // Default to dashboard
        }
    }

    // startApp es torna a executar després de tornar a entrar, i els listeners
    // globals només s'han d'enganxar una vegada.
    if (!listenersAttached) {
        listenersAttached = true;

        // Delegació a tot el document: abans només s'enganxaven els listeners
        // als enllaços que existien en carregar la pàgina, així que qualsevol
        // botó creat després dins d'una vista (com el "Veure tot" del tauler)
        // no feia res en clicar-lo.
        document.addEventListener('click', (event) => {
            const trigger = event.target.closest('[data-view]');
            if (trigger) {
                event.preventDefault();
                const view = trigger.dataset.view;
                if (!VIEWS.includes(view)) return;

                // Canviar el hash dispara hashchange, que és qui navega de debò.
                // Des d'una pestanya (#analytics/presupuesto), l'enllaç de la
                // barra torna a la vista sense pestanya.
                if (window.location.hash === `#${view}`) {
                    navigate(view);
                } else {
                    window.location.hash = view;
                }
                return;
            }

            if (event.target.closest('#logout-btn')) {
                event.preventDefault();
                handleLogout();
            }
        });

        // Routing per hash: la URL passa a reflectir la vista, de manera que
        // recarregar manté la pantalla, el botó d'enrere funciona i els
        // enllaços es poden compartir.
        window.addEventListener('hashchange', () => navigate(currentView(), currentSubview()));
    }

    // Initial Load
    navigate(currentView(), currentSubview());
}

let listenersAttached = false;

async function handleLogout() {
    try {
        await logout();
    } finally {
        // Encara que la crida falli, es buida la pantalla: deixar l'aplicació
        // visible després de prémer "Sortir" seria enganyós.
        document.getElementById('main-content').innerHTML = '';
        showLogin({ onSuccess: startApp });
    }
}

// L'adreça és #vista o #vista/pestanya.
function hashParts() {
    return window.location.hash.replace(/^#/, '').split('/');
}

function currentView() {
    const [view] = hashParts();
    return VIEWS.includes(view) ? view : 'dashboard';
}

function currentSubview() {
    const [view, ...rest] = hashParts();
    return VIEWS.includes(view) ? rest.join('/') : '';
}

/**
 * El nom de la vista tal com surt al menú (o al botó d'importar, que no hi és):
 * així la capçalera sempre diu el mateix que l'enllaç que s'ha clicat.
 */
function viewTitle(view) {
    const link = [...document.querySelectorAll('.nav-link[data-view]')].find(candidate => candidate.dataset.view === view);
    const label = link?.querySelector('span:last-child')?.textContent.trim();
    return label || 'Budget AI';
}
