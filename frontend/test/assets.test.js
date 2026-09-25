// El CSS de Tailwind i les fonts es generen amb "npm run build:assets" i es
// desen al repositori, perquè el frontend se serveix des del disc tal qual.
// Aquests tests comproven que el que hi ha desat és el que es generaria ara:
// una classe nova sense regenerar el CSS no donaria cap error, simplement no
// tindria estil.

import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { buildTailwind, vendorFiles } from '../scripts/build-assets.mjs';

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');

test('public/css/tailwind.css està al dia', () => {
    const fresh = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'tailwind-')), 'tailwind.css');
    buildTailwind(fresh);

    const committed = fs.readFileSync(path.join(root, 'public', 'css', 'tailwind.css'), 'utf8');
    assert.ok(committed === fs.readFileSync(fresh, 'utf8'),
        'tailwind.css no coincideix amb les classes que es fan servir: executa "npm run build:assets"');
});

test('les fonts de public/vendor són les del paquet instal·lat', () => {
    const stale = vendorFiles()
        .filter(([source, target]) => {
            const copied = path.join(root, 'public', 'vendor', target);
            return !fs.existsSync(copied)
                || !fs.readFileSync(copied).equals(fs.readFileSync(path.join(root, 'node_modules', source)));
        })
        .map(([, target]) => target);
    assert.deepEqual(stale, [], `fonts desactualitzades: ${stale.join(', ')}. Executa "npm run build:assets"`);
});
