import test from 'node:test';
import assert from 'node:assert/strict';
import * as general from '../public/js/generalAnalysis.js';

// Divendres 16 d'octubre de 2026.
const TODAY = new Date(2026, 9, 16);

test('els períodes: mes, trimestre, any i els últims 12 mesos', () => {
    assert.deepEqual(general.periodRange('mes', '2026-09', TODAY), { from: '2026-09', to: '2026-09' });
    assert.deepEqual(general.periodRange('trimestre', '2026-T3', TODAY), { from: '2026-07', to: '2026-09' });
    assert.deepEqual(general.periodRange('trimestre', '2026-T4', TODAY), { from: '2026-10', to: '2026-12' });
    assert.deepEqual(general.periodRange('any', '2025', TODAY), { from: '2025-01', to: '2025-12' });
    assert.deepEqual(general.periodRange('ultims12', '', TODAY), { from: '2025-11', to: '2026-10' });
});

test('les opcions de cada tipus de període comencen pel més recent', () => {
    const months = general.periodOptions('mes', TODAY);
    assert.equal(months.length, 24);
    assert.deepEqual(months[0], { value: '2026-10', label: 'octubre 2026' });
    assert.deepEqual(months[10], { value: '2025-12', label: 'diciembre 2025' });

    const quarters = general.periodOptions('trimestre', TODAY);
    assert.deepEqual(quarters.slice(0, 5).map(option => option.value),
        ['2026-T4', '2026-T3', '2026-T2', '2026-T1', '2025-T4']);
    assert.equal(quarters[1].label, '3.º trimestre 2026');

    assert.deepEqual(general.periodOptions('any', TODAY).map(option => option.value),
        ['2026', '2025', '2024', '2023', '2022']);
    assert.deepEqual(general.periodOptions('ultims12', TODAY), []);
});

test('el nom d\'un rang', () => {
    assert.equal(general.rangeLabel('2026-10', '2026-10'), 'octubre 2026');
    assert.equal(general.rangeLabel('2026-07', '2026-09'), 'jul–sep 2026');
    assert.equal(general.rangeLabel('2025-01', '2025-12'), '2025');
    assert.equal(general.rangeLabel('2025-11', '2026-10'), 'nov 2025 – oct 2026');
    assert.equal(general.monthsBefore('2026-02', 3), '2025-11');
});

test('el canvi diu si és bo o dolent segons la xifra: més despesa és dolent, més estalvi és bo', () => {
    assert.deepEqual(general.changeOf(1080, 1000, false), { percent: 8, direction: 'up', tone: 'bad' });
    assert.deepEqual(general.changeOf(1080, 1000, true), { percent: 8, direction: 'up', tone: 'good' });
    assert.deepEqual(general.changeOf('970', '1000', false), { percent: -3, direction: 'down', tone: 'good' });
    assert.deepEqual(general.changeOf(1000, 1001, true), { percent: 0, direction: 'same', tone: 'neutral' });
    // Sense referència, o de zero, no hi ha percentatge que valgui.
    assert.equal(general.changeOf(50, 0, true), null);
    assert.equal(general.changeOf(50, null, true), null);
    // Amb una referència negativa (s'havia gastat més del que entrava), millorar és pujar.
    assert.deepEqual(general.changeOf(-50, -100, true), { percent: 50, direction: 'up', tone: 'good' });

    assert.equal(general.formatChange({ percent: -3, direction: 'down' }), '▼ 3 %');
    assert.equal(general.formatChange(null), '');
});

test('l\'estructura en % dels ingressos, i el que s\'ha gastat de més es diu a part', () => {
    assert.deepEqual(general.structureShares({
        periode: '2026-09', ingressos: '2000', fixos: '550', variables: '300', estalvi: '200', sense_destinar: '950'
    }), {
        periode: '2026-09',
        shares: { fixos: 27.5, variables: 15, estalvi: 10, sense_destinar: 47.5 },
        excedit: 0
    });
    const over = general.structureShares({
        periode: '2026-08', ingressos: '1000', fixos: '700', variables: '400', estalvi: '0', sense_destinar: '-100'
    });
    assert.equal(over.shares.sense_destinar, 0);
    assert.equal(over.excedit, 10);
    assert.deepEqual(general.structureShares({ periode: '2026-07', ingressos: '0' }), { periode: '2026-07', shares: null });
});

test('cada sèrie té el seu color fix, en clar i en fosc', () => {
    assert.equal(general.chartTheme(false).series.length, 4);
    assert.equal(general.chartTheme(false).series[0], '#2a78d6');
    assert.equal(general.chartTheme(true).surface, '#1e293b');
    assert.deepEqual(general.EVOLUTION_SERIES.map(series => series.key), ['total_income', 'total_expense', 'total_savings']);
});

test('sense classificar avisa a partir del 15 %, i les barres van relatives a la més gran', () => {
    assert.ok(general.unclassifiedIsHigh({ percentatge: '15.0' }));
    assert.ok(!general.unclassifiedIsHigh({ percentatge: '14.9' }));
    assert.ok(!general.unclassifiedIsHigh(null));
    assert.deepEqual(general.barWidths([{ total: '550' }, { total: '275' }, { total: '0' }]), [100, 50, 0]);
    assert.deepEqual(general.barWidths([]), []);
});

test('els mesos buits del principi no surten al gràfic; els del mig, sí', () => {
    const months = [
        { period: '2026-03', total_income: 0, total_expense: 0 },
        { period: '2026-04', total_income: 0, total_expense: 0 },
        { period: '2026-05', total_income: 2000, total_expense: 900 },
        { period: '2026-06', total_income: 0, total_expense: 0 },
        { period: '2026-07', total_income: 2000, total_expense: 800 }
    ];
    assert.deepEqual(general.trimEmptyLeading(months, ['total_income', 'total_expense']).map(month => month.period),
        ['2026-05', '2026-06', '2026-07']);
    assert.deepEqual(general.trimEmptyLeading([{ total_income: 0 }], ['total_income']), []);
    assert.equal(general.axisMonth('2025-11'), 'nov 25');
});

test('l\'eix de l\'estructura arriba al 100 %, llevat que algun mes s\'hi passi', () => {
    const normal = general.structureShares({ periode: '2026-09', ingressos: '3', fixos: '1', variables: '1', estalvi: '1', sense_destinar: '0' });
    // 33.3 + 33.3 + 33.3: l'arrodoniment no ha d'estirar l'eix fins al 120 %.
    assert.equal(general.structureAxisMax([normal]), 100);
    const over = general.structureShares({ periode: '2026-08', ingressos: '1000', fixos: '700', variables: '400', estalvi: '0', sense_destinar: '-100' });
    assert.equal(general.structureAxisMax([normal, over]), undefined);
    assert.equal(general.structureAxisMax([]), 100);
});
