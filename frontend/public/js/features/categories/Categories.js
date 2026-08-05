import { getCategories, escapeHtml } from '../../api.js';

export async function initCategories(container) {
    container.innerHTML = `
        <div class="page-header flex justify-between items-center mb-6">
            <h2 class="text-2xl font-bold text-slate-800 dark:text-slate-100">Categorías</h2>
        </div>
        <div id="categories-list" class="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-4"></div>
    `;

    await loadCategories();
}

async function loadCategories() {
    try {
        const categories = await getCategories();
        renderCategories(categories);
    } catch (error) {
        console.error('Error loading categories:', error);
        document.getElementById('categories-list').innerHTML = '<p class="text-red-500">Error al cargar categorías</p>';
    }
}

function renderCategories(categories) {
    const container = document.getElementById('categories-list');
    if (!categories || categories.length === 0) {
        container.innerHTML = '<p class="text-gray-500 dark:text-slate-400 col-span-4 text-center py-8">No hay categorías</p>';
        return;
    }

    const colors = ['#4f46e5', '#10b981', '#f59e0b', '#ef4444', '#8b5cf6', '#06b6d4', '#ec4899', '#84cc16'];

    container.innerHTML = categories.map((cat, idx) => `
        <div class="bg-white dark:bg-slate-800 rounded-xl p-4 shadow-sm border border-slate-200 dark:border-slate-700 flex items-center gap-3">
            <div class="w-10 h-10 rounded-lg flex items-center justify-center" style="background-color: ${colors[idx % colors.length]}20">
                <span class="material-symbols-outlined" style="color: ${colors[idx % colors.length]}">label</span>
            </div>
            <div>
                <h3 class="font-semibold">${escapeHtml(cat.nom)}</h3>
                <span class="text-xs text-gray-500 dark:text-slate-400">${escapeHtml(cat.tipus || 'Sin tipo')}</span>
            </div>
        </div>
    `).join('');
}