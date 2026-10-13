import test from 'node:test';
import assert from 'node:assert/strict';

// budgetReview.js fa servir escapeHtml i formatCurrency d'api.js, que llegeix
// window en carregar-se.
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
    module = await import('../public/js/budgetReview.js');
});

test('les paraules del semàfor depenen de la secció: passar-se en una despesa, quedar-se curt en l\'estalvi', () => {
    assert.equal(module.statusLabel('DESVIAT', 'VARIABLE'), 'Pasado');
    assert.equal(module.statusLabel('DESVIAT', 'SAVINGS'), 'Corto');
    assert.equal(module.statusLabel('BE', 'FIXED'), 'Dentro');
    assert.equal(module.statusLabel('BE', 'INCOME'), 'Cumplido');
    assert.equal(module.statusLabel('CARREC_PUNTUAL', 'FIXED'), 'Cargo puntual');
    assert.equal(module.statusLabel('EN_CURS', 'SAVINGS'), 'En curso');
    // Sense pla ni moviments no hi ha res a jutjar.
    assert.equal(module.statusLabel(null, 'VARIABLE'), '');
    assert.equal(module.statusPill(null, 'VARIABLE'), '');
});

test('la pastilla explica els estats que no s\'entenen sols', () => {
    assert.match(module.statusPill('CARREC_PUNTUAL', 'FIXED'), /title="Es un recurrente trimestral o anual/);
    assert.match(module.statusPill('DESVIAT', 'VARIABLE'), /bg-red-100/);
});

test('una cel·la sense res és un guionet, no un punt de color', () => {
    assert.match(module.statusDot(undefined), /–/);
    assert.match(module.statusDot('DESVIAT', 'plan 100 € · real 150 €'), /bg-red-500/);
    assert.match(module.statusDot('BE', '<b>'), /title="&lt;b&gt;"/);
});

test('la desviació porta sempre el signe', () => {
    assert.equal(module.formatDeviation('40'), '+40.00 €');
    assert.equal(module.formatDeviation(-25), '−25.00 €');
    assert.equal(module.formatDeviation(0), '0.00 €');
});

test('els mesos es compten enrere sense errors al canvi d\'any', () => {
    assert.equal(module.shiftMonths('2026-10', 5), '2026-05');
    assert.equal(module.shiftMonths('2026-02', 3), '2025-11');
    assert.equal(module.shiftMonths('2026-12', -1), '2027-01');
    assert.equal(module.monthLabel('2026-10'), 'octubre 2026');
    assert.equal(module.shortMonthLabel('2026-01'), 'ene');

    const months = module.selectableMonths(new Date(2026, 1, 15), 4);
    assert.deepEqual(months, ['2026-02', '2026-01', '2025-12', '2025-11']);
});

test('el resum de l\'historial només parla dels mesos tancats amb pla', () => {
    assert.equal(module.historySummary({
        seccio: 'VARIABLE', mesos_tancats_amb_pla: 6, mesos_desviats: 4, mesos_avis: 1, desviacio_mitjana: '38.5'
    }), 'Pasado 5 de 6 meses (1 por poco) · de media +38.50 €');
    assert.equal(module.historySummary({
        seccio: 'SAVINGS', mesos_tancats_amb_pla: 2, mesos_desviats: 1, mesos_avis: 0, desviacio_mitjana: '-25'
    }), 'Corto 1 de 2 meses · de media −25.00 €');
    assert.equal(module.historySummary({
        seccio: 'FIXED', mesos_tancats_amb_pla: 3, mesos_desviats: 0, mesos_avis: 0, mesos_puntuals: 1, desviacio_mitjana: '150'
    }), 'Dentro 2 de 3 meses · 1 cargo puntual · de media +150.00 €');
    assert.equal(module.historySummary({
        seccio: 'SAVINGS', mesos_tancats_amb_pla: 4, mesos_desviats: 0, mesos_avis: 0, desviacio_mitjana: '12'
    }), 'Cumplido los 4 meses · de media +12.00 €');
    assert.equal(module.historySummary({ seccio: 'VARIABLE', mesos_tancats_amb_pla: 0 }), 'Sin meses cerrados con plan');
});

test('l\'historial va per seccions, i dins de cada una, primer el que més s\'ha desviat', () => {
    const sorted = module.sortHistory([
        { nom: 'Estalvis', seccio: 'SAVINGS', mesos_desviats: 3 },
        { nom: 'Llar', seccio: 'FIXED', mesos_desviats: 0 },
        { nom: 'Oci', seccio: 'VARIABLE', mesos_desviats: 1, mesos_avis: 0 },
        { nom: 'Gast mensual', seccio: 'VARIABLE', mesos_desviats: 1, mesos_avis: 2 },
        { nom: 'Ingressos', seccio: 'INCOME', mesos_desviats: 0 }
    ]);
    assert.deepEqual(sorted.map(block => block.nom), ['Ingressos', 'Llar', 'Gast mensual', 'Oci', 'Estalvis']);
});
