/**
 * De quin navegador i sistema és una sessió, a partir del User-Agent.
 *
 * Configuració ensenya les sessions obertes, i el User-Agent sencer
 * («Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/…») no diu
 * res a ningú: «Safari · macOS» sí. Només surten noms d'aquestes llistes, mai
 * un tros del text rebut.
 *
 * L'ordre importa: el d'Edge diu també «Chrome» i «Safari», el de Chrome diu
 * «Safari», el d'Android diu «Linux» i el d'iPhone diu «Mac OS X».
 */
const BROWSERS = [
    { name: 'Edge', pattern: /\bEdg(e|A|iOS)?\// },
    { name: 'Opera', pattern: /\bOPR\/|\bOpera\b/ },
    { name: 'Samsung Internet', pattern: /\bSamsungBrowser\// },
    { name: 'Firefox', pattern: /\bFirefox\/|\bFxiOS\// },
    { name: 'Chrome', pattern: /\bChrome\/|\bCriOS\// },
    { name: 'Safari', pattern: /\bVersion\/[\d.]+.*\bSafari\// }
];

const SYSTEMS = [
    { name: 'iOS', pattern: /\b(iPhone|iPad|iPod)\b/ },
    { name: 'Android', pattern: /\bAndroid\b/ },
    { name: 'ChromeOS', pattern: /\bCrOS\b/ },
    { name: 'Windows', pattern: /\bWindows\b/ },
    { name: 'macOS', pattern: /\bMac OS X\b|\bMacintosh\b/ },
    { name: 'Linux', pattern: /\bLinux\b/ }
];

/** { browser, system }, cadascun amb el seu nom o null si no se sap. */
export function describeDevice(userAgent) {
    const text = String(userAgent || '');
    const find = (candidates) => candidates.find(candidate => candidate.pattern.test(text))?.name || null;
    return { browser: find(BROWSERS), system: find(SYSTEMS) };
}
