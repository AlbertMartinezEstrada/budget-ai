/**
 * Els idiomes de la interfície, a la manera de Liferay: cada text és una clau
 * («settings.language.title») i cada idioma té el seu fitxer de propietats
 * (lang/Language_ca.properties, Language_es…). Les vistes demanen t('clau') i
 * surt en l'idioma triat.
 *
 * Abans cada vista anava en un idioma —Transaccions en castellà, Pressupost en
 * català— i no es podia triar. El català és el de referència: és el fitxer que
 * té totes les claus, i els tests comproven que els altres tres tenen les
 * mateixes.
 *
 * No toca el DOM en carregar-se: els tests l'importen des de Node.
 */

export const LANGUAGES = [
    { code: 'ca', name: 'Català', locale: 'ca-ES' },
    { code: 'es', name: 'Español', locale: 'es-ES' },
    { code: 'pt', name: 'Português', locale: 'pt-PT' },
    { code: 'en', name: 'English', locale: 'en-GB' }
];

export const REFERENCE_LANGUAGE = 'ca';

/** On es recorda l'idioma per a la pantalla d'entrada, que no pot llegir els ajustos. */
const STORAGE_KEY = 'budget-ai.idioma';

let currentLanguage = REFERENCE_LANGUAGE;
let messages = new Map();

/**
 * Llegeix un fitxer .properties: «clau = valor», comentaris amb # o !, una
 * línia acabada en \ continua a la següent, i \n, \t, \\ i \uXXXX com a Java.
 * Els fitxers són UTF-8: els accents s'hi escriuen tal qual.
 */
export function parseProperties(text) {
    const entries = new Map();
    const lines = text.replace(/\r\n?/g, '\n').split('\n');
    for (let index = 0; index < lines.length; index++) {
        let line = lines[index].replace(/^\s+/, '');
        if (!line || line.startsWith('#') || line.startsWith('!')) continue;
        while (endsWithContinuation(line) && index + 1 < lines.length) {
            line = line.slice(0, -1) + lines[++index].replace(/^\s+/, '');
        }
        const separator = separatorIndex(line);
        const key = (separator === -1 ? line : line.slice(0, separator)).trim();
        const value = separator === -1 ? '' : line.slice(separator + 1).replace(/^\s+/, '');
        entries.set(unescapeProperty(key), unescapeProperty(value));
    }
    return entries;
}

/** El primer = o : que no va escapat amb una barra. */
function separatorIndex(line) {
    for (let index = 0; index < line.length; index++) {
        if (line[index] === '\\') index++;
        else if (line[index] === '=' || line[index] === ':') return index;
    }
    return -1;
}

/** Una barra al final continua la línia; dues seguides són una barra escrita. */
function endsWithContinuation(line) {
    const trailingBackslashes = line.match(/\\*$/)[0].length;
    return trailingBackslashes % 2 === 1;
}

function unescapeProperty(value) {
    return value.replace(/\\(u[0-9a-fA-F]{4}|.)/g, (sequence, escaped) => {
        if (escaped.startsWith('u') && escaped.length === 5) return String.fromCharCode(parseInt(escaped.slice(1), 16));
        return { n: '\n', t: '\t', r: '\r' }[escaped] ?? escaped;
    });
}

/** El codi d'idioma que s'aplica: un dels quatre, o null si no se'n sap cap. */
export function supportedLanguage(code) {
    if (!code) return null;
    const prefix = String(code).toLowerCase().split(/[-_]/)[0];
    return LANGUAGES.some(language => language.code === prefix) ? prefix : null;
}

/**
 * L'idioma amb què s'obre l'aplicació: el desat als ajustos, si n'hi ha; si no,
 * l'últim triat en aquest navegador; si no, el primer dels del navegador que
 * sapiguem parlar (pt-BR és portuguès, ca-ES-valencia és català). I si no, el
 * de referència.
 */
export function preferredLanguage(saved, browserLanguages = globalThis.navigator?.languages || []) {
    const candidates = [saved, rememberedLanguage(), ...browserLanguages];
    for (const candidate of candidates) {
        const language = supportedLanguage(candidate);
        if (language) return language;
    }
    return REFERENCE_LANGUAGE;
}

function rememberedLanguage() {
    try {
        return globalThis.localStorage?.getItem(STORAGE_KEY) || null;
    } catch {
        // Amb les dades del lloc bloquejades, el navegador no deixa llegir-lo.
        return null;
    }
}

export function rememberLanguage(code) {
    try {
        globalThis.localStorage?.setItem(STORAGE_KEY, code);
    } catch {
        // Sense poder-lo recordar, la pantalla d'entrada sortirà en el del navegador.
    }
}

/** Fa servir aquestes traduccions. Els tests en passen un fitxer llegit del disc. */
export function setMessages(code, entries) {
    currentLanguage = supportedLanguage(code) || REFERENCE_LANGUAGE;
    messages = entries;
}

/** Baixa el fitxer de l'idioma i el fa servir. */
export async function useLanguage(code) {
    const language = supportedLanguage(code) || REFERENCE_LANGUAGE;
    const response = await fetch(`lang/Language_${language}.properties`);
    if (!response.ok) throw new Error(`lang/Language_${language}.properties: HTTP ${response.status}`);
    setMessages(language, parseProperties(await response.text()));
    return language;
}

export function getLanguage() {
    return currentLanguage;
}

/** El locale de l'idioma actual, per als formats de número i de data (Intl). */
export function getLocale() {
    return LANGUAGES.find(language => language.code === currentLanguage).locale;
}

/**
 * El text d'una clau en l'idioma actual, amb els {noms} substituïts.
 *
 * Amb un {count} numèric es busca la forma del plural que toqui
 * (clau.one, clau.other…, segons Intl.PluralRules), i si no hi és, clau.other.
 *
 * Retorna text, no HTML: dins d'un innerHTML, una crida amb valors va dins
 * d'escapeHtml(), perquè els valors (el nom d'un compte, un concepte del banc)
 * no són de confiança. Les traduccions no poden portar < > " & ni `, de manera
 * que t('clau') sense valors sí que es pot posar tal qual. Ho vigila i18n.test.js.
 */
export function t(key, values = {}) {
    let template = messages.get(key);
    if (typeof values.count === 'number') {
        const category = new Intl.PluralRules(getLocale()).select(values.count);
        template = messages.get(`${key}.${category}`) ?? messages.get(`${key}.other`) ?? template;
    }
    if (template === undefined) {
        // Una clau que falta és un error del codi: la prova de navegador falla
        // amb qualsevol error de la consola, i així no passa desapercebuda.
        if (messages.size) console.error(`Falta la traducció de «${key}» (${currentLanguage})`);
        return key;
    }
    return template.replace(/\{(\w+)\}/g, (placeholder, name) =>
        Object.hasOwn(values, name) ? String(values[name]) : placeholder);
}

/**
 * Tradueix l'HTML fix (index.html): data-i18n posa el text de l'element i
 * data-i18n-title el seu title. També diu al navegador en quin idioma és la
 * pàgina, que és el que fan servir els lectors de pantalla.
 */
export function translatePage(root = document) {
    root.querySelectorAll('[data-i18n]').forEach(element => {
        element.textContent = t(element.dataset.i18n);
    });
    root.querySelectorAll('[data-i18n-title]').forEach(element => {
        element.title = t(element.dataset.i18nTitle);
    });
    document.documentElement.lang = currentLanguage;
}

/** Un número amb el format de l'idioma: 1.234,5 en català, 1,234.5 en anglès. */
export function formatNumber(value, options = {}) {
    const number = Number(value);
    return new Intl.NumberFormat(getLocale(), { useGrouping: 'always', ...options })
        .format(Number.isFinite(number) ? number : 0);
}

/** Un percentatge que ja ve en tant per cent (12.5 → «12,5 %» en català, «12.5%» en anglès). */
export function formatPercent(value, fractionDigits = 1) {
    return formatNumber(Number(value) / 100, {
        style: 'percent', minimumFractionDigits: fractionDigits, maximumFractionDigits: fractionDigits
    });
}

/**
 * Una data amb el format de l'idioma. Accepta una Date o el text «2026-10-16»
 * de l'API, que es llegeix com a dia local: new Date('2026-10-16') és la
 * mitjanit UTC, i a l'oest de Greenwich sortia el dia anterior.
 */
export function formatDate(value, options = { day: '2-digit', month: '2-digit', year: 'numeric' }) {
    const date = toDate(value);
    return date ? new Intl.DateTimeFormat(getLocale(), options).format(date) : '';
}

/** Un mes amb l'any: «octubre del 2026», «octubre de 2026», «October 2026». month va d'1 a 12. */
export function formatMonth(year, month) {
    return new Intl.DateTimeFormat(getLocale(), { month: 'long', year: 'numeric' }).format(new Date(year, month - 1, 1));
}

/** El nom d'un mes sol, en majúscula com en un desplegable: «Octubre», «October». month va d'1 a 12. */
export function formatMonthName(month) {
    const name = new Intl.DateTimeFormat(getLocale(), { month: 'long' }).format(new Date(2000, month - 1, 1));
    return name.charAt(0).toLocaleUpperCase(getLocale()) + name.slice(1);
}

/** De quin mes a quin: «octubre–diciembre de 2026», «October – December 2026». */
export function formatMonthRange(startYear, startMonth, endYear, endMonth) {
    return new Intl.DateTimeFormat(getLocale(), { month: 'long', year: 'numeric' })
        .formatRange(new Date(startYear, startMonth - 1, 1), new Date(endYear, endMonth - 1, 1));
}

function toDate(value) {
    if (value instanceof Date) return Number.isNaN(value.getTime()) ? null : value;
    if (!value) return null;
    const dayOnly = String(value).match(/^(\d{4})-(\d{2})-(\d{2})$/);
    const date = dayOnly ? new Date(Number(dayOnly[1]), Number(dayOnly[2]) - 1, Number(dayOnly[3])) : new Date(value);
    return Number.isNaN(date.getTime()) ? null : date;
}
