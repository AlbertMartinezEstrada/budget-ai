import test from 'node:test';
import assert from 'node:assert/strict';
import { describeDevice } from '../public/js/devices.js';

// User-Agents reals de cada navegador, tal com arriben al backend.
const USER_AGENTS = {
    macSafari: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15',
    macChrome: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36',
    windowsEdge: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36 Edg/129.0.2792.79',
    windowsFirefox: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:131.0) Gecko/20100101 Firefox/131.0',
    linuxOpera: 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36 OPR/114.0.0.0',
    iphoneSafari: 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1',
    iphoneChrome: 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/129.0.6668.69 Mobile/15E148 Safari/604.1',
    androidChrome: 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Mobile Safari/537.36',
    androidSamsung: 'Mozilla/5.0 (Linux; Android 14; SM-S921B) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/26.0 Chrome/122.0.0.0 Mobile Safari/537.36',
    chromebook: 'Mozilla/5.0 (X11; CrOS x86_64 14541.0.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36'
};

test('cada navegador i sistema es reconeix, també els que es fan passar per un altre', () => {
    const described = Object.fromEntries(Object.entries(USER_AGENTS)
        .map(([label, userAgent]) => [label, describeDevice(userAgent)]));

    assert.deepEqual(described, {
        macSafari: { browser: 'Safari', system: 'macOS' },
        macChrome: { browser: 'Chrome', system: 'macOS' },
        windowsEdge: { browser: 'Edge', system: 'Windows' },
        windowsFirefox: { browser: 'Firefox', system: 'Windows' },
        linuxOpera: { browser: 'Opera', system: 'Linux' },
        iphoneSafari: { browser: 'Safari', system: 'iOS' },
        iphoneChrome: { browser: 'Chrome', system: 'iOS' },
        androidChrome: { browser: 'Chrome', system: 'Android' },
        androidSamsung: { browser: 'Samsung Internet', system: 'Android' },
        chromebook: { browser: 'Chrome', system: 'ChromeOS' }
    });
});

test('el que no és un navegador conegut no es deixa endevinar ni torna el text rebut', () => {
    assert.deepEqual(describeDevice('curl/8.7.1'), { browser: null, system: null });
    assert.deepEqual(describeDevice(null), { browser: null, system: null });
    assert.deepEqual(describeDevice(''), { browser: null, system: null });
    assert.deepEqual(describeDevice('<img src=x onerror=alert(1)> Windows'), { browser: null, system: 'Windows' });
});
