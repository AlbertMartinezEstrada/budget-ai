import test from 'node:test';
import assert from 'node:assert/strict';
import { monthTotals, TOTAL_ROWS } from '../public/js/budgetTotals.js';

/**
 * Un mes com el que torna /budgets/monthly-summary: 2.000 € d'ingressos, el
 * lloguer de 800 € encara sense pagar, 450 € gastats en variables i 150 €
 * apartats. El «real» de la secció és el cost de vida: als fixos, el
 * prorrateig del lloguer encara que no s'hagi pagat.
 */
function summaryOf({ income = 2000, fixed = 0, variable = 450, saved = 150 } = {}) {
    return {
        total_disponible: income,
        seccions: [
            { tipus: 'INCOME', assignat: '2000.00', real: '1500.00', grups: [{ caixa_real: '1500.00' }] },
            { tipus: 'FIXED', assignat: '800.00', real: '800.00', grups: [{ caixa_real: String(fixed) }] },
            {
                tipus: 'VARIABLE', assignat: '600.00', real: String(variable),
                grups: [{ caixa_real: String(variable - 50) }, { caixa_real: '50.00' }]
            },
            { tipus: 'SAVINGS', assignat: '300.00', real: String(saved), grups: [{ caixa_real: String(saved) }] }
        ]
    };
}

const rowOf = (totals, key) => totals.rows.find(row => row.key === key);

test('una fila per part, i el total gastat és fixos més variables', () => {
    const totals = monthTotals(summaryOf());
    assert.deepEqual(totals.rows.map(row => row.key), TOTAL_ROWS);
    assert.deepEqual(rowOf(totals, 'SPENT'), {
        key: 'SPENT', plan: 1400, real: 450, difference: -950, tone: 'neutral', share: 22.5
    });
    assert.equal(totals.available, 2000);
});

test('el real és el que ha passat (caixa_real), no el cost de vida de la secció', () => {
    // El lloguer encara no s'ha pagat: el cost de vida diria 800 de 800.
    const totals = monthTotals(summaryOf());
    assert.equal(rowOf(totals, 'FIXED').real, 0);
    assert.equal(rowOf(totals, 'INCOME').real, 1500);
    // Els blocs de primer nivell se sumen: 400 + 50.
    assert.equal(rowOf(totals, 'VARIABLE').real, 450);
});

test('passar-se en una despesa és dolent; a l\'estalvi i als ingressos, arribar al pla és bo', () => {
    const over = monthTotals(summaryOf({ variable: 650, saved: 300 }));
    assert.equal(rowOf(over, 'VARIABLE').tone, 'bad');
    assert.equal(rowOf(over, 'VARIABLE').difference, 50);
    assert.equal(rowOf(over, 'SAVINGS').tone, 'good');
    // Els ingressos encara no hi han arribat (1.500 de 2.000): no és cap error.
    assert.equal(rowOf(over, 'INCOME').tone, 'neutral');
    assert.equal(rowOf(over, 'INCOME').share, null);
    // Sense pla no hi ha res a jutjar, com al semàfor de l'anàlisi.
    const unplanned = summaryOf();
    unplanned.seccions[1].assignat = '0';
    unplanned.seccions[1].grups = [{ caixa_real: '90.00' }];
    assert.equal(rowOf(monthTotals(unplanned), 'FIXED').tone, 'neutral');
});

test('els imports es compten en cèntims exactes', () => {
    const summary = summaryOf();
    summary.seccions[2].assignat = '0.30';
    summary.seccions[2].grups = [{ caixa_real: '0.10' }, { caixa_real: '0.20' }];
    const variable = rowOf(monthTotals(summary), 'VARIABLE');
    assert.equal(variable.real, 0.3);
    assert.equal(variable.difference, 0);
    assert.equal(variable.tone, 'neutral');
});

test('la barra omple el que s\'ha gastat i apartat dels ingressos, i no en surt', () => {
    const totals = monthTotals(summaryOf({ fixed: 800 }));
    assert.deepEqual(totals.bar, [
        { key: 'FIXED', width: 40 }, { key: 'VARIABLE', width: 22.5 }, { key: 'SAVINGS', width: 7.5 }
    ]);
    assert.equal(totals.overIncome, 0);

    // 1.200 + 900 + 300 = 2.400 sobre 2.000: les tres parts es redueixen fins al 100 %.
    const over = monthTotals(summaryOf({ fixed: 1200, variable: 900, saved: 300 }));
    assert.equal(Math.round(over.bar.reduce((sum, part) => sum + part.width, 0)), 100);
    assert.equal(over.bar[0].width, 50);
    assert.equal(over.overIncome, 400);

    // Treure més de l'estalvi del que s'hi posa no ocupa barra.
    assert.equal(monthTotals(summaryOf({ saved: -100 })).bar[2].width, 0);
});

test('sense ingressos no hi ha percentatges ni barra, però la taula hi és', () => {
    const totals = monthTotals(summaryOf({ income: null }));
    assert.equal(totals.available, null);
    assert.ok(totals.rows.every(row => row.share === null));
    assert.ok(totals.bar.every(part => part.width === 0));
    assert.equal(rowOf(totals, 'SPENT').real, 450);
    // Un resum buit (un mes sense categories) no peta.
    assert.equal(monthTotals({}).rows.length, TOTAL_ROWS.length);
});
