import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

// decisions.js fa servir formatCurrency d'api.js, que llegeix window en carregar-se.
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
    module = await import('../public/js/decisions.js');
    // Els textos es comproven en castellà, l'idioma en què es va escriure la vista.
    const i18n = await import('../public/js/i18n.js');
    const spanish = fs.readFileSync(new URL('../public/lang/Language_es.properties', import.meta.url), 'utf8');
    i18n.setMessages('es', i18n.parseProperties(spanish));
});

const TODAY = new Date(2026, 9, 14);

const delivery = { categoria_id: 2, nom: 'Delivery', pla: '60', mitjana_real: '70', te_pla_propi: true, percentatge: null, base: '1200' };
const groceries = { categoria_id: 4, nom: 'Supermercat', pla: '300', mitjana_real: '290', te_pla_propi: true, percentatge: '25', base: '1200' };
const coffee = { categoria_id: 5, nom: 'Cafè', pla: '0', mitjana_real: '40', te_pla_propi: false, percentatge: null, base: null };

/** Intl separa l'import del símbol amb un espai que no es trenca; aquí és un espai. */
function equalText(actual, expected) {
    assert.equal(String(actual).replace(/[\u00a0\u202f]/g, ' '), expected);
}

test('retallar una partida allibera diners per estalviar; pujar-la en treu', () => {
    const result = module.simulate([delivery, groceries, coffee], { 2: '10', 4: '320' });
    assert.deepEqual(result.rows.map(row => row.diferencia), [-50, 20, 0]);
    assert.equal(result.extraMonthly, 30);
    assert.equal(result.extraYearly, 360);
    // Sense pla, la partida comença pel que s'hi gasta.
    assert.equal(result.rows[2].abans, 40);
    // Un camp buit o mal escrit no canvia res, i no hi ha imports negatius.
    assert.equal(module.simulate([delivery], { 2: '' }).extraMonthly, 0);
    assert.equal(module.simulate([delivery], { 2: 'abc' }).extraMonthly, 0);
    assert.equal(module.simulate([delivery], { 2: '-20' }).extraMonthly, 60);
});

test('quants mesos falten per a una meta, i quan s\'hi arriba', () => {
    assert.equal(module.monthsToGoal(1800, 250), 8);
    assert.equal(module.monthsToGoal(0, 250), 0);
    assert.equal(module.monthsToGoal(1800, 0), null);
    assert.equal(module.monthsToGoal(1800, -50), null);

    const goal = { nom: 'Viatge', falta: '1800', data_objectiu: '2027-06-01' };
    assert.deepEqual(module.goalProjection(goal, 250, TODAY), { months: 8, period: '2027-06', onTime: true });
    assert.deepEqual(module.goalProjection(goal, 200, TODAY), { months: 9, period: '2027-07', onTime: false });
    assert.deepEqual(module.goalProjection({ falta: '100' }, 0, TODAY), { months: null, period: null, onTime: null });
});

test('amb la simulació, la meta arriba abans', () => {
    const goal = { nom: 'Viatge', falta: '1800', data_objectiu: '2027-06-01' };
    const comparison = module.goalComparison(goal, 200, 100, TODAY);
    assert.equal(comparison.now.months, 9);
    assert.equal(comparison.simulated.months, 6);
    assert.equal(comparison.monthsSooner, 3);
    assert.equal(comparison.simulated.onTime, true);
    assert.equal(module.projectionText(comparison.simulated), 'llegas en abril de 2027 (6 meses)');
    assert.equal(module.projectionText({ months: 1, period: '2026-11' }), 'llegas en noviembre de 2026 (1 mes)');
    assert.equal(module.projectionText({ months: null }), 'no llegas: ahorrando esto no se acaba nunca');
    assert.equal(module.projectionText({ months: 0 }), 'ya la tienes');
});

test('aplicar la simulació envia només el que canvia; un percentatge es queda percentatge', () => {
    assert.deepEqual(module.simulationChanges([delivery, groceries, coffee], { 2: '40', 4: '300', 5: '30' }), [
        { categoria_id: 2, import: 40 },
        { categoria_id: 5, import: 30 }
    ]);
    // 330 de 1200: 27.5 %.
    assert.deepEqual(module.simulationChanges([groceries], { 4: '330' }), [{ categoria_id: 4, percentatge: 27.5 }]);
});

test('els recurrents per blocs, el que més costa primer', () => {
    const groups = module.recurringGroups([
        { nom: 'Netflix', bloc: 'Subscripcions', anual: '155.88' },
        { nom: 'Lloguer', bloc: 'Llar', anual: '6640' },
        { nom: 'iCloud', bloc: 'Subscripcions', anual: '35.88' },
        { nom: 'Altre', bloc: null, anual: '12' }
    ]);
    assert.deepEqual(groups.map(group => [group.bloc, group.anual, group.partides.length]),
        [['Llar', 6640, 1], ['Subscripcions', 191.76, 2], ['Sin categoría', 12, 1]]);
    assert.equal(module.frequencyLabel('TRIMESTRAL'), 'cada 3 meses');
    assert.equal(module.frequencyLabel('mensual'), 'al mes');
    assert.equal(module.frequencyLabel(null), '');
});

test('el que puja, en una frase', () => {
    equalText(module.trendText({ abans: '42.33', ultim: '95', increment_percent: 124 }),
        'de 42,33 € a 95,00 € al mes (+124 %)');
    equalText(module.trendText({ abans: '0', ultim: '30', increment_percent: null }), 'de 0,00 € a 30,00 € al mes');
    assert.equal(module.monthName('2027-03'), 'marzo de 2027');
    assert.equal(module.monthsAfter(new Date(2026, 10, 1), 2), '2027-01');
});
