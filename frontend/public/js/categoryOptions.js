import { escapeHtml } from './api.js';

/**
 * Els desplegables de categoria, agrupats com el pressupost.
 *
 * Cada pantalla muntava el seu: unes llistaven les quaranta categories en
 * l'ordre de la base de dades, d'altres sagnaven les fulles sota el bloc, i
 * alguna oferia blocs on només hi poden anar fulles. No es trobava res, i no
 * es veia a quina part del pressupost anava a parar cada categoria.
 *
 * Ara tots surten igual: per seccions (fixos, variables, ingressos) i, dins,
 * un grup per bloc —"Gastos fijos · Llar"— amb les seves fulles per ordre
 * alfabètic. Un <select> no pot niar grups, així que la secció va al nom del
 * grup: així el desplegable continua sent el natiu, que al mòbil és el que
 * millor es fa servir.
 */

const SECTION_LABELS = {
    FIXED: 'Gastos fijos',
    VARIABLE: 'Gastos variables',
    INCOME: 'Ingresos'
};

// Les despeses primer: són la gran majoria dels moviments que es classifiquen.
export const ALL_SECTIONS = ['FIXED', 'VARIABLE', 'INCOME'];
export const EXPENSE_SECTIONS = ['FIXED', 'VARIABLE'];
export const INCOME_SECTIONS = ['INCOME'];

const DECLARED_SECTIONS = new Set(ALL_SECTIONS);

const byName = (first, second) => first.nom.localeCompare(second.nom, 'ca');

/** L'arbre de categories, amb els mateixos criteris que CategoryHierarchyService. */
function buildTree(categories) {
    const byId = new Map(categories.map(category => [category.id, category]));
    const childrenByParent = new Map();
    for (const category of categories) {
        // Un pare que ja no existeix es tracta com si la categoria fos de
        // primer nivell, igual que al backend: si no, desapareixeria.
        const parentId = category.parent_id != null && byId.has(category.parent_id)
            ? category.parent_id
            : null;
        if (!childrenByParent.has(parentId)) childrenByParent.set(parentId, []);
        childrenByParent.get(parentId).push(category);
    }
    for (const children of childrenByParent.values()) children.sort(byName);
    return {
        roots: childrenByParent.get(null) || [],
        childrenOf: (id) => childrenByParent.get(id) || []
    };
}

function leavesUnder(category, childrenOf, visited = new Set()) {
    // Un parent_id mal informat podria fer un cicle i penjar la pàgina.
    if (visited.has(category.id)) return [];
    visited.add(category.id);
    const children = childrenOf(category.id);
    if (children.length === 0) return [category];
    return children.flatMap(child => leavesUnder(child, childrenOf, visited));
}

/**
 * A quina secció del pressupost va un bloc de primer nivell.
 *
 * El mateix càlcul que BudgetService.sectionOf: la que declara el bloc o, si
 * no en declara cap, fix quan totes les seves fulles ho són. Si aquí es
 * decidís d'una altra manera, el desplegable posaria la categoria en una
 * secció i el pressupost la comptaria en una altra.
 */
function sectionOf(root, childrenOf) {
    if (DECLARED_SECTIONS.has(root.tipus_cost)) return root.tipus_cost;
    const leaves = leavesUnder(root, childrenOf);
    if (leaves.length === 0) return 'VARIABLE';
    return leaves.every(leaf => leaf.tipus_cost === 'FIXED') ? 'FIXED' : 'VARIABLE';
}

/**
 * La secció del pressupost on cau una categoria: la del bloc d'on penja.
 *
 * @returns FIXED, VARIABLE o INCOME; null si la categoria no hi és.
 */
export function sectionOfCategory(categories, categoryId) {
    const byId = new Map((categories || []).map(category => [category.id, category]));
    let current = byId.get(categoryId);
    if (!current) return null;
    const visited = new Set();
    while (current.parent_id != null && byId.has(current.parent_id) && !visited.has(current.id)) {
        visited.add(current.id);
        current = byId.get(current.parent_id);
    }
    return sectionOf(current, buildTree(categories).childrenOf);
}

/**
 * Les categories on poden anar diners: les que no tenen fills.
 *
 * Un grup existeix per agregar els seus fills, i un moviment penjat d'un grup
 * es comptaria dues vegades: el backend ho rebutja, així que val més no
 * oferir-ho.
 */
export function leafCategories(categories) {
    const parentIds = new Set((categories || []).map(category => category.parent_id).filter(Boolean));
    return (categories || []).filter(category => !parentIds.has(category.id));
}

/**
 * Les opcions d'un <select> de categories, agrupades per secció i bloc.
 *
 * @param categories la llista de GET /categories, tal com arriba.
 * @param options.value    què va a l'atribut value: l'id (per defecte) o el nom.
 * @param options.selected el value que ha de sortir seleccionat, si n'hi ha.
 * @param options.groups   si també es poden triar els blocs i els grups. Només
 *                         on té sentit: un pressupost o el pare d'una categoria.
 *                         Un moviment penjat d'un grup es comptaria dues vegades.
 * @param options.sections quines seccions surten, i en quin ordre.
 * @param options.only     quines fulles s'ofereixen (les fixes, les de despesa…).
 * @param options.exclude  ids que no s'ofereixen, ni com a fulla ni com a grup.
 * @returns l'HTML de les opcions, sense cap opció buida: si en cal una
 *          ("Totes", "Sense categoria"), la posa qui crida.
 */
export function categoryOptions(categories, {
    value = (category) => category.id,
    selected = null,
    groups = false,
    sections = ALL_SECTIONS,
    only = () => true,
    exclude = new Set()
} = {}) {
    const { roots, childrenOf } = buildTree(categories || []);
    const isGroup = (category) => childrenOf(category.id).length > 0;
    const offered = (category) => !exclude.has(category.id);

    const option = (category, label) => {
        const optionValue = String(value(category));
        const isSelected = selected != null && String(selected) === optionValue;
        return `<option value="${escapeHtml(optionValue)}"${isSelected ? ' selected' : ''}>${escapeHtml(label)}</option>`;
    };
    const optgroup = (label, options) =>
        `<optgroup label="${escapeHtml(label)}">${options.join('')}</optgroup>`;

    // Per sota del bloc, cada opció porta el camí: "Cotxe › Assegurança" no
    // es confon amb una altra "Assegurança" d'un altre grup.
    const branchOptions = (parent, prefix, visited) => {
        const options = [];
        for (const child of childrenOf(parent.id)) {
            if (visited.has(child.id)) continue;
            visited.add(child.id);
            const path = prefix ? `${prefix} › ${child.nom}` : child.nom;
            if (isGroup(child)) {
                if (groups && offered(child)) options.push(option(child, `${path} (grupo)`));
                options.push(...branchOptions(child, path, visited));
            } else if (offered(child) && only(child)) {
                options.push(option(child, path));
            }
        }
        return options;
    };

    const html = [];
    for (const section of sections) {
        for (const root of roots.filter(candidate => sectionOf(candidate, childrenOf) === section)) {
            const options = [];
            if (!isGroup(root)) {
                // Un bloc sense subcategories (Trade Republic) és alhora la
                // fulla: surt com un grup d'una sola opció, al seu lloc per
                // ordre alfabètic, igual que al pressupost.
                if (offered(root) && only(root)) options.push(option(root, root.nom));
            } else {
                if (groups && offered(root)) options.push(option(root, `${root.nom} (bloque)`));
                options.push(...branchOptions(root, '', new Set([root.id])));
            }
            // Un bloc sense res per triar només faria soroll.
            if (options.length > 0) html.push(optgroup(`${SECTION_LABELS[section]} · ${root.nom}`, options));
        }
    }
    return html.join('');
}
