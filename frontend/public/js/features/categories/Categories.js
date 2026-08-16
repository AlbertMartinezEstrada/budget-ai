import {
    getCategories, createCategory, updateCategory, deleteCategory, escapeHtml
} from '../../api.js';

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
                <h2 class="text-2xl font-bold text-slate-800 dark:text-slate-100">Categorías</h2>
                <p class="text-sm text-gray-500 dark:text-slate-400 mt-1">
                    Agrupa las categorías y marca cuáles son gastos fijos.
                </p>
            </div>
            <button id="add-category-btn" class="bg-primary text-white px-4 py-2 rounded-lg hover:bg-primary/90 flex items-center gap-2">
                <span class="material-symbols-outlined">add</span>
                Nueva categoría
            </button>
        </div>

        <div class="bg-white dark:bg-slate-800 rounded-xl p-4 shadow-sm border border-slate-200 dark:border-slate-700 mb-6 text-sm text-gray-600 dark:text-slate-300">
            <p><strong>Grupo</strong>: agrupa subcategorías y suma sus gastos. No recibe movimientos.</p>
            <p><strong>Fijo</strong>: gasto recurrente. En «coste de vida» cuenta prorrateado mes a mes.</p>
            <p><strong>Variable</strong>: cuenta por el gasto real de cada mes.</p>
        </div>

        <div id="categories-list" class="space-y-4"></div>

        <!-- Modal -->
        <div id="category-modal" class="fixed inset-0 bg-black/50 hidden items-center justify-center z-50">
            <div class="bg-white dark:bg-slate-800 rounded-xl p-6 w-full max-w-md">
                <h3 class="text-xl font-bold mb-4" id="category-modal-title">Nueva categoría</h3>
                <form id="category-form" class="space-y-4">
                    <input type="hidden" id="category-id">
                    <div>
                        <label class="block text-sm font-medium mb-1" for="category-name">Nombre</label>
                        <input type="text" id="category-name" required class="w-full px-3 py-2 border rounded-lg">
                    </div>
                    <div>
                        <label class="block text-sm font-medium mb-1" for="category-parent">Grupo</label>
                        <select id="category-parent" class="w-full px-3 py-2 border rounded-lg"></select>
                    </div>
                    <div id="category-nature-wrapper">
                        <label class="block text-sm font-medium mb-1" for="category-nature">Naturaleza</label>
                        <select id="category-nature" class="w-full px-3 py-2 border rounded-lg">
                            <option value="VARIABLE">Variable — se mide por el gasto del mes</option>
                            <option value="FIXED">Fijo — se prorratea en coste de vida</option>
                        </select>
                    </div>
                    <div id="category-section-wrapper" style="display: none;">
                        <label class="block text-sm font-medium mb-1" for="category-section">Sección del reparto</label>
                        <select id="category-section" class="w-full px-3 py-2 border rounded-lg">
                            <option value="AUTO">Deducir de las subcategorías</option>
                            <option value="FIXED">Gastos fijos</option>
                            <option value="VARIABLE">Gastos variables</option>
                            <option value="INCOME">Ingresos — dinero que entra, no se reparte</option>
                        </select>
                    </div>
                    <p id="category-hint" class="text-xs text-gray-500 dark:text-slate-400"></p>
                    <div class="flex gap-2 justify-end">
                        <button type="button" id="category-cancel-btn" class="px-4 py-2 border rounded-lg hover:bg-gray-50 dark:hover:bg-slate-700">Cancelar</button>
                        <button type="submit" class="bg-primary text-white px-4 py-2 rounded-lg hover:bg-primary/90">Guardar</button>
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
    document.getElementById('category-modal').addEventListener('click', (e) => {
        if (e.target.id === 'category-modal') closeModal();
    });
}

async function loadCategories() {
    try {
        categories = await getCategories();
        renderTree();
    } catch (error) {
        console.error('Error loading categories:', error);
        document.getElementById('categories-list').innerHTML =
            '<p class="text-red-500">Error al cargar categorías</p>';
    }
}

const childrenOf = (id) => categories.filter(c => c.parent_id === id);
const isGroup = (category) => childrenOf(category.id).length > 0;

function renderTree() {
    const container = document.getElementById('categories-list');

    if (categories.length === 0) {
        container.innerHTML = '<p class="text-gray-500 dark:text-slate-400 text-center py-8">No hay categorías</p>';
        return;
    }

    const roots = categories.filter(c => !c.parent_id);
    const groups = roots.filter(isGroup);
    const loose = roots.filter(c => !isGroup(c));

    const cards = groups.map(group => `
        <div class="bg-white dark:bg-slate-800 rounded-xl p-4 shadow-sm border border-slate-200 dark:border-slate-700">
            ${renderRow(group, true)}
            <div class="mt-3 pl-4 border-l-2 border-slate-200 dark:border-slate-700 space-y-2">
                ${childrenOf(group.id).map(child => renderRow(child, false)).join('')}
            </div>
        </div>
    `);

    if (loose.length > 0) {
        cards.push(`
            <div class="bg-white dark:bg-slate-800 rounded-xl p-4 shadow-sm border border-slate-200 dark:border-slate-700">
                <h3 class="font-semibold text-gray-500 dark:text-slate-400 text-sm mb-3">Sin grupo</h3>
                <div class="space-y-2">
                    ${loose.map(category => renderRow(category, false)).join('')}
                </div>
            </div>
        `);
    }

    container.innerHTML = cards.join('');
}

function renderRow(category, asGroupHeader) {
    const group = isGroup(category);
    // Els grups no tenen naturalesa pròpia: poden barrejar fixos i variables.
    const badge = group
        ? { text: 'grupo', cls: BADGE_CLASSES.group }
        : category.tipus_cost === 'FIXED'
            ? { text: 'fijo', cls: BADGE_CLASSES.fixed }
            : { text: 'variable', cls: BADGE_CLASSES.variable };

    return `
        <div class="flex items-center justify-between ${asGroupHeader ? '' : 'text-sm'}">
            <span class="flex items-center gap-2 flex-wrap">
                <span class="${asGroupHeader ? 'font-semibold text-lg' : ''}">${escapeHtml(category.nom)}</span>
                <span class="text-xs px-2 py-0.5 rounded-full ${badge.cls}">${badge.text}</span>
                ${sectionBadge(category, group)}
                ${group ? `<span class="text-xs text-gray-500 dark:text-slate-400">${childrenOf(category.id).length} subcategorías</span>` : ''}
            </span>
            <span class="flex gap-1">
                <button data-action="edit" data-id="${category.id}" class="p-1 hover:bg-gray-100 dark:hover:bg-slate-700 rounded" title="Editar">
                    <span class="material-symbols-outlined text-sm">edit</span>
                </button>
                <button data-action="delete" data-id="${category.id}" class="p-1 hover:bg-red-50 text-red-500 rounded" title="Eliminar">
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
    if (!group || category.parent_id || !category.tipus_cost) return '';

    if (category.tipus_cost === 'FIXED') {
        return '<span class="text-xs px-2 py-0.5 rounded-full bg-blue-100 text-blue-700">en gastos fijos</span>';
    }
    if (category.tipus_cost === 'INCOME') {
        return '<span class="text-xs px-2 py-0.5 rounded-full bg-emerald-100 text-emerald-700">en ingresos</span>';
    }
    return '<span class="text-xs px-2 py-0.5 rounded-full bg-violet-100 text-violet-700">en gastos variables</span>';
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
    const options = ['<option value="">— Sin grupo —</option>'];
    for (const category of categories) {
        if (excluded.has(category.id)) continue;
        options.push(`<option value="${category.id}">${escapeHtml(category.nom)}</option>`);
    }
    return options.join('');
}

function openModal(category = null) {
    const modal = document.getElementById('category-modal');
    const form = document.getElementById('category-form');
    const natureWrapper = document.getElementById('category-nature-wrapper');
    const sectionWrapper = document.getElementById('category-section-wrapper');
    const hint = document.getElementById('category-hint');

    form.reset();
    document.getElementById('category-modal-title').textContent =
        category ? 'Editar categoría' : 'Nueva categoría';
    document.getElementById('category-parent').innerHTML = parentOptions(category?.id);

    if (category) {
        document.getElementById('category-id').value = category.id;
        document.getElementById('category-name').value = category.nom;
        document.getElementById('category-parent').value = category.parent_id || '';
        document.getElementById('category-nature').value = category.tipus_cost || 'VARIABLE';
        document.getElementById('category-section').value = category.tipus_cost || 'AUTO';

        // A un grup, el camp no diu com es mesura —això ho diu cada
        // subcategoria— sinó a quina secció del repartiment va el bloc sencer.
        const group = isGroup(category);
        natureWrapper.style.display = group ? 'none' : '';
        sectionWrapper.style.display = group && !category.parent_id ? '' : 'none';
        hint.textContent = group
            ? (category.parent_id
                ? 'Es un grupo: su coste sale de sumar sus subcategorías.'
                : 'Un bloque puede ser fijo y tener dentro subcategorías variables: el alquiler no se mueve, la luz sí.')
            : '';
    } else {
        document.getElementById('category-id').value = '';
        natureWrapper.style.display = '';
        sectionWrapper.style.display = 'none';
        hint.textContent = 'Si le cuelgas subcategorías, pasará a ser un grupo.';
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

    const category = categories.find(c => c.id === id);
    if (!category) return;

    if (button.dataset.action === 'edit') {
        openModal(category);
        return;
    }

    if (button.dataset.action === 'delete') {
        if (!confirm(`¿Eliminar «${category.nom}»?`)) return;
        try {
            await deleteCategory(id);
            await loadCategories();
        } catch (error) {
            // El backend explica per què no es pot: moviments o subcategories.
            alert(error.message || 'Error al eliminar la categoría');
        }
    }
}

async function handleSubmit(event) {
    event.preventDefault();

    const id = document.getElementById('category-id').value;
    const parentValue = document.getElementById('category-parent').value;
    const existing = id ? categories.find(c => c.id === Number.parseInt(id, 10)) : null;
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
    if (!group) {
        data.tipus_cost = document.getElementById('category-nature').value;
    } else if (existing && !existing.parent_id) {
        data.tipus_cost = document.getElementById('category-section').value;
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
        alert(error.message || 'Error al guardar la categoría');
    }
}
