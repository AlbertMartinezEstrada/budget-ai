import test from 'node:test';
import assert from 'node:assert/strict';

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
