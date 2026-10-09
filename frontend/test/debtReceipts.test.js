import test from 'node:test';
import assert from 'node:assert/strict';

// debtReceipts.js fa servir escapeHtml i formatCurrency d'api.js, que llegeix
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
    module = await import('../public/js/debtReceipts.js');
});

const receipt = (data, estat, amount = '97.38') => ({ data, import: amount, pagat: '0', estat, eliminable: true });

// La Steam Deck: 779 € a 97,38 € al mes des del 8 de setembre, amb el
// setembre saltat.
const steamDeck = {
    id: 1,
    forma_retorn: 'QUOTES',
    frequencia: 'MENSUAL',
    calendari: [
        receipt('2026-09-08', 'SALTAT', '0'),
        receipt('2026-10-08', 'PAGAT'),
        receipt('2026-11-08', 'PENDENT'),
        receipt('2026-12-08', 'PENDENT')
    ]
};

/** Les opcions convertides en { value, text, selected }. */
function parse(html) {
    return [...html.matchAll(/<option value="([^"]*)"( selected)?>([\s\S]*?)<\/option>/g)]
        .map(([, value, selected, text]) => ({ value, selected: Boolean(selected), text: text.replace(/\s+/g, ' ').trim() }));
}

test('un rebut mensual es diu pel mes, no pel dia', () => {
    assert.equal(module.receiptLabel('2026-10-08', 'MENSUAL'), 'octubre 2026');
    // Tot de cop no té freqüència: també va pel mes.
    assert.equal(module.receiptLabel('2026-03-31', undefined), 'març 2026');
});

test('un rebut setmanal es diu pel dilluns de la setmana, i un de trimestral pels tres mesos', () => {
    // Dimecres 7 d'octubre de 2026: la setmana comença el dilluns 5.
    assert.equal(module.receiptLabel('2026-10-07', 'SETMANAL'), `setmana del ${new Date(2026, 9, 5).toLocaleDateString('ca-ES')}`);
    assert.equal(module.receiptLabel('2026-10-15', 'TRIMESTRAL'), 'octubre–desembre 2026');
    assert.equal(module.receiptLabel('2026-11-15', 'TRIMESTRAL'), 'novembre 2026–gener 2027');
});

test('el rebut d\'aquest període "toca", amb les paraules de la freqüència', () => {
    assert.equal(module.receiptStatusLabel('TOCA', 'MENSUAL'), 'toca aquest mes');
    assert.equal(module.receiptStatusLabel('TOCA', 'SETMANAL'), 'toca aquesta setmana');
    assert.equal(module.receiptStatusLabel('ENDARRERIT', 'MENSUAL'), 'endarrerit');
    assert.equal(module.receiptStatusLabel('SALTAT', 'MENSUAL'), 'saltat: passa al final');
});

test('dues dates són del mateix rebut si cauen al mateix període, com al backend', () => {
    assert.ok(module.samePeriod('2026-10-08', '2026-10-31', 'MENSUAL'));
    assert.ok(!module.samePeriod('2026-10-08', '2026-11-01', 'MENSUAL'));
    // De dilluns a diumenge.
    assert.ok(module.samePeriod('2026-10-07', '2026-10-11', 'SETMANAL'));
    assert.ok(!module.samePeriod('2026-10-07', '2026-10-12', 'SETMANAL'));
    // Els tres mesos que comencen el del rebut.
    assert.ok(module.samePeriod('2026-11-15', '2027-01-31', 'TRIMESTRAL'));
    assert.ok(!module.samePeriod('2026-11-15', '2027-02-01', 'TRIMESTRAL'));
    assert.ok(!module.samePeriod('2026-11-15', '2026-10-31', 'TRIMESTRAL'));
});

test('el desplegable comença pel que toca i no ofereix els rebuts trets', () => {
    const options = parse(module.receiptOptions(steamDeck));

    assert.equal(options[0].value, '');
    assert.match(options[0].text, /El que toca/);
    assert.deepEqual(options.slice(1).map(option => option.value), ['2026-10-08', '2026-11-08', '2026-12-08']);
    assert.match(options[1].text, /octubre 2026 · 97\.38 € · pagat/);
    assert.ok(options.every(option => !option.selected));
});

test('el rebut triat es reconeix pel mes, encara que el dia no sigui el del calendari', () => {
    const options = parse(module.receiptOptions(steamDeck, '2026-11-20'));

    assert.deepEqual(options.filter(option => option.selected).map(option => option.value), ['2026-11-08']);
});

test('un rebut triat que ja no és al calendari surt igualment, perquè editar no el canviï sense voler', () => {
    const options = parse(module.receiptOptions(steamDeck, '2026-09-08'));

    const selected = options.filter(option => option.selected);
    assert.equal(selected.length, 1);
    assert.equal(selected[0].value, '2026-09-08');
    assert.match(selected[0].text, /setembre 2026 · ja no és al calendari/);
});

test('només hi ha rebut per triar als deutes a quotes', () => {
    assert.ok(module.hasReceiptsToChoose(steamDeck));
    assert.ok(!module.hasReceiptsToChoose({ forma_retorn: 'UNIC', calendari: [receipt('2026-10-01', 'PENDENT')] }));
    assert.ok(!module.hasReceiptsToChoose({ forma_retorn: 'LLIURE', calendari: [] }));
    assert.ok(!module.hasReceiptsToChoose(undefined));
});
