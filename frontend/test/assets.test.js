// El CSS de Tailwind, les fonts i Chart.js es generen amb "npm run build:assets" i es
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

test('les fonts i Chart.js de public/vendor són els del paquet instal·lat', () => {
    const stale = vendorFiles()
        .filter(([source, target]) => {
            const copied = path.join(root, 'public', 'vendor', target);
            return !fs.existsSync(copied)
                || !fs.readFileSync(copied).equals(fs.readFileSync(path.join(root, 'node_modules', source)));
        })
        .map(([, target]) => target);
    assert.deepEqual(stale, [], `fitxers desactualitzats: ${stale.join(', ')}. Executa "npm run build:assets"`);
});

test('les variables pròpies no es diuen com les de Tailwind', () => {
    // Tailwind 4 posa el seu tema en variables (--radius-lg, --color-red-600…)
    // i els CSS propis van en una capa per sobre: amb el mateix nom, la nostra
    // trepitja la seva. Una --radius-lg de main.css feia rodones les caselles
    // amb rounded-lg.
    const own = new Set([...fs.readFileSync(path.join(root, 'public', 'css', 'main.css'), 'utf8')
        .matchAll(/(--[\w-]+)\s*:/g)].map(match => match[1]));
    const tailwind = fs.readFileSync(path.join(root, 'public', 'css', 'tailwind.css'), 'utf8');
    const theirs = new Set([...tailwind.matchAll(/(--[\w-]+)\s*[:,)]/g)].map(match => match[1]));
    assert.ok(theirs.has('--radius-lg') && theirs.has('--color-slate-500'), 'no s\'han trobat les variables de Tailwind');
    assert.deepEqual([...own].filter(name => theirs.has(name)), []);
});
