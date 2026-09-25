# Frontend · contexto para agentes de IA

Las reglas generales están en [`../AGENTS.md`](../AGENTS.md). **Léelas antes que
esto**: `apiFetch`, `escapeHtml`, la prohibición de `onclick` y las clases de
Tailwind son las que más se rompen.

## Qué es

JavaScript **sin framework**, con módulos ES nativos. **No hay compilación**:
los ficheros de `public/` se sirven tal cual y basta con recargar el navegador.

Express solo sirve estáticos. **No hace de intermediario**: el navegador llama
directamente a la API del puerto 8000. Por eso hay CORS, y por eso el frontend
no puede guardar secretos.

El paquete es **ESM** (`"type": "module"`), no CommonJS.

## Estructura

```
index.js              servidor de estáticos (ESM, con Cache-Control: no-cache)
public/
  index.html          barra lateral, cabecera, <main id="main-content">
  css/                main (variables y tema oscuro), layout, components
  js/
    api.js            TODA la comunicación con el backend
    app.js            arranque, sesión, routing por hash, navegación
    features/<vista>/ una carpeta por vista, con init<Vista>(container)
test/                 tests con el ejecutor integrado de Node
```

## Comandos

```bash
npm ci                 # instalar exactamente las versiones del lockfile
npm test               # 22 tests (hace falta npm ci antes: compilan Tailwind)
npm run build:assets   # regenerar tailwind.css y las fuentes de public/vendor
```

`npm test` es `node --test` sin argumentos, a propósito: pasarle una ruta
funciona en Node 18 pero falla desde Node 20.

## Cómo se añade una vista

1. Crea `features/<nombre>/<Nombre>.js` que exporte `init<Nombre>(container)`.
2. Impórtala en `app.js` y añádela **al `switch` y a la constante `VIEWS`**. Si
   falta en `VIEWS`, el routing por hash la ignora en silencio; hay un test que
   lo comprueba.
3. Añade el enlace en `index.html` con `data-view="<nombre>"`.

Cada vista escribe su HTML en `#main-content` y engancha sus propios listeners.
No hay estado compartido: se monta de cero cada vez.

## Detalles del frontend

**La navegación va por delegación en `document`.** Los botones creados dentro de
una vista no existen al cargar la página, así que un listener puesto al arrancar
nunca los alcanzaría. Ya pasó con el botón "Veure tot" del tablero, que no hacía
nada.

**La sesión se comprueba antes de cargar nada.** Sin sesión se muestra la
pantalla de entrada y no se pide ni un dato. Un `401` en cualquier llamada
devuelve a esa pantalla automáticamente.

**El tema oscuro tiene dos mitades**: las variables CSS bajo `html.dark` en
`main.css` para los componentes propios, y las variantes `dark:` de Tailwind en
las vistas. Si tocas una, mira la otra.

**Nada viene de un CDN.** Tailwind, la fuente Inter y los iconos (Material
Symbols) se sirven desde `public/`: un script de terceros tiene acceso a toda la
página, y sin internet la app se quedaba sin estilos. Un test de
`contract.test.js` falla si `index.html` o los CSS cargan algo de otro dominio.

**Tailwind está compilado y guardado en el repositorio**
(`public/css/tailwind.css`), porque el frontend se sirve desde disco tal cual,
sin paso de construcción. La configuración está en `tailwind.config.js`.

- **Si usas una clase de Tailwind que no se usaba en ningún otro sitio, ejecuta
  `npm run build:assets`** y sube el `tailwind.css` que genera. Si no, la clase
  no tiene estilo y no sale ningún error. `test/assets.test.js` lo comprueba y
  falla si el CSS guardado no coincide con el que se generaría.
- Tailwind solo genera las clases que encuentra escritas enteras en
  `index.html` y `js/`. `bg-${color}-500` no sale en el CSS: enumera las clases.
- Las fuentes de `public/vendor/` se copian de `node_modules` con el mismo
  comando; el mismo test comprueba que coinciden con las versiones instaladas.

**Los iconos son de Material Symbols**: `<span class="material-symbols-outlined">nombre</span>`.
No uses otra librería de iconos.

**Los campos que se leen de la API** siguen la convención en catalán
(`saldo_actual`, `quantitat_objectiu`, `cost`), con las dos excepciones que
explica `../AGENTS.md`. Antes de inventarte un nombre, míralo en la entidad o
llama al endpoint.
