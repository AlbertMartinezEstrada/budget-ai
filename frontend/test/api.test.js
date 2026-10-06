import test from 'node:test';
import assert from 'node:assert/strict';

// api.js llegeix window.location en carregar-se per muntar la URL de l'API,
// així que cal simular un mínim de navegador abans d'importar-lo.
global.window = {
    location: { protocol: 'http:', hostname: 'localhost' },
    matchMedia: () => ({ matches: false })
};
global.document = {
    documentElement: { classList: { add() {}, remove() {} } },
    dispatchEvent() {}
};
global.CustomEvent = class CustomEvent {
    constructor(type, init) { this.type = type; this.detail = init && init.detail; }
};

let api;

test.before(async () => {
    api = await import('../public/js/api.js');
});

test('formatCurrency no peta amb valors buits', () => {
    // Aquest era el detonant que tombava la graella d'objectius sencera:
    // undefined.toFixed(2) llança un TypeError dins d'un .map().
    assert.equal(api.formatCurrency(null), '0.00 €');
    assert.equal(api.formatCurrency(undefined), '0.00 €');
    assert.equal(api.formatCurrency(''), '0.00 €');
    assert.equal(api.formatCurrency('no és un número'), '0.00 €');
    assert.equal(api.formatCurrency(NaN), '0.00 €');
});

test('formatCurrency accepta números i cadenes numèriques', () => {
    assert.equal(api.formatCurrency(45.3), '45.30 €');
    assert.equal(api.formatCurrency('12.5'), '12.50 €');
    assert.equal(api.formatCurrency(0), '0.00 €');
    assert.equal(api.formatCurrency(-33.333), '-33.33 €');
});

test('formatCurrency arrodoneix a dos decimals', () => {
    assert.equal(api.formatCurrency(1234.567), '1234.57 €');
});

test('escapeHtml neutralitza les etiquetes', () => {
    assert.equal(
        api.escapeHtml('<img src=x onerror=alert(1)>'),
        '&lt;img src=x onerror=alert(1)&gt;'
    );
    assert.equal(api.escapeHtml('<script>'), '&lt;script&gt;');
});

test('escapeHtml escapa les cometes, que trencaven els atributs', () => {
    // El nom d'empresa s'interpola dins de value="...", i un apòstrof
    // trencava la fila sencera de la taula de revisió.
    assert.equal(api.escapeHtml(`O'Brien`), 'O&#39;Brien');
    assert.equal(api.escapeHtml('diu "hola"'), 'diu &quot;hola&quot;');
});

test('escapeHtml escapa l\'ampersand primer, sense doble escapat', () => {
    assert.equal(api.escapeHtml('A & B'), 'A &amp; B');
    // Si l'ampersand no es fes primer, això donaria &amp;lt;
    assert.equal(api.escapeHtml('&lt;'), '&amp;lt;');
});

test('escapeHtml tracta els buits com a cadena buida', () => {
    assert.equal(api.escapeHtml(null), '');
    assert.equal(api.escapeHtml(undefined), '');
    assert.equal(api.escapeHtml(0), '0');
    assert.equal(api.escapeHtml(false), 'false');
});

test('appState exposa els valors per defecte esperats', () => {
    assert.equal(api.appState.currency, 'EUR');
    assert.ok(api.appState.notifications);
});

test('un 404 sense missatge explica que potser cal reconstruir el backend', () => {
    // Això és el que torna Spring quan el backend en marxa no coneix la ruta:
    // dividir un moviment amb el backend sense reconstruir deia "Not Found".
    const springDefault = JSON.stringify({
        timestamp: '2026-10-06T16:00:00Z', status: 404, error: 'Not Found', path: '/gastos/12/parts'
    });

    const message = api.describeError(404, springDefault, '/gastos/12/parts');

    assert.ok(message.includes('/gastos/12/parts'), message);
    assert.ok(message.includes('docker compose up -d --build backend'), message);
});

test('el missatge del backend mana sobre l\'explicació genèrica', () => {
    const body = JSON.stringify({ status: 'error', message: 'No existeix el moviment 12.', path: '/gastos/12/parts' });

    assert.equal(api.describeError(404, body, '/gastos/12/parts'), 'No existeix el moviment 12.');
    // Els endpoints antics encara posen el text a "error".
    assert.equal(api.describeError(409, JSON.stringify({ error: 'El compte té moviments' })), 'El compte té moviments');
});

test('una resposta buida o en HTML es descriu pel codi', () => {
    assert.ok(api.describeError(500, '').includes('docker compose logs backend'));
    assert.ok(api.describeError(502, '<html><body>Bad Gateway</body></html>').includes('no respon'));
    assert.ok(!api.describeError(502, '<html>').includes('<'));
});

test('sense connexió amb el backend, el missatge ho diu', async () => {
    const realFetch = global.fetch;
    global.fetch = async () => { throw new TypeError('Failed to fetch'); };
    try {
        await assert.rejects(api.apiFetch('/gastos'), (error) => {
            assert.ok(error.message.includes('No es pot connectar amb el backend'), error.message);
            assert.ok(!error.message.includes('Failed to fetch'));
            return true;
        });
    } finally {
        global.fetch = realFetch;
    }
});
