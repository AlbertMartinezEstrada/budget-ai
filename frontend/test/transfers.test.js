import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

// transfers.js fa servir escapeHtml d'api.js, que llegeix window en carregar-se.
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
    module = await import('../public/js/transfers.js');
    // Els textos es comproven en català, l'idioma de referència.
    const i18n = await import('../public/js/i18n.js');
    const catalan = fs.readFileSync(new URL('../public/lang/Language_ca.properties', import.meta.url), 'utf8');
    i18n.setMessages('ca', i18n.parseProperties(catalan));
});

const principal = { id: 1, nom: 'Compte Principal', tipus: 'CORRIENTE' };
const revolut = { id: 2, nom: 'Revolut', tipus: 'CORRIENTE' };
const tradeRepublic = { id: 3, nom: 'Trade Republic', tipus: 'INVERSIONES' };

test('un traspàs entre comptes del dia a dia no compta; cap a l\'estalvi o des d\'ell, sí', () => {
    // El mateix criteri que InternalTransferService.movesSavings: si aquí
    // fos un altre, el formulari diria una cosa i el backend en faria una altra.
    assert.equal(module.transferCounts(principal, revolut), false);
    assert.equal(module.transferCounts(principal, tradeRepublic), true);
    assert.equal(module.transferCounts(tradeRepublic, principal), true);
    assert.equal(module.isSavingsAccount({ tipus: 'AHORRO' }), true);
    assert.equal(module.isSavingsAccount(undefined), false);
});

test('l\'etiqueta diu cap a on van els diners', () => {
    assert.equal(module.transferLabel('EXPENSE', 'Revolut'), '⇄ a Revolut');
    assert.equal(module.transferLabel('INCOME', 'Trade Republic'), '⇄ des de Trade Republic');
});

test('el desplegable no ofereix el mateix compte, i marca l\'estalvi', () => {
    const html = module.counterpartOptions([principal, revolut, tradeRepublic], 1, 3);
    assert.ok(!html.includes('Compte Principal'));
    assert.match(html, /<option value="3" selected>\s*Trade Republic \(estalvi\)/);
    assert.ok(html.startsWith('<option value="">No és un traspàs</option>'));
});

test('els noms dels comptes s\'escapen', () => {
    const html = module.counterpartOptions([{ id: 9, nom: '<b>O\'Brien</b>' }], null);
    assert.ok(!html.includes('<b>'));
    assert.ok(html.includes('&lt;b&gt;O&#39;Brien&lt;/b&gt;'));
});

test('a Estalvi i inversió mana la categoria: des del dia a dia és estalvi, marcat com a traspàs o no', () => {
    const hint = module.savingsCategoryHint('EXPENSE', principal, '');
    assert.match(hint, /compta com a estalviat, encara que no ho marquis com a traspàs/);
    assert.match(hint, /categoria de despesa/);
    assert.match(module.savingsCategoryHint('INCOME', principal, ''), /resta de l'estalviat/);
});

test('el que es paga des d\'un compte d\'estalvi és gastat del fons', () => {
    assert.match(module.savingsCategoryHint('EXPENSE', tradeRepublic, ''), /gastat del fons/);
    // El que hi entra s'hi queda: no cal dir res.
    assert.equal(module.savingsCategoryHint('INCOME', tradeRepublic, ''), '');
});

test('d\'un traspàs ja en parla l\'avís de l\'altre compte', () => {
    assert.equal(module.savingsCategoryHint('EXPENSE', principal, '3'), '');
});
