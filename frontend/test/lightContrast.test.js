import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const publicDir = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'public');
const read = (file) => fs.readFileSync(path.join(publicDir, file), 'utf8');

/**
 * El tema clar es llegeix: cap text per sota de 4.5:1 (WCAG AA).
 *
 * Abans, el verd dels ingressos (text-success, 2.5:1 sobre blanc), el vermell
 * dels errors i les despeses (text-error i text-red-500, 3.8:1), el taronja, el
 * teal, els grisos 400 i el botó verd amb text blanc no s'hi arribaven. Ara es
 * fa servir el to que sí que s'hi arriba (el 600-700), i aquests tests vigilen
 * que no en torni a entrar un de clar. En fosc, dark.css posa el seu to.
 */

const MINIMUM = 4.5;
const WHITE = [255, 255, 255];

/** El CSS que pinta el tema clar: el de Tailwind i les classes pròpies. */
const lightCss = read('css/tailwind.css') + read('css/components.css');

/** Les regles d'una sola classe: { 'text-red-600': 'color:rgb(220 38 38/...)' }. */
const rules = new Map([...lightCss.matchAll(/(?<=^|[}\n])\s*\.((?:[a-z]+\\:)?[a-z][\w-]*(?:\\\/\d+)?)\s*\{([^}]*)\}/g)]
    .map(match => [match[1].replace(/\\/g, ''), match[2]]));

/** Les variables del tema clar (:root de main.css), per resoldre un var(--…). */
const rootBlock = read('css/main.css').match(/:root \{([^}]*)\}/)[1];
const variables = new Map([...rootBlock.matchAll(/(--[\w-]+):\s*([^;]+);/g)].map(match => [match[1], match[2]]));

function parseColor(value) {
    const variable = value.match(/var\((--[\w-]+)\)/);
    if (variable) return variables.has(variable[1]) ? parseColor(variables.get(variable[1])) : null;
    if (/^\s*(white|#fff)\b/.test(value)) return WHITE;
    const hex = value.match(/#([0-9a-f]{6})\b/i);
    if (hex) return [0, 2, 4].map(index => parseInt(hex[1].slice(index, index + 2), 16));
    const rgb = value.match(/rgb\((\d+)\s+(\d+)\s+(\d+)/);
    return rgb ? rgb.slice(1, 4).map(Number) : null;
}

function colorOf(name, property) {
    const body = rules.get(name);
    const declaration = body && body.match(new RegExp(`(?:^|;)\\s*${property}:\\s*([^;]+)`));
    return declaration ? parseColor(declaration[1]) : null;
}

function luminance(color) {
    const channel = (value) => {
        const normalized = value / 255;
        return normalized <= 0.03928 ? normalized / 12.92 : ((normalized + 0.055) / 1.055) ** 2.4;
    };
    return 0.2126 * channel(color[0]) + 0.7152 * channel(color[1]) + 0.0722 * channel(color[2]);
}

function contrast(first, second) {
    const [lighter, darker] = [luminance(first), luminance(second)].sort((a, b) => b - a);
    return Math.round((lighter + 0.05) / (darker + 0.05) * 100) / 100;
}

/** Tots els fitxers que escriuen classes: les vistes i index.html. */
function sources() {
    const files = [path.join(publicDir, 'index.html')];
    const walk = (dir) => {
        for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
            const full = path.join(dir, entry.name);
            if (entry.isDirectory()) walk(full);
            else if (entry.name.endsWith('.js')) files.push(full);
        }
    };
    walk(path.join(publicDir, 'js'));
    return files.map(file => [path.relative(publicDir, file), fs.readFileSync(file, 'utf8')]);
}

test('cada color de text que fa servir l\'aplicació es llegeix sobre blanc', () => {
    // text-white només va sobre un fons de color: ho mira el test següent.
    const weak = [...rules.keys()]
        .filter(name => /^text-/.test(name) && name !== 'text-white')
        .map(name => [name, colorOf(name, 'color')])
        .filter(([, color]) => color)
        .map(([name, color]) => [name, contrast(color, WHITE)])
        .filter(([, ratio]) => ratio < MINIMUM);
    assert.deepEqual(weak, [],
        `per sota de ${MINIMUM}:1 sobre blanc: ${weak.map(([name, ratio]) => `${name} (${ratio})`).join(', ')}. ` +
        'Fes servir un to més fosc (el 600-700) i posa el seu equivalent a dark.css.');
});

test('el text sobre un fons de color (pastilles, avisos, botons) també s\'hi arriba', () => {
    const weak = new Set();
    for (const [file, source] of sources()) {
        // Qualsevol tros entre cometes: class="…" dins d'un `…`, 'bg-… text-…' en
        // un objecte… Es talla per totes les cometes perquè, amb les niades, una
        // parella "obertura-tancament" se saltaria justament les classes.
        for (const literal of source.split(/["'`]/)) {
            const classes = literal.split(/\s+/);
            const background = classes.find(name => /^bg-[a-z]+(-\d+)?$/.test(name) && colorOf(name, 'background-color'));
            const text = classes.find(name => /^text-[a-z]+(-\d+)?$/.test(name) && colorOf(name, 'color'));
            if (!background || !text) continue;
            const ratio = contrast(colorOf(text, 'color'), colorOf(background, 'background-color'));
            if (ratio < MINIMUM) weak.add(`${text} sobre ${background} (${ratio}) a ${file}`);
        }
    }
    assert.deepEqual([...weak], []);
});

test('els botons propis amb text blanc tenen un fons prou fosc', () => {
    const weak = [...rules.keys()]
        .filter(name => /^btn-/.test(name))
        .filter(name => /(?:^|;)\s*color:\s*(white|#fff)\b/.test(rules.get(name)))
        .map(name => [name, colorOf(name, 'background-color')])
        .filter(([, background]) => background)
        .map(([name, background]) => [name, contrast(WHITE, background)])
        .filter(([, ratio]) => ratio < MINIMUM);
    assert.deepEqual(weak, []);
});
