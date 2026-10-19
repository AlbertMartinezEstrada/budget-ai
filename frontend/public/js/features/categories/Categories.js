import {
    getCategories, createCategory, updateCategory, deleteCategory, escapeHtml
} from '../../api.js';
import { categoryOptions, sectionLabel } from '../../categoryOptions.js';
import { t } from '../../i18n.js';

// Tailwind no pot generar classes construïdes en temps d'execució.
const BADGE_CLASSES = {
    group: 'bg-indigo-100 text-indigo-700',
    fixed: 'bg-blue-100 text-blue-700',
    variable: 'bg-slate-100 text-slate-600'
};

let categories = [];

export async function initCategories(container) {
    container.innerHTML = `
        <div class="page-header flex justify-between items-center mb-6">
            <div>
                <h2 class="text-2xl font-bold text-slate-800 dark:text-slate-100">${t('nav.categories')}</h2>
                <p class="text-sm text-gray-500 dark:text-slate-400 mt-1">
                    ${t('categories.subtitle')}
                </p>
            </div>
            <button id="add-category-btn" class="bg-primary text-white px-4 py-2 rounded-2xl hover:bg-primary/90 flex items-center gap-2">
                <span class="material-symbols-outlined">add</span>
                ${t('categories.add')}
            </button>
        </div>

        <div class="bg-white dark:bg-slate-800 rounded-3xl p-4 shadow-xs border border-slate-200 dark:border-slate-700 mb-6 text-sm text-gray-600 dark:text-slate-300">
            <p><strong>${t('categories.legend-group-term')}</strong>: ${t('categories.legend-group')}</p>
            <p><strong>${t('categories.legend-fixed-term')}</strong>: ${t('categories.legend-fixed')}</p>
            <p><strong>${t('categories.legend-variable-term')}</strong>: ${t('categories.legend-variable')}</p>
        </div>

        <div id="categories-list" class="space-y-4"></div>

        <!-- Modal -->
        <div id="category-modal" class="fixed inset-0 bg-black/50 hidden items-center justify-center z-50">
            <div class="bg-white dark:bg-slate-800 rounded-3xl p-6 w-full max-w-md">
                <h3 class="text-xl font-bold mb-4" id="category-modal-title">${t('categories.add')}</h3>
                <form id="category-form" class="space-y-4">
                    <input type="hidden" id="category-id">
                    <div>
                        <label class="block text-sm font-medium mb-1" for="category-name">${t('common.name')}</label>
                        <input type="text" id="category-name" required class="w-full px-3 py-2 border rounded-2xl">
                    </div>
                    <div>
                        <label class="block text-sm font-medium mb-1" for="category-parent">${t('categories.parent')}</label>
                        <select id="category-parent" class="w-full px-3 py-2 border rounded-2xl"></select>
                    </div>
                    <div id="category-nature-wrapper">
                        <label class="block text-sm font-medium mb-1" for="category-nature">${t('categories.nature')}</label>
                        <select id="category-nature" class="w-full px-3 py-2 border rounded-2xl">
                            <option value="VARIABLE">${t('categories.nature-variable')}</option>
                            <option value="FIXED">${t('categories.nature-fixed')}</option>
                        </select>
                    </div>
                    <div id="category-section-wrapper" style="display: none;">
                        <label class="block text-sm font-medium mb-1" for="category-section">${t('categories.section')}</label>
                        <select id="category-section" class="w-full px-3 py-2 border rounded-2xl">
                            <option value="AUTO">${t('categories.section-auto')}</option>
                            <option value="FIXED">${sectionLabel('FIXED')}</option>
                            <option value="VARIABLE">${sectionLabel('VARIABLE')}</option>
                            <option value="SAVINGS">${t('categories.section-savings-option')}</option>
                            <option value="INCOME">${t('categories.section-income-option')}</option>
                        </select>
                    </div>
                    <p id="category-hint" class="text-xs text-gray-500 dark:text-slate-400"></p>
                    <div class="flex gap-2 justify-end">
                        <button type="button" id="category-cancel-btn" class="px-4 py-2 border rounded-2xl hover:bg-gray-50 dark:hover:bg-slate-700">${t('common.cancel')}</button>
                        <button type="submit" class="bg-primary text-white px-4 py-2 rounded-2xl hover:bg-primary/90">${t('common.save')}</button>
                    </div>
                </form>
            </div>
        </div>
    `;

    await loadCategories();

    document.getElementById('add-category-btn').addEventListener('click', () => openModal());
    document.getElementById('category-cancel-btn').addEventListener('click', closeModal);
    document.getElementById('category-form').addEventListener('submit', handleSubmit);
    document.getElementById('categories-list').addEventListener('click', handleListClick);
    document.getElementById('category-modal').addEventListener('click', (event) => {
        if (event.target.id === 'category-modal') closeModal();
    });
}

async function loadCategories() {
    try {
        categories = await getCategories();
        renderTree();
    } catch (error) {
        console.error('Error loading categories:', error);
        document.getElementById('categories-list').innerHTML =
            `<p class="text-red-600">${t('categories.load-failed')}</p>`;
    }
}

const childrenOf = (id) => categories.filter(category => category.parent_id === id);

/** Seccions que no diuen com es mesura una fulla, només on va el bloc. */
const SECTION_ONLY = new Set(['INCOME', 'SAVINGS']);
const isGroup = (category) => childrenOf(category.id).length > 0;

function renderTree() {
    const container = document.getElementById('categories-list');

    if (categories.length === 0) {
        container.innerHTML = `<p class="text-gray-500 dark:text-slate-400 text-center py-8">${t('categories.empty')}</p>`;
        return;
    }

    const roots = categories.filter(category => !category.parent_id);
    const groups = roots.filter(isGroup);
    const loose = roots.filter(category => !isGroup(category));

    const cards = groups.map(group => `
        <div class="bg-white dark:bg-slate-800 rounded-3xl p-4 shadow-xs border border-slate-200 dark:border-slate-700">
            ${renderRow(group, true)}
            ${renderChildren(group, new Set([group.id]))}
        </div>
    `);

    if (loose.length > 0) {
        cards.push(`
            <div class="bg-white dark:bg-slate-800 rounded-3xl p-4 shadow-xs border border-slate-200 dark:border-slate-700">
                <h3 class="font-semibold text-gray-500 dark:text-slate-400 text-sm mb-3">${t('categories.no-group')}</h3>
                <div class="space-y-2">
                    ${loose.map(category => renderRow(category, false)).join('')}
                </div>
            </div>
        `);
    }

    container.innerHTML = cards.join('');
}

/**
 * Els fills d'un grup, i els dels seus grups, sagnats. Inversió té Fons
 * d'inversió a dins, i Fons d'inversió, Inversions: amb un sol nivell,
 * Inversions no sortia enlloc.
 */
function renderChildren(group, visited) {
    return `
        <div class="mt-3 pl-4 border-l-2 border-slate-200 dark:border-slate-700 space-y-2">
            ${childrenOf(group.id).map(child => {
                // Un parent_id mal informat podria fer un cicle i penjar la pàgina.
                if (visited.has(child.id)) return '';
                visited.add(child.id);
                return renderRow(child, false) + (isGroup(child) ? renderChildren(child, visited) : '');
            }).join('')}
        </div>`;
}

function renderRow(category, asGroupHeader) {
    const group = isGroup(category);
    // Els grups no tenen naturalesa pròpia: poden barrejar fixos i variables.
    // Un bloc d'ingressos sense subcategories tampoc: el que diu el seu camp és
    // la secció, i "variable" hauria estat mentida.
    const badge = group
        ? { text: t('categories.badge-group'), cls: BADGE_CLASSES.group }
        : SECTION_ONLY.has(category.tipus_cost)
            ? null
            : category.tipus_cost === 'FIXED'
                ? { text: t('categories.badge-fixed'), cls: BADGE_CLASSES.fixed }
                : { text: t('categories.badge-variable'), cls: BADGE_CLASSES.variable };

    return `
        <div class="flex items-center justify-between ${asGroupHeader ? '' : 'text-sm'}">
            <span class="flex items-center gap-2 flex-wrap">
                <span class="${asGroupHeader ? 'font-semibold text-lg' : ''}">${escapeHtml(category.nom)}</span>
                ${badge ? `<span class="text-xs px-2 py-0.5 rounded-full ${badge.cls}">${badge.text}</span>` : ''}
                ${sectionBadge(category, group)}
                ${group ? `<span class="text-xs text-gray-500 dark:text-slate-400">${escapeHtml(t('categories.subcategory-count', { count: childrenOf(category.id).length }))}</span>` : ''}
            </span>
            <span class="flex gap-1">
                <button data-action="edit" data-id="${category.id}" class="p-1 hover:bg-gray-100 dark:hover:bg-slate-700 rounded-lg" title="${t('common.edit')}">
                    <span class="material-symbols-outlined text-sm">edit</span>
                </button>
                <button data-action="delete" data-id="${category.id}" class="p-1 hover:bg-red-50 text-red-600 rounded-lg" title="${t('common.delete')}">
                    <span class="material-symbols-outlined text-sm">delete</span>
                </button>
            </span>
        </div>
    `;
}

/**
 * A quina secció del repartiment va un bloc, quan ell mateix ho declara.
 *
 * Només als blocs de primer nivell amb subcategories: és l'únic lloc on el
 * camp vol dir "secció" i no "com es mesura". Si no ho declaren, no s'ensenya
 * res, perquè la secció surt de les fulles i ja es veu en cadascuna.
 */
function sectionBadge(category, group) {
    if (category.parent_id || !category.tipus_cost) return '';
    // Una fulla de primer nivell només en porta si és d'ingressos:
    // si és fixa o variable, ja ho diu la seva pastilla.
    if (!group && !SECTION_ONLY.has(category.tipus_cost)) return '';

    if (category.tipus_cost === 'FIXED') {
        return `<span class="text-xs px-2 py-0.5 rounded-full bg-blue-100 text-blue-700">${t('categories.in-fixed')}</span>`;
    }
    if (category.tipus_cost === 'INCOME') {
        return `<span class="text-xs px-2 py-0.5 rounded-full bg-emerald-100 text-emerald-700">${t('categories.in-income')}</span>`;
    }
    if (category.tipus_cost === 'SAVINGS') {
        return `<span class="text-xs px-2 py-0.5 rounded-full bg-teal-100 text-teal-700">${t('categories.in-savings')}</span>`;
    }
    return `<span class="text-xs px-2 py-0.5 rounded-full bg-violet-100 text-violet-700">${t('categories.in-variable')}</span>`;
}

/**
 * Opcions de grup per al desplegable.
 *
 * S'exclou la mateixa categoria i tots els seus descendents: posar-se un
 * descendent com a grup deixaria una branca desconnectada de l'arrel. El
 * backend també ho rebutja, però val més no oferir-ho.
 */
function parentOptions(editingId) {
    const excluded = new Set();
    if (editingId) {
        const pending = [editingId];
        while (pending.length > 0) {
            const current = pending.pop();
            if (excluded.has(current)) continue;
            excluded.add(current);
            childrenOf(current).forEach(child => pending.push(child.id));
        }
    }

    // Qualsevol categoria pot fer de grup: en penjar-li un fill, passa a
    // ser-ho automàticament.
    return `<option value="">${t('categories.no-group-option')}</option>`
        + categoryOptions(categories, { groups: true, exclude: excluded });
}

function openModal(category = null) {
    const modal = document.getElementById('category-modal');
    const form = document.getElementById('category-form');
    const natureWrapper = document.getElementById('category-nature-wrapper');
    const sectionWrapper = document.getElementById('category-section-wrapper');
    const hint = document.getElementById('category-hint');

    form.reset();
    document.getElementById('category-modal-title').textContent =
        t(category ? 'categories.edit-title' : 'categories.add');
    document.getElementById('category-parent').innerHTML = parentOptions(category?.id);

    if (category) {
        document.getElementById('category-id').value = category.id;
        document.getElementById('category-name').value = category.nom;
        document.getElementById('category-parent').value = category.parent_id || '';
        document.getElementById('category-nature').value = category.tipus_cost || 'VARIABLE';
        document.getElementById('category-section').value = category.tipus_cost || 'AUTO';

        // A un bloc de primer nivell, el camp no diu com es mesura —això ho diu
        // cada subcategoria— sinó a quina secció del repartiment va. També si
        // no té subcategories: amb el desplegable de naturalesa, que només té
        // fix i variable, editar un bloc d'ingressos sense fulles li treia la
        // secció.
        const group = isGroup(category);
        const root = !category.parent_id;
        natureWrapper.style.display = root || group ? 'none' : '';
        sectionWrapper.style.display = root ? '' : 'none';
        hint.textContent = group
            ? t(category.parent_id ? 'categories.hint-group' : 'categories.hint-block')
            : root
                ? t('categories.hint-root')
                : '';
    } else {
        document.getElementById('category-id').value = '';
        natureWrapper.style.display = '';
        sectionWrapper.style.display = 'none';
        hint.textContent = t('categories.hint-new');
    }

    modal.classList.remove('hidden');
    modal.classList.add('flex');
}

function closeModal() {
    const modal = document.getElementById('category-modal');
    modal.classList.add('hidden');
    modal.classList.remove('flex');
}

async function handleListClick(event) {
    const button = event.target.closest('button[data-action]');
    if (!button) return;

    const id = Number.parseInt(button.dataset.id, 10);
    if (!Number.isInteger(id)) return;

    const category = categories.find(candidate => candidate.id === id);
    if (!category) return;

    if (button.dataset.action === 'edit') {
        openModal(category);
        return;
    }

    if (button.dataset.action === 'delete') {
        if (!confirm(t('categories.delete-confirm', { name: category.nom }))) return;
        try {
            await deleteCategory(id);
            await loadCategories();
        } catch (error) {
            // El backend explica per què no es pot: moviments o subcategories.
            alert(error.message || t('categories.delete-failed'));
        }
    }
}

async function handleSubmit(event) {
    event.preventDefault();

    const id = document.getElementById('category-id').value;
    const parentValue = document.getElementById('category-parent').value;
    const existing = id ? categories.find(candidate => candidate.id === Number.parseInt(id, 10)) : null;
    const group = existing ? isGroup(existing) : false;

    const data = { nom: document.getElementById('category-name').value };

    if (id) {
        // En una actualització, un camp absent no es toca. Per treure una
        // categoria del seu grup cal enviar un valor negatiu explícit: és el
        // conveni que fa servir l'API per distingir-ho de "no enviat".
        data.parent_id = parentValue ? Number.parseInt(parentValue, 10) : -1;
    } else if (parentValue) {
        data.parent_id = Number.parseInt(parentValue, 10);
    }

    // El mateix camp, dues preguntes: a una fulla, com es mesura; a un bloc de
    // primer nivell, a quina secció del repartiment va. "AUTO" el buida, per
    // tornar a deduir-la de les subcategories.
    if (existing && !existing.parent_id) {
        // Si la penja d'un grup, la secció ja no vol dir res: es queda com estava.
        if (!parentValue) data.tipus_cost = document.getElementById('category-section').value;
    } else if (!group) {
        data.tipus_cost = document.getElementById('category-nature').value;
    }

    try {
        if (id) {
            await updateCategory(Number.parseInt(id, 10), data);
        } else {
            await createCategory(data);
        }
        closeModal();
        await loadCategories();
    } catch (error) {
        alert(error.message || t('categories.save-failed'));
    }
}
