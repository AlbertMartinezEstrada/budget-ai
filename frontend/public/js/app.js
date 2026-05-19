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

document.addEventListener('DOMContentLoaded', async () => {
    console.log('Budget AI Frontend Initialized');

    await loadAppState();

    const mainContentArea = document.getElementById('main-content');
    // Select all navigation links, including the header button
    const navLinks = document.querySelectorAll('.nav-link[data-view]');

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

    // Event Listeners for all navigation elements
    navLinks.forEach(link => {
        link.addEventListener('click', (e) => {
            e.preventDefault();
            const view = e.currentTarget.dataset.view;
            if (view) {
                navigate(view);
            }
        });
    });

    // Initial Load
    navigate('dashboard');
});
