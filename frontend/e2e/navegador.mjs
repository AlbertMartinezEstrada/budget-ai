// Prova de navegador: el que fins ara es comprovava a mà a cada PR.
//
// Entra, importa l'extracte d'exemple per la pantalla fins a desar-lo (amb el
// sopar compartit agrupat amb els seus Bizums a la revisió), i
// recorre totes les vistes i els seus formularis en tema clar i fosc, i després
// en cada idioma. Al final tanca les sessions i comprova que un token copiat
// ja no entra. Falla si hi ha errors de JavaScript, peticions que fallen,
// violacions de la política de seguretat, una capçalera que no diu la vista,
// textos amb poc contrast, una traducció que falta o un text que no hi cap.
//
// Necessita el backend (8000) i el frontend (3000) en marxa, contra una base
// de dades DE PROVA: importa moviments de debò. Per això no arrenca sense
// E2E_BASE_DE_DADES_DE_PROVA=si.
//   E2E_BASE_DE_DADES_DE_PROVA=si E2E_USERNAME=... E2E_PASSWORD=... npm run test:navegador
// CHROMIUM_PATH permet fer servir un Chromium ja instal·lat en lloc del de
// Playwright. Les captures queden a e2e/resultats/.
import { chromium } from 'playwright';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { LANGUAGES, parseProperties } from '../public/js/i18n.js';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const FRONTEND_URL = process.env.E2E_FRONTEND_URL || 'http://localhost:3000';
const USERNAME = process.env.E2E_USERNAME;
const PASSWORD = process.env.E2E_PASSWORD;
const RESULTS_DIR = path.join(HERE, 'resultats');
const SAMPLE_STATEMENT = path.join(HERE, '..', '..', 'test_bank_statement.csv');
// El sopar de l'extracte (REST. EL TRULL, 32 €), que es comparteix amb els seus dos Bizums.
const DINNER_AMOUNT = /\b32[,.]00\b/;
const DINNER_DEBT = 'Sopar a Girona';

/** Els missatges del backend de cada idioma: han d'arribar en el de la pantalla. */
const BACKEND_MESSAGES = Object.fromEntries(LANGUAGES.map(({ code }) => [code, parseProperties(fs.readFileSync(
    path.join(HERE, '..', '..', 'backend-java', 'src', 'main', 'resources', 'i18n', `messages_${code}.properties`), 'utf8'))]));

/** Cada vista, i el botó que obre el seu formulari si en té. */
const VIEWS = [
    { view: 'dashboard' },
    { view: 'transactions', formButton: '#add-transaction-btn' },
    { view: 'upload' },
    { view: 'accounts', formButton: '#add-account-btn' },
    { view: 'budgets', formButton: '#add-budget-btn' },
    { view: 'categories', formButton: '#add-category-btn' },
    { view: 'goals', formButton: '#add-goal-btn' },
    { view: 'debts', formButton: '#add-debt-btn' },
    { view: 'transfers', formButton: '#add-transfer-btn' },
    { view: 'recurring', formButton: '#add-recurring-btn' },
    { view: 'analytics' },
    { view: 'analytics/presupuesto' },
    { view: 'analytics/decisiones' },
    { view: 'settings' }
];

if (process.env.E2E_BASE_DE_DADES_DE_PROVA !== 'si') {
    console.error('Aquesta prova importa moviments: feu-la només contra una base de dades de prova, '
        + 'amb E2E_BASE_DE_DADES_DE_PROVA=si. Mai contra la de les vostres finances.');
    process.exit(2);
}
if (!USERNAME || !PASSWORD) {
    console.error('Falten E2E_USERNAME i E2E_PASSWORD (les credencials del backend en marxa).');
    process.exit(2);
}

const failures = [];
let loggedIn = false;
let currentStep = 'entrar';
const fail = (message) => failures.push(`[${currentStep}] ${message}`);

fs.mkdirSync(RESULTS_DIR, { recursive: true });
const browser = await chromium.launch(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {});
const page = await browser.newPage({ viewport: { width: 1300, height: 1000 } });

// Les confirmacions (desar la importació) s'accepten.
page.on('dialog', dialog => dialog.accept());
page.on('pageerror', error => fail(`error de JavaScript: ${error.message}`));
page.on('console', message => {
    // Les peticions fallides ja les compta el listener de 'response', amb l'adreça.
    if (message.type() === 'error' && !message.text().startsWith('Failed to load resource')) {
        fail(`consola: ${message.text().slice(0, 200)}`);
    }
});
page.on('response', response => {
    if (response.status() < 400) return;
    // Abans d'entrar, /auth/me respon 401: és com l'app sap que cal el login.
    if (!loggedIn && response.status() === 401 && response.url().endsWith('/auth/me')) return;
    fail(`HTTP ${response.status()} ${response.request().method()} ${response.url()}`);
});
page.on('requestfailed', request => {
    // En canviar de vista es tallen les peticions de l'anterior: això no és un error.
    if (request.failure()?.errorText === 'net::ERR_ABORTED') return;
    fail(`petició fallida ${request.url()}: ${request.failure()?.errorText}`);
});
// Les peticions en curs. Sortir d'una vista amb peticions a mig fer les talla,
// i l'aplicació (amb raó) ho registra com a error: abans de sortir, s'espera.
let pendingRequests = 0;
page.on('request', () => pendingRequests++);
page.on('requestfinished', () => pendingRequests--);
page.on('requestfailed', () => pendingRequests--);

/** Fins que no queda cap petició en curs durant mig segon (com a molt, 15 s). */
async function waitForQuietNetwork() {
    const deadline = Date.now() + 15000;
    let quietSince = Date.now();
    while (Date.now() - quietSince < 500 && Date.now() < deadline) {
        if (pendingRequests > 0) quietSince = Date.now();
        await page.waitForTimeout(50);
    }
}

// La política de seguretat no deixa rastre a la consola si no s'escolta.
await page.addInitScript(() => document.addEventListener('securitypolicyviolation', event =>
    console.error(`violació de la política de seguretat: ${event.violatedDirective} (${event.blockedURI})`)));

async function waitForRenderedView() {
    await page.waitForFunction(() => (document.getElementById('main-content')?.children.length || 0) > 0,
        null, { timeout: 15000 });
}

/** Sense transicions: un color a mig canviar no és un problema de contrast. */
async function freezeTransitions() {
    await page.addStyleTag({ content: '*, *::before, *::after { transition: none !important; animation: none !important; }' });
}

async function setTheme(theme) {
    await page.evaluate(dark => document.documentElement.classList.toggle('dark', dark), theme === 'dark');
}

/**
 * Cada text i cada camp visible contra el fons que té a sota (WCAG AA: 4.5:1,
 * 3:1 per al text gran). S'executa dins la pàgina.
 */
function lowContrastTexts() {
    // Un color amb opacitat de Tailwind 4 (bg-red-500/20) surt com a color-mix
    // i el navegador el dona en oklab(): abans es llegia com a transparent i
    // no es mesurava. El que no és rgb() es pinta en un píxel i es llegeix.
    const pixel = document.createElement('canvas').getContext('2d', { willReadFrequently: true });
    const parse = (value) => {
        const match = value.match(/^rgba?\(([^)]+)\)$/);
        if (match) {
            const parts = match[1].split(/[ ,/]+/).filter(Boolean).map(Number);
            return [parts[0], parts[1], parts[2], parts.length > 3 ? parts[3] : 1];
        }
        pixel.clearRect(0, 0, 1, 1);
        pixel.fillStyle = value;
        pixel.fillRect(0, 0, 1, 1);
        const [red, green, blue, alpha] = pixel.getImageData(0, 0, 1, 1).data;
        return [red, green, blue, alpha / 255];
    };
    const over = (top, bottom) => [0, 1, 2].map(channel => top[channel] * top[3] + bottom[channel] * (1 - top[3])).concat(1);
    const backgroundOf = (element) => {
        const layers = [];
        for (let node = element; node; node = node.parentElement) {
            const color = parse(getComputedStyle(node).backgroundColor);
            if (color[3] > 0) layers.push(color);
            if (color[3] >= 1) break;
        }
        return layers.reverse().reduce((result, layer) => over(layer, result), [255, 255, 255, 1]);
    };
    const luminance = (color) => {
        const linear = (value) => {
            const normalized = value / 255;
            return normalized <= 0.03928 ? normalized / 12.92 : ((normalized + 0.055) / 1.055) ** 2.4;
        };
        return 0.2126 * linear(color[0]) + 0.7152 * linear(color[1]) + 0.0722 * linear(color[2]);
    };
    const contrast = (first, second) => {
        const [lighter, darker] = [luminance(first), luminance(second)].sort((left, right) => right - left);
        return (lighter + 0.05) / (darker + 0.05);
    };
    const isVisible = (element) => {
        const box = element.getBoundingClientRect();
        if (box.width === 0 || box.height === 0) return false;
        for (let node = element; node; node = node.parentElement) {
            const style = getComputedStyle(node);
            if (style.display === 'none' || style.visibility === 'hidden' || Number(style.opacity) === 0) return false;
        }
        return true;
    };
    const problems = [];
    for (const element of document.querySelectorAll('body *')) {
        if (['SCRIPT', 'STYLE', 'OPTION', 'CANVAS'].includes(element.tagName)) continue;
        const isField = element.matches('input:not([type=checkbox]):not([type=radio]):not([type=hidden]):not([type=file]), select, textarea');
        const hasOwnText = [...element.childNodes].some(node => node.nodeType === 3 && node.textContent.trim());
        if (!isField && !hasOwnText) continue;
        // Les icones són una font; un control desactivat no està obligat a tenir contrast (WCAG).
        if (element.closest('.material-symbols-outlined') || element.disabled || element.closest('[disabled]')) continue;
        if (!isVisible(element)) continue;
        const style = getComputedStyle(element);
        const background = backgroundOf(element);
        const ratio = contrast(over(parse(style.color), background), background);
        const fontSize = parseFloat(style.fontSize);
        const isLarge = fontSize >= 24 || (fontSize >= 18.66 && Number.parseInt(style.fontWeight, 10) >= 700);
        if (ratio < (isLarge ? 3 : 4.5)) {
            const text = isField ? (element.value || element.placeholder || element.tagName)
                : [...element.childNodes].filter(node => node.nodeType === 3).map(node => node.textContent).join(' ');
            problems.push(`«${text.trim().replace(/\s+/g, ' ').slice(0, 40)}» ${ratio.toFixed(2)}:1 (${String(element.className).slice(0, 60)})`);
        }
    }
    return problems;
}

/**
 * Obre la vista des de zero (sense res de l'anterior) i espera que s'hagi
 * pintat. Passa per una pàgina en blanc: anar a una altra #vista només
 * canviaria l'àncora, i recarregar a sobre tallaria les peticions que la vista
 * acaba de començar.
 */
async function openView(view, theme) {
    await page.goto('about:blank');
    await page.goto(`${FRONTEND_URL}/#${view}`);
    // .sidebar ja és a l'HTML abans de comprovar la sessió: el senyal és que
    // la vista hagi pintat alguna cosa.
    await waitForRenderedView();
    await waitForQuietNetwork();
    await freezeTransitions();
    // L'aplicació posa el tema desat en carregar; aquí mana el que es prova.
    await setTheme(theme);
}

async function checkView({ view, formButton }, theme) {
    currentStep = `${view} · ${theme}`;
    await openView(view, theme);

    const content = await page.evaluate(() => document.getElementById('main-content')?.innerText.trim().length || 0);
    if (content === 0) fail('la vista no ha pintat res');

    const [mainView] = view.split('/');
    const titles = await page.evaluate(viewName => ({
        header: document.getElementById('page-title').textContent.trim(),
        tab: document.title,
        menu: [...document.querySelectorAll('.nav-link[data-view]')]
            .find(link => link.dataset.view === viewName)?.querySelector('span:last-child')?.textContent.trim()
    }), mainView);
    if (!titles.menu || titles.header !== titles.menu) fail(`la capçalera diu «${titles.header}» i el menú «${titles.menu}»`);
    if (!titles.tab.startsWith(titles.header)) fail(`la pestanya diu «${titles.tab}»`);

    for (const problem of await page.evaluate(lowContrastTexts)) fail(`poc contrast: ${problem}`);
    await page.screenshot({ path: path.join(RESULTS_DIR, `${view.replace('/', '-')}-${theme}.png`), fullPage: true });

    if (formButton) {
        currentStep = `${view} · ${theme} · formulari`;
        const button = await page.$(formButton);
        if (!button) {
            fail(`no hi ha el botó ${formButton}`);
            return;
        }
        await button.click();
        await page.waitForTimeout(500);
        for (const problem of await page.evaluate(lowContrastTexts)) fail(`poc contrast: ${problem}`);
        await page.screenshot({ path: path.join(RESULTS_DIR, `${view}-${theme}-formulari.png`) });
    }
}

// ---- Entrar ----

/** Entra per la pantalla d'entrada i torna què ha respost el login. */
async function logIn(targetPage) {
    await targetPage.goto(FRONTEND_URL);
    await targetPage.fill('input[type="text"], input[name="username"]', USERNAME);
    await targetPage.fill('input[type="password"]', PASSWORD);
    // Fins que el login no respon, la cookie de sessió no existeix: si se surt
    // abans de la pàgina, la vista següent torna a demanar el login.
    const [response] = await Promise.all([
        targetPage.waitForResponse(candidate => candidate.url().endsWith('/auth/login')),
        targetPage.keyboard.press('Enter')
    ]);
    return response.status();
}

/** Si la pàgina, oberta de nou, demana entrar: la seva sessió ja no serveix. */
async function asksToLogIn(targetPage) {
    await targetPage.goto(FRONTEND_URL);
    return targetPage.waitForSelector('#login-password', { state: 'visible', timeout: 15000 })
        .then(() => true, () => false);
}

const loginStatus = await logIn(page);
if (loginStatus !== 200) {
    console.error(`El login ha respost ${loginStatus}: reviseu E2E_USERNAME i E2E_PASSWORD.`);
    process.exit(1);
}
loggedIn = true;
await waitForRenderedView();
await waitForQuietNetwork();

// ---- Importar l'extracte d'exemple per la pantalla ----

/**
 * Puja l'extracte i espera la revisió o l'avís que ja hi era. Torna quin dels
 * dos. L'avís el diu el backend, i ha de ser en l'idioma de la pantalla: és la
 * prova que la capçalera Accept-Language hi arriba.
 */
async function uploadSampleStatement() {
    await openView('upload', 'light');
    // El camp de fitxer està amagat sota la zona d'arrossegar: n'hi ha prou que hi
    // sigui. La vista el connecta quan ha carregat els comptes (openView ho espera).
    await page.setInputFiles('#file-input', SAMPLE_STATEMENT);
    await page.waitForSelector('#upload-btn:not([disabled])');
    const [response] = await Promise.all([
        page.waitForResponse(candidate => candidate.url().endsWith('/upload-csv'), { timeout: 30000 }),
        page.click('#upload-btn')
    ]);
    const result = await response.json().catch(() => ({}));
    if (!response.ok() || !['review', 'info'].includes(result.status)) {
        fail(`pujar l'extracte: ${response.status()} «${result.message || result.error || ''}»`);
        return 'error';
    }
    if (result.status === 'review') {
        await page.waitForSelector('#review-section:not(.hidden)');
        return 'revisió';
    }
    const language = await page.evaluate(() => document.documentElement.lang);
    const expected = BACKEND_MESSAGES[language]?.get('upload.all-exist');
    if (result.message !== expected) fail(`l'avís del backend no és en l'idioma de la pantalla (${language}): «${result.message}»`);
    await page.waitForFunction(message => document.getElementById('upload-status').textContent.includes(message),
        result.message, { timeout: 10000 });
    return 'ja hi era';
}

/**
 * L'índex de la fila de la revisió amb aquest import. Sense la IA, l'empresa
 * de totes és «Desconegut»: el sopar és l'únic moviment de 32 €.
 */
async function reviewRowOf(amount) {
    return page.$$eval('#review-body tr[data-index]', (rows, pattern) => rows
        .find(row => new RegExp(pattern).test(row.textContent))?.dataset.index, amount.source);
}

/**
 * El sopar de l'extracte, 32 € entre quatre, i els dos Bizums de 8 € que ja
 * han arribat: s'agrupen a la revisió, abans d'importar. El formulari es mira
 * en clar i en fosc.
 */
async function shareDinnerWhileReviewing() {
    const dinner = await reviewRowOf(DINNER_AMOUNT);
    if (dinner === undefined) {
        fail('a la revisió no hi ha el sopar de l\'extracte');
        return;
    }
    await page.click(`button[data-action="share-row"][data-index="${dinner}"]`);
    await page.waitForSelector('#shared-modal:not(.hidden)');
    for (const theme of ['light', 'dark']) {
        await setTheme(theme);
        for (const problem of await page.evaluate(lowContrastTexts)) fail(`poc contrast (${theme}): ${problem}`);
        await page.screenshot({ path: path.join(RESULTS_DIR, `upload-${theme}-despesa-compartida.png`) });
    }
    await setTheme('light');

    await page.fill('#shared-name', DINNER_DEBT);
    await page.fill('#shared-people', '4');
    const mine = await page.inputValue('#shared-mine');
    if (mine !== '8.00') fail(`32 € entre quatre: la meva part surt de ${mine}`);
    const candidates = await page.$$('#shared-candidates input[data-candidate]');
    if (candidates.length !== 2) fail(`el sopar té ${candidates.length} Bizums per triar i a l'extracte n'hi ha 2`);
    for (const candidate of candidates) await candidate.check();
    await page.click('#shared-save');
    await page.waitForSelector('#shared-modal.hidden', { state: 'attached' });

    const linked = await page.$$eval('#review-body input[name="exclos"][disabled]:checked', boxes => boxes.length);
    if (linked !== 2) fail(`a la revisió, ${linked} Bizums surten vinculats al sopar en lloc de 2`);
}

/** Importat, el sopar surt dividit i el deute diu que en falta una part. */
async function checkSharedDinner() {
    const parts = await page.$$eval('#transactions-body tr', (rows, pattern) => rows
        .find(row => new RegExp(pattern).test(row.textContent))?.querySelector('.parts-toggle')?.textContent.trim(),
        DINNER_AMOUNT.source);
    if (!parts) fail('a Transaccions, el sopar compartit no surt dividit');
    const debts = await page.evaluate(async url => (await fetch(`${url}/debts`, { credentials: 'include' })).json(),
        FRONTEND_URL.replace(/:\d+$/, ':8000'));
    const dinnerDebt = debts.find(debt => debt.nom === DINNER_DEBT);
    if (!dinnerDebt) fail('no hi ha el deute del sopar compartit');
    else if (Number(dinnerDebt.import) !== 24 || Number(dinnerDebt.pendent) !== 8) {
        fail(`el deute del sopar és de ${dinnerDebt.import} i en falten ${dinnerDebt.pendent}; havia de ser de 24 i en faltaven 8`);
    }
}

currentStep = 'importar';
// En una base de dades nova surt la revisió; si la prova ja s'ha fet abans, avisa que hi és.
const firstImport = await uploadSampleStatement() === 'revisió';
if (firstImport) {
    const reviewRows = await page.$$eval('#review-body tr', rows => rows.length);
    if (reviewRows !== 7) fail(`la revisió mostra ${reviewRows} moviments i l'extracte en té 7`);
    currentStep = 'importar · despesa compartida';
    await shareDinnerWhileReviewing();
    currentStep = 'importar';
    await page.click('#confirm-review');
    await page.waitForFunction(() => /✅/.test(document.getElementById('upload-status')?.textContent || ''), null, { timeout: 30000 })
        .catch(async () => fail(`desar la importació no ha acabat bé: «${await page.textContent('#upload-status')}»`));
}

// Tornar a pujar el mateix extracte no és un error: ha de dir que ja hi és.
currentStep = 'importar · el mateix extracte una altra vegada';
if (await uploadSampleStatement() !== 'ja hi era') fail('el mateix extracte, pujat dues vegades, torna a sortir per revisar');

currentStep = 'importar · comprovació';
await openView('transactions', 'light');
const savedCount = Number(await page.textContent('#transaction-count'));
if (!(savedCount >= 7)) fail(`a Transaccions n'hi ha ${savedCount}; n'haurien de ser almenys els 7 importats`);
if (firstImport) await checkSharedDinner();

// ---- Totes les vistes, en clar i en fosc ----
for (const theme of ['light', 'dark']) {
    for (const entry of VIEWS) await checkView(entry, theme);
}

// ---- Cada idioma ----

/** Tria l'idioma a Configuració, com ho faria qualsevol, i espera la vista ja traduïda. */
async function chooseLanguage(code) {
    await openView('settings', 'light');
    await page.waitForSelector('#settings-content', { state: 'visible' });
    await page.selectOption('#setting-language', code);
    await page.waitForFunction(language => document.documentElement.lang === language
        && document.getElementById('settings-content')?.offsetParent !== null, code, { timeout: 15000 });
    await waitForQuietNetwork();
}

/**
 * Un text que no hi cap: la pàgina s'eixampla més que la finestra, o un enllaç
 * del menú talla el seu nom. El portuguès i l'anglès tenen textos més llargs
 * que el català.
 */
function overflowingTexts() {
    const problems = [];
    const root = document.documentElement;
    if (root.scrollWidth > root.clientWidth + 1) problems.push(`la pàgina fa ${root.scrollWidth}px i la finestra ${root.clientWidth}px`);
    for (const link of document.querySelectorAll('.sidebar .nav-link')) {
        if (link.scrollWidth > link.clientWidth + 1) problems.push(`el menú talla «${link.textContent.trim()}»`);
    }
    return problems;
}

for (const { code } of [...LANGUAGES].reverse()) {
    const messages = parseProperties(fs.readFileSync(path.join(HERE, '..', 'public', 'lang', `Language_${code}.properties`), 'utf8'));
    currentStep = `idioma ${code}`;
    await chooseLanguage(code);
    const menu = await page.$$eval('.sidebar .nav-link[data-view]', links => links.map(link =>
        [link.dataset.view, link.querySelector('span:last-child').textContent.trim()]));
    for (const [view, label] of menu) {
        if (label !== messages.get(`nav.${view}`)) fail(`el menú diu «${label}» a ${view} i en ${code} és «${messages.get(`nav.${view}`)}»`);
    }
    for (const { view } of VIEWS) {
        currentStep = `${view} · ${code}`;
        await openView(view, 'light');
        if (await page.evaluate(() => document.documentElement.lang) !== code) fail('la pàgina no és en aquest idioma');
        for (const problem of await page.evaluate(overflowingTexts)) fail(problem);
        await page.screenshot({ path: path.join(RESULTS_DIR, `${view.replace('/', '-')}-${code}.png`), fullPage: true });
    }
}

// ---- Sessions: sortir i «tancar les altres» les tanquen de debò ----
currentStep = 'sessions · tancar les altres';
const otherBrowser = await browser.newContext();
const otherPage = await otherBrowser.newPage();
if (await logIn(otherPage) !== 200) fail('el segon navegador no ha pogut entrar');
await openView('settings', 'light');
await page.waitForSelector('#sessions-list [data-session]');
const listedSessions = await page.$$eval('#sessions-list [data-session]', rows => rows.map(row => row.dataset.session));
if (listedSessions.length < 2) fail(`Configuració ensenya ${listedSessions.length} sessions i n'hi ha almenys dues d'obertes`);
if (listedSessions.filter(kind => kind === 'actual').length !== 1) fail('Configuració no marca una sola sessió com a «aquesta»');
await page.click('#close-other-sessions-btn');
await page.waitForFunction(() => document.querySelectorAll('#sessions-list [data-session]').length === 1
    && document.getElementById('close-other-sessions-btn').disabled, null, { timeout: 15000 })
    .catch(() => fail('després de tancar les altres, Configuració no en deixa només una'));
if (!await asksToLogIn(otherPage)) fail('el segon navegador continua dins després de tancar-ne la sessió');
await otherBrowser.close();

// La cookie d'abans de sortir, copiada a un altre navegador: abans servia set dies més.
currentStep = 'sessions · sortir';
const [sessionCookie] = (await page.context().cookies()).filter(cookie => cookie.name === 'budget_session');
loggedIn = false;
await Promise.all([
    page.waitForResponse(response => response.url().endsWith('/auth/logout')),
    page.click('#logout-btn')
]);
await page.waitForSelector('#login-password', { state: 'visible' });
const copiedBrowser = await browser.newContext();
await copiedBrowser.addCookies([sessionCookie]);
if (!await asksToLogIn(await copiedBrowser.newPage())) fail('una còpia de la cookie continua entrant després de sortir');
await copiedBrowser.close();

await browser.close();

if (failures.length) {
    console.error(`\n✗ ${failures.length} problemes:`);
    for (const failure of failures) console.error(`  · ${failure}`);
    process.exit(1);
}
console.log(`✓ Importació i ${VIEWS.length} vistes en clar i en fosc i en ${LANGUAGES.length} idiomes, `
    + 'sense errors, textos amb poc contrast ni traduccions que faltin; sortir tanca la sessió de debò.');
