import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
    parseProperties, setMessages, t, LANGUAGES, REFERENCE_LANGUAGE, supportedLanguage,
    preferredLanguage, formatNumber, formatPercent, formatDate, getLocale
} from '../public/js/i18n.js';

/**
 * Els idiomes, a la manera de Liferay: una clau per text i un fitxer per idioma.
 *
 * Aquests tests vigilen el que no dona cap error i es veu malament: una clau
 * que falta en un idioma (surt la clau a la pantalla), un {valor} que una
 * traducció no porta, una clau que el codi demana i no existeix, i un text
 * escrit a mà en una vista ja traduïda (surt en català en tots els idiomes,
 * que és com estava abans: cada vista en el seu idioma).
 */

const publicDir = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'public');
const read = (file) => fs.readFileSync(path.join(publicDir, file), 'utf8');
const languageFile = (code) => `lang/Language_${code}.properties`;
const catalogues = Object.fromEntries(LANGUAGES.map(({ code }) => [code, parseProperties(read(languageFile(code)))]));
const reference = catalogues[REFERENCE_LANGUAGE];

/** Les vistes i fitxers ja passats a claves: no hi pot quedar cap text escrit a mà. */
const TRANSLATED_FILES = [
    'index.html',
    'js/app.js',
    'js/api.js',
    'js/features/auth/Login.js',
    'js/features/settings/Settings.js',
    'js/features/dashboard/Dashboard.js'
];

const PLURAL_SUFFIX = /\.(zero|one|two|few|many|other)$/;
const placeholders = (text) => [...text.matchAll(/\{(\w+)\}/g)].map(match => match[1]).sort();

function jsFiles(dir = path.join(publicDir, 'js'), found = []) {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
        const full = path.join(dir, entry.name);
        if (entry.isDirectory()) jsFiles(full, found);
        else if (entry.name.endsWith('.js')) found.push(full);
    }
    return found;
}

function stripComments(source) {
    return source
        .replace(/<!--[\s\S]*?-->/g, '')
        .replace(/\/\*[\s\S]*?\*\//g, '')
        .replace(/^\s*\/\/.*$/gm, '');
}

/** El codi on es fan servir claus: tot el JavaScript de la pàgina (menys i18n.js) i index.html. */
const codeFiles = [...jsFiles().filter(file => !file.endsWith('i18n.js')), path.join(publicDir, 'index.html')]
    .map(file => ({ path: path.relative(publicDir, file), source: stripComments(fs.readFileSync(file, 'utf8')) }));

test('el fitxer de cada idioma es llegeix com un .properties', () => {
    const entries = parseProperties([
        '# comentari',
        '! també comentari',
        'simple = Hola',
        'amb.dos-punts: Valor amb = dins',
        'llarg = primera part \\',
        '    i segona',
        'escapat = línia\\nnova i barra \\\\ i \\u00e9',
        '   sagnat=sense espais'
    ].join('\n'));
    assert.equal(entries.get('simple'), 'Hola');
    assert.equal(entries.get('amb.dos-punts'), 'Valor amb = dins');
    assert.equal(entries.get('llarg'), 'primera part i segona');
    assert.equal(entries.get('escapat'), 'línia\nnova i barra \\ i é');
    assert.equal(entries.get('sagnat'), 'sense espais');
    assert.equal(entries.size, 5);
});

test('els quatre idiomes tenen exactament les mateixes claus que el català', () => {
    const referenceKeys = [...reference.keys()];
    for (const { code } of LANGUAGES) {
        const keys = new Set(catalogues[code].keys());
        const missing = referenceKeys.filter(key => !keys.has(key));
        const extra = [...keys].filter(key => !reference.has(key));
        assert.deepEqual({ missing, extra }, { missing: [], extra: [] }, `${languageFile(code)}`);
    }
});

test('cap clau surt dues vegades en un mateix fitxer', () => {
    // parseProperties es queda amb l'última: la primera desapareixeria sense avisar.
    for (const { code } of LANGUAGES) {
        const keys = read(languageFile(code)).split('\n')
            .map(line => line.trim())
            .filter(line => line && !line.startsWith('#') && !line.startsWith('!'))
            .map(line => line.split(/\s*[=:]/)[0]);
        const repeated = keys.filter((key, index) => keys.indexOf(key) !== index);
        assert.deepEqual(repeated, [], languageFile(code));
    }
});

test('cada traducció porta els mateixos {valors} que la de referència', () => {
    const mismatches = [];
    for (const { code } of LANGUAGES) {
        for (const [key, text] of catalogues[code]) {
            const expected = placeholders(reference.get(key) ?? '');
            if (placeholders(text).join() !== expected.join()) {
                mismatches.push(`${code} ${key}: {${placeholders(text).join('}, {')}} en lloc de {${expected.join('}, {')}}`);
            }
        }
    }
    assert.deepEqual(mismatches, []);
});

test('les traduccions es poden posar tal qual dins de l\'HTML', () => {
    // t('clau') sense valors va directament dins de les plantilles d'innerHTML.
    // Amb un < o unes cometes, una traducció trencaria l'etiqueta o l'atribut.
    const problems = [];
    for (const { code } of LANGUAGES) {
        for (const [key, text] of catalogues[code]) {
            if (!/^[a-z0-9]+(?:[.-][a-z0-9]+)+$/.test(key)) problems.push(`${code}: clau «${key}» (minúscules, punts i guions)`);
            if (!text.trim()) problems.push(`${code} ${key}: buida`);
            if (/[<>"&`]/.test(text)) problems.push(`${code} ${key}: porta < > " & o \``);
        }
    }
    assert.deepEqual(problems, []);
});

/** Les cadenes del codi que tenen forma de clau d'un dels espais de noms dels fitxers. */
function keysUsedInCode() {
    const namespaces = new Set([...reference.keys()].map(key => key.split('.')[0]));
    const used = new Map();
    for (const file of codeFiles) {
        for (const [, key] of file.source.matchAll(/['"`]([a-z0-9-]+(?:\.[a-z0-9-]+)+)['"`]/g)) {
            if (!namespaces.has(key.split('.')[0])) continue;
            if (!used.has(key)) used.set(key, new Set());
            used.get(key).add(file.path);
        }
    }
    return used;
}

test('cada clau que demana el codi existeix', () => {
    const missing = [...keysUsedInCode()]
        .filter(([key]) => !reference.has(key) && !reference.has(`${key}.other`))
        .map(([key, files]) => `${key} (${[...files].join(', ')})`);
    assert.deepEqual(missing, [], `afegiu-les a ${languageFile(REFERENCE_LANGUAGE)} i als altres tres`);
});

test('cap clau dels fitxers queda sense fer servir', () => {
    const used = keysUsedInCode();
    const unused = [...new Set([...reference.keys()].map(key => used.has(key) ? key : key.replace(PLURAL_SUFFIX, '')))]
        .filter(key => !used.has(key));
    assert.deepEqual(unused, [], 'traieu-les dels quatre fitxers o feu-les servir');
});

test('un text amb valors no s\'interpola dins de l\'HTML sense escapar', () => {
    // Els valors (el nom d'un compte, un concepte del banc) no són de confiança:
    // ${t('clau', { name })} dins d'un innerHTML seria HTML de la pàgina.
    const offenders = codeFiles
        .filter(file => /\$\{\s*t\(\s*['"][^'"]+['"]\s*,/.test(file.source))
        .map(file => file.path);
    assert.deepEqual(offenders, [], 'feu servir ${escapeHtml(t(\'clau\', { … }))}');
});

/**
 * Els textos escrits a mà que quedin en un fitxer: el contingut de les
 * etiquetes, els atributs que es llegeixen (title, placeholder, aria-label,
 * alt) i els que es posen amb textContent o com a missatge d'error. Les
 * interpolacions ${…}, els noms d'icona i «Budget AI» no compten.
 */
function handwrittenTexts(source) {
    // De dins cap a fora: ${escapeHtml(t('clau', { message }))} porta claus a dins.
    const withoutInterpolations = (text) => {
        let previous;
        do {
            previous = text;
            text = text.replace(/\{[^{}]*\}/g, '');
        } while (text !== previous);
        return text.replace(/\$/g, '');
    };
    const isText = (text) => {
        const cleaned = withoutInterpolations(text).replace(/Budget AI/g, '').trim();
        // Els noms d'icona (account_balance) i els codis (EUR, INCOME) no es tradueixen.
        return /[A-Za-zÀ-ÿ]{2,}/.test(cleaned) && !/^[a-z0-9_]+$/.test(cleaned) && !/^[A-Z0-9_]+$/.test(cleaned);
    };
    const code = source
        .split('\n').filter(line => !/^\s*console\.(log|warn|error)\(/.test(line)).join('\n')
        // El que posa data-i18n se substitueix en carregar: el text de dins és el de reserva.
        .replace(/<(\w+)([^>]*\sdata-i18n="[^"]+"[^>]*)>[^<]*<\/\1>/g, '<$1$2></$1>')
        .replace(/\stitle="[^"]*"([^>]*data-i18n-title=)/g, '$1');
    const found = [];
    // El text entre una etiqueta i la següent. Si hi ha una cometa invertida
    // pel mig, s'ha sortit de la plantilla i el que hi ha és codi.
    for (const [, text] of code.matchAll(/<\/?[a-zA-Z][^<>]*>([^<>]+)(?=<[/a-zA-Z!])/g)) {
        if (!text.includes('`') && isText(text)) found.push(text.trim());
    }
    for (const [, text] of code.matchAll(/\s(?:title|placeholder|aria-label|alt)="([^"]*)"/g)) if (isText(text)) found.push(text);
    for (const [, , text] of code.matchAll(/\.textContent\s*=\s*(['`])((?:(?!\1).)*)\1/g)) if (isText(text)) found.push(text);
    for (const [, , text] of code.matchAll(/new Error\(\s*(['`])((?:(?!\1).)*)\1/g)) if (isText(text)) found.push(text);
    for (const [, text] of code.matchAll(/\|\|\s*'([^']*)'/g)) if (isText(text)) found.push(text);
    return found;
}

test('les vistes ja traduïdes no tenen cap text escrit a mà', () => {
    const problems = TRANSLATED_FILES.flatMap(file =>
        handwrittenTexts(stripComments(read(file))).map(text => `${file}: «${text.replace(/\s+/g, ' ').slice(0, 60)}»`));
    assert.deepEqual(problems, [], 'passeu-los a una clau de lang/Language_ca.properties (i dels altres tres)');
});

test('el detector de textos escrits a mà troba els casos que vigila', () => {
    // Si el detector deixés de trobar res, el test d'abans passaria sempre.
    assert.deepEqual(handwrittenTexts('<h3 class="x">Moviments</h3>'), ['Moviments']);
    assert.deepEqual(handwrittenTexts('<button title="Esborrar">'), ['Esborrar']);
    assert.deepEqual(handwrittenTexts("element.textContent = 'Desat';"), ['Desat']);
    assert.deepEqual(handwrittenTexts("throw new Error('No hi és');"), ['No hi és']);
    assert.deepEqual(handwrittenTexts("transaction.empresa || 'Desconegut'"), ['Desconegut']);
    assert.deepEqual(handwrittenTexts("<h3>${t('nav.debts')}</h3><span class=\"icon\">savings</span><b>Budget AI</b>"), []);
    assert.deepEqual(handwrittenTexts("<p>${escapeHtml(t('dashboard.load-failed', { message: error.message }))}</p>"), []);
    assert.deepEqual(handwrittenTexts('<span data-i18n="nav.debts">Deutes</span>'), []);
    // El codi entre dues plantilles no és text de la pàgina.
    assert.deepEqual(handwrittenTexts("`<div>${t('nav.debts')}</div>`; if (total > 0 && others.length) container.innerHTML = `<p>${t('nav.debts')}</p>`"), []);
});

test('t() substitueix els valors i tria el plural', () => {
    setMessages('ca', new Map([
        ['greeting', 'Hola, {name}'],
        ['items.one', '{count} moviment'],
        ['items.other', '{count} moviments'],
        ['partial', 'Falta {missing}']
    ]));
    assert.equal(t('greeting', { name: 'Anna' }), 'Hola, Anna');
    assert.equal(t('items', { count: 1 }), '1 moviment');
    assert.equal(t('items', { count: 3 }), '3 moviments');
    // Si una regla del CLDR dona una forma que el fitxer no té («many»), val «other».
    assert.equal(t('items', { count: 1000000 }), '1000000 moviments');
    assert.equal(t('partial'), 'Falta {missing}');
    // Una clau que no hi és surt tal qual, i la consola ho diu.
    const originalError = console.error;
    const logged = [];
    console.error = (message) => logged.push(message);
    try {
        assert.equal(t('no.existeix'), 'no.existeix');
    } finally {
        console.error = originalError;
    }
    assert.match(logged[0], /no\.existeix/);
});

test('l\'idioma del navegador es reconeix pel prefix', () => {
    assert.equal(supportedLanguage('pt-BR'), 'pt');
    assert.equal(supportedLanguage('ca-ES-valencia'), 'ca');
    assert.equal(supportedLanguage('EN_gb'), 'en');
    assert.equal(supportedLanguage('fr'), null);
    assert.equal(supportedLanguage(undefined), null);
});

test('mana l\'idioma desat; si no, el primer del navegador que es parla; si no, el català', () => {
    assert.equal(preferredLanguage('es', ['en-GB']), 'es');
    assert.equal(preferredLanguage(null, ['fr-FR', 'pt-PT', 'en']), 'pt');
    assert.equal(preferredLanguage(null, ['de', 'fr']), REFERENCE_LANGUAGE);
});

test('els números, els percentatges i les dates surten amb el format de cada idioma', () => {
    const spaces = (text) => text.replace(/[  ]/g, ' ');
    const expected = {
        ca: { number: '1.234,5', percent: '12,5 %', date: '06/10/2026' },
        es: { number: '1.234,5', percent: '12,5 %', date: '06/10/2026' },
        pt: { number: '1 234,5', percent: '12,5%', date: '06/10/2026' },
        en: { number: '1,234.5', percent: '12.5%', date: '06/10/2026' }
    };
    for (const { code, locale } of LANGUAGES) {
        setMessages(code, new Map());
        assert.equal(getLocale(), locale);
        assert.deepEqual({
            number: spaces(formatNumber(1234.5)),
            percent: spaces(formatPercent(12.5)),
            // El text de l'API és un dia, no una hora UTC: no pot sortir el dia abans.
            date: formatDate('2026-10-06')
        }, expected[code], code);
    }
    assert.equal(formatDate(''), '');
    assert.equal(formatDate('no és una data'), '');
});
