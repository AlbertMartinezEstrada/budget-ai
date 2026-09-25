// Genera el que abans es baixava de CDNs en cada càrrega de la pàgina:
//
//   public/css/tailwind.css      Tailwind compilat (abans cdn.tailwindcss.com)
//   public/vendor/inter/         la font Inter        (abans Google Fonts)
//   public/vendor/material-symbols/  les icones        (abans Google Fonts)
//
// Es desa al repositori perquè el frontend se serveix des del disc tal qual:
// sense pas de construcció, n'hi ha prou de recarregar. Un test comprova que
// el que hi ha desat coincideix amb el que es generaria ara.
//
//   npm run build:assets
//
// Cal tornar-lo a executar en afegir classes de Tailwind noves.

import { execFileSync } from 'node:child_process';
import { copyFileSync, mkdirSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const modules = path.join(root, 'node_modules');
const vendor = path.join(root, 'public', 'vendor');

export function buildTailwind(outputFile) {
    execFileSync(process.execPath, [
        path.join(modules, 'tailwindcss', 'lib', 'cli.js'),
        '--config', path.join(root, 'tailwind.config.js'),
        '--input', path.join(root, 'src', 'tailwind.css'),
        '--output', outputFile,
        '--minify',
    ], { cwd: root, stdio: 'pipe' });
}

/**
 * Fitxers de font a copiar: [origen dins de node_modules, destí dins de vendor].
 *
 * D'Inter només el pes variable normal (sense cursiva ni opsz), que és l'únic
 * que fa servir la interfície: el paquet sencer són uns 2 MB i en calen 200 KB.
 */
export function vendorFiles() {
    const interCss = readFileSync(path.join(modules, '@fontsource-variable', 'inter', 'index.css'), 'utf8');
    const interFonts = [...interCss.matchAll(/url\(\.\/files\/([^)]+)\)/g)].map(match => match[1]);

    return [
        ['@fontsource-variable/inter/index.css', 'inter/inter.css'],
        ...interFonts.map(file => [`@fontsource-variable/inter/files/${file}`, `inter/files/${file}`]),
        ['material-symbols/outlined.css', 'material-symbols/outlined.css'],
        ['material-symbols/material-symbols-outlined.woff2', 'material-symbols/material-symbols-outlined.woff2'],
    ];
}

function copyVendor() {
    for (const [source, target] of vendorFiles()) {
        const destination = path.join(vendor, target);
        mkdirSync(path.dirname(destination), { recursive: true });
        copyFileSync(path.join(modules, source), destination);
    }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
    const output = path.join(root, 'public', 'css', 'tailwind.css');
    buildTailwind(output);
    copyVendor();
    const size = readFileSync(output).length;
    console.log(`tailwind.css: ${(size / 1024).toFixed(0)} KB · fonts copiades a public/vendor`);
}
