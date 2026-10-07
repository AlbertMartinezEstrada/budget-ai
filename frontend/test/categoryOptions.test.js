import test from 'node:test';
import assert from 'node:assert/strict';

// categoryOptions.js fa servir escapeHtml d'api.js, que llegeix window en
// carregar-se: cal el mateix mínim de navegador que a api.test.js.
global.window = {
    location: { protocol: 'http:', hostname: 'localhost' },
    matchMedia: () => ({ matches: false })
};
global.document = {
    documentElement: { classList: { add() {}, remove() {} } },
    dispatchEvent() {}
};

let module;

test.before(async () => {
    module = await import('../public/js/categoryOptions.js');
});

let nextId = 1;
const category = (nom, tipus_cost = null, parent = null) =>
    ({ id: nextId++, nom, tipus_cost, parent_id: parent ? parent.id : null });

// Un arbre petit amb els casos que importen, en l'ordre desendreçat en què
// arriben de la base de dades.
const gastMensual = category('Gast mensual', 'VARIABLE');
const llar = category('Llar', 'FIXED');
const ingressos = category('Ingressos', 'INCOME');
const subscripcions = category('Subscripcions', 'FIXED');
const deutes = category('Deutes i préstecs', 'FIXED');
// Sense secció declarada: es dedueix de les fulles, com al pressupost.
const cotxe = category('Cotxe');
const oci = category('Oci');
// Un bloc sense subcategories: és alhora la fulla.
const tradeRepublic = category('Trade Republic', 'VARIABLE');
const altres = category('Altres');

const CATEGORIES = [
    gastMensual, llar, ingressos, subscripcions, deutes, cotxe, oci, tradeRepublic, altres,
    category('Bars i restaurants', 'VARIABLE', gastMensual),
    category('Menjar i supermercat', 'VARIABLE', gastMensual),
    category('Llum', 'VARIABLE', llar),
    category('Casa', 'FIXED', llar),
    category('Nòmina', null, ingressos),
    category('iCloud', 'FIXED', subscripcions),
    category('Claude', 'FIXED', subscripcions),
    category('Pagament de deutes', 'FIXED', deutes),
    category('Assegurança', 'FIXED', cotxe),
    category('Gasolina', 'FIXED', cotxe),
    category('Cinema', 'VARIABLE', oci),
    category('Concerts', 'FIXED', oci)
];

const byName = (nom) => CATEGORIES.find(candidate => candidate.nom === nom);

/** L'HTML de les opcions convertit en grups, per poder-ho comparar. */
function parse(html) {
    const groups = [];
    for (const [, label, body] of html.matchAll(/<optgroup label="([^"]*)">(.*?)<\/optgroup>/g)) {
        const options = [...body.matchAll(/<option value="([^"]*)"( selected)?>([^<]*)<\/option>/g)]
            .map(([, value, selected, text]) => ({ value, text, selected: Boolean(selected) }));
        groups.push({ label, options });
    }
    return groups;
}

const labels = (html) => parse(html).map(group => group.label);
const texts = (html, label) => parse(html).find(group => group.label === label).options.map(option => option.text);

test('les categories surten per seccions, en l\'ordre del pressupost, i un grup per bloc', () => {
    assert.deepEqual(labels(module.categoryOptions(CATEGORIES)), [
        'Gastos fijos · Cotxe',
        'Gastos fijos · Deutes i préstecs',
        'Gastos fijos · Llar',
        'Gastos fijos · Subscripcions',
        'Gastos variables · Altres',
        'Gastos variables · Gast mensual',
        'Gastos variables · Oci',
        'Gastos variables · Trade Republic',
        'Ingresos · Ingressos'
    ]);
});

test('un bloc sense subcategories surt com a bloc i es pot triar', () => {
    const html = module.categoryOptions(CATEGORIES);
    assert.deepEqual(texts(html, 'Gastos variables · Trade Republic'), ['Trade Republic']);
});

test('l\'estalvi va entre els fixos i els variables, com al pressupost', () => {
    const estalvis = category('Estalvis', 'SAVINGS');
    const withSavings = [...CATEGORIES, estalvis, category('Colxó', null, estalvis)];

    const sections = labels(module.categoryOptions(withSavings)).map(label => label.split(' · ')[0]);
    const firstSavings = sections.indexOf('Ahorro');
    assert.ok(firstSavings > sections.lastIndexOf('Gastos fijos'));
    assert.ok(firstSavings < sections.indexOf('Gastos variables'));
    // I compta com a despesa: un recurrent o una quota hi poden anar.
    assert.ok(labels(module.categoryOptions(withSavings, { sections: module.EXPENSE_SECTIONS }))
        .includes('Ahorro · Estalvis'));
    assert.equal(module.sectionOfCategory(withSavings, estalvis.id), 'SAVINGS');
});

test('dins de cada bloc, les fulles van per ordre alfabètic', () => {
    const html = module.categoryOptions(CATEGORIES);
    assert.deepEqual(texts(html, 'Gastos fijos · Llar'), ['Casa', 'Llum']);
    assert.deepEqual(texts(html, 'Gastos fijos · Subscripcions'), ['Claude', 'iCloud']);
});

test('un bloc que no declara secció va a fixos només si totes les seves fulles ho són', () => {
    // Igual que BudgetService.sectionOf: si no, el desplegable diria una
    // secció i el pressupost en comptaria una altra.
    const sections = labels(module.categoryOptions(CATEGORIES));
    assert.ok(sections.includes('Gastos fijos · Cotxe'));
    assert.ok(sections.includes('Gastos variables · Oci'));
});

test('per defecte només s\'ofereixen fulles: un moviment en un bloc comptaria dues vegades', () => {
    const values = parse(module.categoryOptions(CATEGORIES))
        .flatMap(group => group.options.map(option => Number(option.value)));
    for (const block of [gastMensual, llar, ingressos, subscripcions, deutes, cotxe, oci]) {
        assert.ok(!values.includes(block.id), `${block.nom} no s'hauria d'oferir`);
    }
    assert.ok(values.includes(tradeRepublic.id), 'un bloc sense subcategories sí que es pot triar');
});

test('on es pot triar un bloc, surt el primer del seu grup', () => {
    const html = module.categoryOptions(CATEGORIES, { groups: true });
    assert.deepEqual(texts(html, 'Gastos fijos · Llar'), ['Llar (bloque)', 'Casa', 'Llum']);
});

test('un grup dins d\'un bloc porta el camí, perquè no es confongui', () => {
    const vehicle = category('Vehicle', 'FIXED');
    const moto = category('Moto', null, vehicle);
    const nested = [vehicle, moto, category('Assegurança moto', 'FIXED', moto)];

    assert.deepEqual(texts(module.categoryOptions(nested), 'Gastos fijos · Vehicle'),
        ['Moto › Assegurança moto']);
    assert.deepEqual(texts(module.categoryOptions(nested, { groups: true }), 'Gastos fijos · Vehicle'),
        ['Vehicle (bloque)', 'Moto (grupo)', 'Moto › Assegurança moto']);
});

test('es pot fer servir el nom com a valor i marcar-ne un de seleccionat', () => {
    const options = parse(module.categoryOptions(CATEGORIES, {
        value: (candidate) => candidate.nom,
        selected: 'Llum'
    })).flatMap(group => group.options);

    assert.deepEqual(options.filter(option => option.selected).map(option => option.value), ['Llum']);
});

test('es poden demanar només algunes seccions, i en un altre ordre', () => {
    assert.deepEqual(labels(module.categoryOptions(CATEGORIES, { sections: module.INCOME_SECTIONS })),
        ['Ingresos · Ingressos']);
    assert.ok(!labels(module.categoryOptions(CATEGORIES, { sections: module.EXPENSE_SECTIONS }))
        .some(label => label.startsWith('Ingresos')));
});

test('un bloc que es queda sense cap fulla per triar no surt', () => {
    const html = module.categoryOptions(CATEGORIES, { only: (leaf) => leaf.tipus_cost === 'FIXED' });
    assert.deepEqual(texts(html, 'Gastos fijos · Llar'), ['Casa']);
    assert.ok(!labels(html).includes('Gastos variables · Gast mensual'));
});

test('els exclosos no surten ni com a fulla ni com a grup', () => {
    const html = module.categoryOptions(CATEGORIES, {
        groups: true,
        exclude: new Set([llar.id, byName('Casa').id])
    });
    assert.deepEqual(texts(html, 'Gastos fijos · Llar'), ['Llum']);
});

test('els noms s\'escapen: venen del que escriu l\'usuari', () => {
    const odd = category('O\'Brien <b>', 'VARIABLE');
    const html = module.categoryOptions([odd], { value: (candidate) => candidate.nom });
    assert.ok(!html.includes('<b>'));
    assert.ok(html.includes('value="O&#39;Brien &lt;b&gt;"'));
});

test('la secció d\'una categoria és la del bloc d\'on penja', () => {
    assert.equal(module.sectionOfCategory(CATEGORIES, byName('Llum').id), 'FIXED');
    assert.equal(module.sectionOfCategory(CATEGORIES, byName('Nòmina').id), 'INCOME');
    assert.equal(module.sectionOfCategory(CATEGORIES, byName('Cinema').id), 'VARIABLE');
    assert.equal(module.sectionOfCategory(CATEGORIES, tradeRepublic.id), 'VARIABLE');
    assert.equal(module.sectionOfCategory(CATEGORIES, 99999), null);
});

test('sense categories no peta', () => {
    assert.equal(module.categoryOptions([]), '');
    assert.equal(module.categoryOptions(undefined), '');
});
