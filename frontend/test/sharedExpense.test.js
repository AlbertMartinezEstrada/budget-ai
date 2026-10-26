import test from 'node:test';
import assert from 'node:assert/strict';
import {
    equalShare, balanceOf, canShare, canRepay, repaymentCandidates, openSharedExpenseDebts
} from '../public/js/sharedExpense.js';

test('a parts iguals, el cèntim que no es reparteix és meu', () => {
    assert.deepEqual(equalShare(40, 4), { mine: 10, each: 10, owed: 30 });
    // 40 entre 3: em deuen 13,33 cadascú, i la meva part s'emporta el cèntim.
    assert.deepEqual(equalShare('40.00', 3), { mine: 13.34, each: 13.33, owed: 26.66 });
    // Menys de dues persones no és compartir: es compta com a dues.
    assert.deepEqual(equalShare(40, 1), { mine: 20, each: 20, owed: 20 });
});

test('el que em deuen, el que m\'han tornat i el que falta, en cèntims exactes', () => {
    assert.deepEqual(balanceOf(40, 10, [10, '10.00']),
        { owed: 30, repaid: 20, pending: 10, validShare: true, tooMuch: false });
    // 0,1 + 0,2 en coma flotant no fa 0,3; en cèntims sí.
    assert.equal(balanceOf(0.3, 0, [0.1, 0.2]).pending, 0);
    assert.equal(balanceOf(40, 10, [10, 10, 10, 10]).tooMuch, true);
});

test('la meva part ha de ser de 0 a menys del total', () => {
    assert.equal(balanceOf(40, 0).validShare, true);
    assert.equal(balanceOf(40, 39.99).validShare, true);
    assert.equal(balanceOf(40, 40).validShare, false);
    assert.equal(balanceOf(40, -1).validShare, false);
    assert.equal(balanceOf(40, '').validShare, false);
});

test('només es comparteix una despesa lliure, i només la torna un ingrés lliure', () => {
    const dinner = { type: 'EXPENSE', cost: 40 };
    const bizum = { type: 'INCOME', cost: 10 };
    assert.equal(canShare(dinner), true);
    assert.equal(canShare(bizum), false);
    assert.equal(canShare({ ...dinner, parts: [{}, {}] }), false);
    assert.equal(canShare({ ...dinner, deute_id: 3 }), false);
    assert.equal(canShare({ ...dinner, compte_contrapart_id: 2 }), false);
    // -1 vol dir «sense deute» o «ja no és un traspàs».
    assert.equal(canShare({ ...dinner, deute_id: -1, compte_contrapart_id: -1 }), true);

    assert.equal(canRepay(bizum), true);
    assert.equal(canRepay(dinner), false);
    assert.equal(canRepay({ ...bizum, deute_id: 3 }), false);
    assert.equal(canRepay({ ...bizum, compte_contrapart_id: 2 }), false);
});

test('els candidats: ingressos lliures dins la finestra, primer els de la part de cadascú', () => {
    const payment = { type: 'EXPENSE', cost: 40, data: '2026-10-03' };
    const movements = [
        payment,
        { id: 1, type: 'INCOME', cost: 7, data: '2026-10-04' },
        { id: 2, type: 'INCOME', cost: 10, data: '2026-10-06' },
        { id: 3, type: 'INCOME', cost: 10, data: '2026-10-05' },
        { id: 4, type: 'INCOME', cost: 10, data: '2027-01-30' },
        { id: 5, type: 'INCOME', cost: 10, data: '2026-10-04', deute_id: 8 },
        { id: 6, type: 'EXPENSE', cost: 10, data: '2026-10-04' }
    ];

    const saved = repaymentCandidates(payment, movements, { each: 10, window: { before: 7, after: 90 } });
    assert.deepEqual(saved.map(movement => movement.id), [3, 2, 1]);

    // Sense finestra (la revisió d'un extracte), també el del gener.
    const all = repaymentCandidates(payment, movements, { each: 10 });
    assert.deepEqual(all.map(movement => movement.id), [3, 2, 4, 1]);
});

test('el Bizum que arriba tard només va a un deute que em deuen, no saldat i on l\'avançament no compta', () => {
    const sharedDinner = [
        { type: 'EXPENSE', es_part: true, exclos_pressupost: true },
        { type: 'INCOME', exclos_pressupost: true }
    ];
    const debts = [
        { id: 1, direccio: 'EM_DEUEN', pendent: 10, moviments: sharedDinner },
        { id: 2, direccio: 'EM_DEUEN', pendent: 0, moviments: sharedDinner },
        { id: 3, direccio: 'DEC', pendent: 500, moviments: [] },
        // Un préstec a un amic que compta com a despesa: el que em torni ha de
        // comptar com a ingrés, i aquest Bizum s'excloria.
        { id: 4, direccio: 'EM_DEUEN', pendent: 200, moviments: [{ type: 'EXPENSE', exclos_pressupost: false }] },
        // Un deute sense cap avançament vinculat: no n'ha comptat cap.
        { id: 5, direccio: 'EM_DEUEN', pendent: 50 }
    ];
    assert.deepEqual(openSharedExpenseDebts(debts).map(debt => debt.id), [1, 5]);
});
