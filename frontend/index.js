// Mòduls ES, igual que el codi del navegador: així el paquet té un sol
// sistema de mòduls i els tests poden importar public/js/api.js directament.
import express from 'express';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const PORT = 3000;

/** El port del backend: la pàgina hi parla des de la mateixa adreça (api.js). */
const BACKEND_PORT = 8000;

/**
 * Les regles que el navegador ha d'aplicar a la pàgina.
 *
 * - Només scripts propis: ni inline ni d'altres dominis. La pàgina no en té
 *   cap, i així un HTML injectat no pot executar res.
 * - Estils: els propis i els atributs style="", que les vistes fan servir.
 * - Imatges data: per a les fletxes i caselles del plugin de formularis.
 * - connect-src: la mateixa adreça i el backend al port 8000, amb http o https
 *   perquè des del mòbil (Tailscale) s'hi entra pel nom del PC.
 * - frame-ancestors 'none': cap altra web la pot posar dins d'un iframe.
 */
export function contentSecurityPolicy(hostname) {
    // El nom ve de la capçalera Host. Si porta res estrany, només es permet
    // la mateixa adreça: millor no connectar que obrir la porta a qualsevol.
    const backend = /^[a-z0-9.-]+$/i.test(hostname || '')
        ? ` http://${hostname}:${BACKEND_PORT} https://${hostname}:${BACKEND_PORT}`
        : '';
    return [
        "default-src 'self'",
        "script-src 'self'",
        "style-src 'self' 'unsafe-inline'",
        "img-src 'self' data:",
        "font-src 'self'",
        `connect-src 'self'${backend}`,
        "object-src 'none'",
        "base-uri 'self'",
        "form-action 'self'",
        "frame-ancestors 'none'"
    ].join('; ');
}

export function createApp() {
    const app = express();

    // Express s'anunciava a cada resposta (X-Powered-By): no cal dir a ningú
    // amb què està fet el servidor.
    app.disable('x-powered-by');

    // Abans aquí hi havia Access-Control-Allow-Origin: *, que deixava
    // qualsevol web llegir els fitxers. No feia falta: la pàgina els demana
    // des de la mateixa adreça.
    app.use((request, response, next) => {
        response.setHeader('Content-Security-Policy', contentSecurityPolicy(request.hostname));
        response.setHeader('X-Content-Type-Options', 'nosniff');
        response.setHeader('X-Frame-Options', 'DENY');
        response.setHeader('Referrer-Policy', 'no-referrer');
        next();
    });

    // Servim els fitxers de la carpeta 'public'.
    // Sense capçaleres de memòria cau, el navegador es queda amb la versió antiga
    // del CSS i del JavaScript després de cada canvi. Amb "no-cache" el navegador
    // revalida sempre contra el servidor (segueix aprofitant l'ETag quan no hi ha
    // hagut cap canvi, així que no es descarrega res de més).
    app.use(express.static(path.join(__dirname, 'public'), {
        etag: true,
        setHeaders: (response) => {
            response.setHeader('Cache-Control', 'no-cache');
        }
    }));

    app.get('/', (request, response) => {
        response.sendFile(path.join(__dirname, 'public', 'index.html'));
    });

    return app;
}

// Només s'engega quan s'executa directament (npm start), no quan un test
// importa createApp.
if (process.argv[1] === fileURLToPath(import.meta.url)) {
    createApp().listen(PORT, '0.0.0.0', () => {
        console.log(`Frontend corrent a http://localhost:${PORT}`);
    });
}
