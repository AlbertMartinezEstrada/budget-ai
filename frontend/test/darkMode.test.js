import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const publicDir = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'public');
const read = (file) => fs.readFileSync(path.join(publicDir, file), 'utf8');

/**
 * El tema fosc no es trenca en silenci.
 *
 * Abans, els camps eren blancs amb el text clar del tema (no es veia què
 * s'escrivia), les capçaleres de taula es quedaven clares i els títols de les
 * vistes, clars sobre un fons clar. Les vistes fan servir classes de Tailwind
 * pensades per al tema clar, i css/dark.css les tradueix: aquests tests vigilen
 * que cap classe nova d'aquestes es quedi sense traduir.
 */

/** Les classes de color que el CSS compilat té, és a dir, les que fa servir l'aplicació. */
function usedClasses() {
    const tailwind = read('css/tailwind.css');
    return new Set([...tailwind.matchAll(/\.((?:hover\\:)?(?:bg|text)-[a-z]+(?:-\d+)?(?:\\\/\d+)?)(?=[\s{:,>.])/g)]
        .map(match => match[1]));
}

/** Les que en fosc no es llegirien: fons clars, grisos de text i colors amb significat en to fosc. */
const NEEDS_DARK = [
    /^bg-white$/,
    /^bg-(gray|slate)-(50|100|200|300)$/,
    /^hover\\:bg-(gray|slate|red)-(50|100)$/,
    /^bg-(amber|blue|emerald|green|indigo|red|teal|violet|orange|yellow)-(50|100)$/,
    /^text-(gray|slate)-(400|500|600|700|800|900)$/,
    /^text-(red|green|emerald|teal|orange|amber|blue|indigo|violet|yellow)-(500|600|700|800|900)$/,
    /^text-primary$/
];

test('cada classe clara que fa servir l\'aplicació té el seu equivalent fosc a dark.css', () => {
    const dark = read('css/dark.css');
    const missing = [...usedClasses()]
        .filter(name => NEEDS_DARK.some(pattern => pattern.test(name)))
        .filter(name => !dark.includes(`.${name}`));
    assert.deepEqual(missing, [],
        `sense equivalent fosc: ${missing.join(', ')}. Afegeix-lo a public/css/dark.css (o posa la variant dark: a la vista i aquí també).`);
});

test('dark.css es carrega després de tailwind.css, perquè a igual especificitat mani', () => {
    const html = read('index.html');
    const tailwind = html.indexOf('href="css/tailwind.css"');
    const dark = html.indexOf('href="css/dark.css"');
    assert.ok(tailwind > 0 && dark > tailwind, 'index.html ha de carregar css/dark.css després de css/tailwind.css');
});

test('les traduccions de dark.css no passen per davant d\'una variant dark: escrita a la vista', () => {
    // :where(html.dark) no suma especificitat: una classe .dark\:x de la vista
    // n'ha de tenir més. Un "html.dark .bg-white" la trepitjaria.
    const dark = read('css/dark.css').replace(/\/\*[\s\S]*?\*\//g, '');
    const utilityRules = [...dark.matchAll(/^([^{}@]+)\{/gm)]
        .map(match => match[1].trim())
        .filter(selector => /\.(bg|text|border|divide|hover)/.test(selector));
    const unsafe = utilityRules.filter(selector => selector.split(',')
        .some(part => !part.trim().startsWith(':where(html.dark)')));
    assert.deepEqual(unsafe, []);
});

test('els camps i les taules agafen els colors del tema, no un blanc fix', () => {
    const components = read('css/components.css');
    const formControl = components.match(/\.form-control, \.input \{[^}]*\}/)[0];
    assert.match(formControl, /background-color: var\(--bg-input\)/);
    assert.match(formControl, /color: var\(--text-main\)/);
    assert.match(components.match(/\.table th \{[^}]*\}/)[0], /background-color: var\(--bg-subtle\)/);

    const main = read('css/main.css');
    const darkBlock = main.match(/html\.dark \{[^}]*\}/)[0];
    for (const variable of ['--bg-content', '--bg-input', '--bg-subtle', '--bg-muted']) {
        assert.ok(darkBlock.includes(variable), `falta ${variable} al tema fosc`);
    }
    assert.match(darkBlock, /color-scheme: dark/);
});

test('la barra lateral, fosca en tots dos temes, no fa servir colors de text per a fons clar', () => {
    // En clar, els enllaços eren text-slate-600 i el logotip indi 600 sobre el
    // gray-800 de la barra: menys de 2.5:1, no es llegien.
    const html = read('index.html');
    const sidebar = html.slice(html.indexOf('<nav class="sidebar">'), html.indexOf('<main'));
    assert.deepEqual(sidebar.match(/\btext-(slate|gray)-(500|600|700|800|900)\b|\btext-primary\b|#4f46e5/g), null);

    const app = fs.readFileSync(path.join(publicDir, 'js', 'app.js'), 'utf8');
    assert.match(app, /classList\.toggle\('active', link\.dataset\.view === view\)/);

    const layout = read('css/layout.css');
    for (const rule of ['.sidebar-logo', '.sidebar .nav-link', '.sidebar .nav-link.active']) {
        const block = layout.match(new RegExp(`${rule.replace(/\./g, '\\.')} \\{[^}]*\\}`))[0];
        assert.doesNotMatch(block, /var\(--primary-color\)/, `${rule} amb el color primari no es llegeix sobre la barra`);
        assert.match(block, /color: #/, `${rule} ha de dur el seu color`);
    }
});
