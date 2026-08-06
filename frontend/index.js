// Mòduls ES, igual que el codi del navegador: així el paquet té un sol
// sistema de mòduls i els tests poden importar public/js/api.js directament.
import express from 'express';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const app = express();
const PORT = 3000;

app.use((req, res, next) => {
    res.header('Access-Control-Allow-Origin', '*');
    res.header('Access-Control-Allow-Headers', 'Origin, X-Requested-With, Content-Type, Accept');
    next();
});

// Servim els fitxers de la carpeta 'public'.
// Sense capçaleres de memòria cau, el navegador es queda amb la versió antiga
// del CSS i del JavaScript després de cada canvi. Amb "no-cache" el navegador
// revalida sempre contra el servidor (segueix aprofitant l'ETag quan no hi ha
// hagut cap canvi, així que no es descarrega res de més).
app.use(express.static(path.join(__dirname, 'public'), {
    etag: true,
    setHeaders: (res) => {
        res.setHeader('Cache-Control', 'no-cache');
    }
}));

app.get('/', (req, res) => {
    res.sendFile(path.join(__dirname, 'public', 'index.html'));
});

// Aquesta part és la clau: fa que el procés NO s'aturi
app.listen(PORT, '0.0.0.0', () => {
    console.log(`Frontend corrent a http://localhost:${PORT}`);
});