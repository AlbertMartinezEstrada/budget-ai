import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

let module;

test.before(async () => {
    module = await import('../public/js/debtLinks.js');
    // Els textos es comproven en català, l'idioma de referència.
    const i18n = await import('../public/js/i18n.js');
    const catalan = fs.readFileSync(new URL('../public/lang/Language_ca.properties', import.meta.url), 'utf8');
    i18n.setMessages('ca', i18n.parseProperties(catalan));
});

test('la categoria proposada depèn del sentit del deute i del moviment', () => {
    const owe = { direccio: 'DEC' };
    const owed = { direccio: 'EM_DEUEN' };
    // La quota de la Steam Deck és una devolució; l'entrada del préstec, un ingrés.
    assert.equal(module.suggestedDebtCategory(owe, 'EXPENSE'), 'Pagament de deutes');
    assert.equal(module.suggestedDebtCategory(owe, 'INCOME'), 'Préstecs rebuts');
    assert.equal(module.suggestedDebtCategory(owed, 'EXPENSE'), 'Préstecs fets');
    assert.equal(module.suggestedDebtCategory(owed, 'INCOME'), 'Cobrament de préstecs');
    // Sense deute, o un sentit que no es coneix, no es proposa res.
    assert.equal(module.suggestedDebtCategory(undefined, 'EXPENSE'), null);
    assert.equal(module.suggestedDebtCategory({ direccio: 'ALTRE' }, 'EXPENSE'), null);
});

test('cada deute diu en quin sentit va, i si ja està saldat', () => {
    assert.equal(module.debtOptionLabel({ nom: 'Steam Deck', direccio: 'DEC' }), 'Steam Deck (dec)');
    assert.match(module.debtOptionLabel({ nom: 'Steam Deck', direccio: 'DEC', saldat: true }), /^Steam Deck \(dec, saldat/);
    assert.match(module.debtOptionLabel({ nom: 'Sopar', direccio: 'EM_DEUEN' }), /^Sopar \(/);
    assert.notEqual(module.debtOptionLabel({ nom: 'Sopar', direccio: 'EM_DEUEN' }),
        module.debtOptionLabel({ nom: 'Sopar', direccio: 'DEC' }));
});
