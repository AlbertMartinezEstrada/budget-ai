import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

/**
 * Guàrdia contra regressions conegudes.
 *
 * No comprova que el codi sigui correcte: comprova que no tornin els patrons
 * exactes que ja han fallat una vegada. Són barats de mantenir i cadascun
 * correspon a un error real que va arribar a producció.
 */

const JS_DIR = path.join(__dirname, '..', 'public', 'js');

function jsFiles(dir = JS_DIR, acc = []) {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
        const full = path.join(dir, entry.name);
        if (entry.isDirectory()) jsFiles(full, acc);
        else if (entry.name.endsWith('.js')) acc.push(full);
    }
    return acc;
}

// Els comentaris s'eliminen abans d'analitzar: aquest fitxer busca patrons
// al codi, i diversos comentaris expliquen precisament els errors antics
// citant-ne els noms. Sense això, la documentació faria fallar els tests.
function stripComments(source) {
    return source
        .replace(/\/\*[\s\S]*?\*\//g, '')
        .replace(/^\s*\/\/.*$/gm, '');
}

const files = jsFiles().map(file => ({
    path: path.relative(path.join(__dirname, '..'), file),
    source: stripComments(fs.readFileSync(file, 'utf8'))
}));

function findAll(pattern) {
    return files
        .filter(f => pattern.test(f.source))
        .map(f => f.path);
}

test('hi ha fitxers per analitzar', () => {
    assert.ok(files.length >= 12, `només s'han trobat ${files.length} fitxers`);
});

test('no es fan servir els noms de camp que el backend no produeix', () => {
    // Cadascun d'aquests va provocar una pantalla en blanc o un valor a zero.
    const forbidden = [
        ['total_expenses', 'AnalyticsService retorna "total_expense" en singular'],
        ['\\.target_amount', 'FinancialGoal exposa "quantitat_objectiu"'],
        ['\\.current_amount', 'FinancialGoal exposa "quantitat_actual"'],
        ['account_origen_id', 'Transfer espera "sourceAccount"'],
        ['account_desti_id', 'Transfer espera "destinationAccount"'],
        ['settings\\.user_name', 'Settings se serialitza en camelCase: "userName"'],
        ['notifications_expenses', 'Settings se serialitza en camelCase']
    ];

    const problems = [];
    for (const [pattern, reason] of forbidden) {
        const hits = findAll(new RegExp(pattern));
        if (hits.length) problems.push(`${pattern} -> ${hits.join(', ')} (${reason})`);
    }

    assert.deepEqual(problems, [], `\n${problems.join('\n')}`);
});

test('analytics no tracta la categoria com un objecte', () => {
    // Només a la vista d'anàlisi: /analytics/category-breakdown retorna
    // "category" com a cadena. En canvi, un Budget sí que porta l'objecte
    // Category anidat, i allà budget.category.nom és correcte.
    const analytics = files.find(f => f.path.includes('Analytics.js'));
    assert.ok(analytics, 'no s\'ha trobat Analytics.js');
    assert.ok(
        !/cat\.category\s*\?\./.test(analytics.source),
        'Analytics.js torna a llegir category com si fos un objecte'
    );
});

test('cap crida a l\'API es fa sense la cookie de sessió', () => {
    // Totes les crides han de passar per apiFetch, que hi posa
    // credentials: 'include'. Un fetch directe cap al backend no enviaria la
    // cookie i rebria un 401.
    const api = files.find(f => f.path.endsWith(path.join('js', 'api.js')));
    assert.ok(api, 'no s\'ha trobat api.js');

    // L'única línia que pot cridar fetch() amb la URL del backend és la del
    // propi helper apiFetch, i ha de portar-hi les credencials.
    const rawFetchLines = api.source
        .split('\n')
        .filter(line => /fetch\(`\$\{API_URL\}/.test(line));

    assert.equal(rawFetchLines.length, 1,
        `hi ha ${rawFetchLines.length} crides directes a fetch; només apiFetch en pot fer`);
    assert.ok(/credentials:\s*'include'/.test(rawFetchLines[0]),
        'apiFetch ha d\'enviar credentials: include');
});

test('només api.js sap on viu el backend', () => {
    // Budgets i Recurring cridaven http://localhost:8000 a pèl, de manera que
    // només funcionaven obrint l'aplicació des de la mateixa màquina.
    const offenders = files
        .filter(f => !f.path.endsWith(path.join('js', 'api.js')))
        .filter(f => /https?:\/\/localhost:\d+/.test(f.source))
        .map(f => f.path);

    assert.deepEqual(offenders, [], `URL del backend fora d'api.js: ${offenders.join(', ')}`);
});

test('cap vista fa servir onclick amb dades interpolades', () => {
    // Un compte anomenat O'Brien trencava la fila sencera, i un nom preparat
    // a posta podia executar codi.
    const offenders = findAll(/onclick\s*=/);
    assert.deepEqual(offenders, [], `onclick a: ${offenders.join(', ')}`);
});

test('les vistes que interpolen dades a l\'HTML importen escapeHtml', () => {
    // Una plantilla totalment estàtica (com la de Configuració) no necessita
    // escapat; el criteri és si hi ha interpolació dins de l'HTML.
    const interpolatesIntoHtml = files.filter(f =>
        f.path.includes('features') &&
        /innerHTML\s*=/.test(f.source) &&
        /\$\{/.test(f.source)
    );

    const missing = interpolatesIntoHtml
        .filter(f => !/escapeHtml/.test(f.source))
        .map(f => f.path);

    assert.deepEqual(missing, [], `vistes sense escapat: ${missing.join(', ')}`);
});

test('no es construeixen noms de classe de Tailwind en temps d\'execució', () => {
    // Tailwind no pot generar `bg-${color}-500`: la classe no existeix.
    const offenders = findAll(/["'`](?:bg|text|border)-\$\{/);
    assert.deepEqual(offenders, [], `classes dinàmiques a: ${offenders.join(', ')}`);
});

test('app.js coneix totes les vistes que sap renderitzar', () => {
    const app = files.find(f => f.path.endsWith(path.join('js', 'app.js')));
    assert.ok(app, 'no s\'ha trobat app.js');

    // Les vistes del switch i les de la llista VIEWS han de coincidir: si una
    // vista no és a VIEWS, el routing per hash la ignora en silenci.
    const cases = [...app.source.matchAll(/case\s+'([a-z]+)':/g)].map(m => m[1]);
    const viewsList = app.source.match(/const VIEWS\s*=\s*\[([\s\S]*?)\]/);
    assert.ok(viewsList, 'no s\'ha trobat la llista VIEWS');

    const declared = [...viewsList[1].matchAll(/'([a-z]+)'/g)].map(m => m[1]);

    const missing = cases.filter(c => !declared.includes(c));
    assert.deepEqual(missing, [], `vistes al switch però no a VIEWS: ${missing.join(', ')}`);
});
