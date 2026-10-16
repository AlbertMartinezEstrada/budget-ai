import test from 'node:test';
import assert from 'node:assert/strict';
import { createApp, contentSecurityPolicy } from '../index.js';

/**
 * El servidor que entrega la pàgina diu al navegador què hi pot passar.
 *
 * Abans no enviava cap capçalera de seguretat, s'anunciava com a Express i
 * deixava qualsevol web llegir els fitxers (Access-Control-Allow-Origin: *).
 */

async function headersFor(urlPath, host) {
    const server = createApp().listen(0, '127.0.0.1');
    await new Promise(resolve => server.once('listening', resolve));
    try {
        const response = await fetch(`http://127.0.0.1:${server.address().port}${urlPath}`,
            { headers: host ? { Host: host } : {} });
        await response.arrayBuffer();
        return { status: response.status, headers: response.headers };
    } finally {
        await new Promise(resolve => server.close(resolve));
    }
}

test('la pàgina surt amb les capçaleres de seguretat', async () => {
    const { status, headers } = await headersFor('/', 'localhost:3000');
    assert.equal(status, 200);
    assert.equal(headers.get('x-content-type-options'), 'nosniff');
    assert.equal(headers.get('x-frame-options'), 'DENY');
    assert.equal(headers.get('referrer-policy'), 'no-referrer');
    assert.equal(headers.get('x-powered-by'), null, 'no s\'ha d\'anunciar el servidor');
    assert.equal(headers.get('access-control-allow-origin'), null, 'cap altra web ha de poder llegir els fitxers');

    const policy = headers.get('content-security-policy');
    assert.match(policy, /script-src 'self'(;|$)/, 'només scripts propis, cap inline');
    assert.match(policy, /frame-ancestors 'none'/);
    assert.match(policy, /object-src 'none'/);
});

test('els fitxers estàtics també porten les capçaleres', async () => {
    const { status, headers } = await headersFor('/js/api.js', 'localhost:3000');
    assert.equal(status, 200);
    assert.ok(headers.get('content-security-policy'));
    assert.equal(headers.get('x-content-type-options'), 'nosniff');
});

test('la pàgina pot parlar amb el backend a la mateixa adreça, sigui quina sigui', () => {
    // api.js crida al port 8000 de l'adreça amb què s'ha obert la pàgina:
    // localhost al PC, el nom del PC des del mòbil.
    assert.match(contentSecurityPolicy('localhost'), /connect-src 'self' http:\/\/localhost:8000 https:\/\/localhost:8000/);
    assert.match(contentSecurityPolicy('mi-pc'), /connect-src 'self' http:\/\/mi-pc:8000 https:\/\/mi-pc:8000/);
});

test('una capçalera Host estranya no pot afegir regles a la política', () => {
    const policy = contentSecurityPolicy("evil.com; script-src 'unsafe-inline'");
    assert.doesNotMatch(policy, /evil|unsafe-inline'[^;]*script/);
    assert.match(policy, /connect-src 'self'(;|$)/);
});

test('api.js crida el backend al port que la política permet', async () => {
    const { readFile } = await import('node:fs/promises');
    const source = await readFile(new URL('../public/js/api.js', import.meta.url), 'utf8');
    assert.match(source, /window\.location\.hostname\}:8000/,
        'si el backend canvia de port, canvieu-lo també a index.js (BACKEND_PORT)');
});
