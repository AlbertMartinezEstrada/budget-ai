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
npm test               # 89 tests (hace falta npm ci antes: compilan Tailwind)
npm run build:assets   # regenerar tailwind.css, las fuentes y Chart.js de public/vendor
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

**El tema oscuro tiene tres piezas**:
- Las variables CSS bajo `html.dark` en `main.css` (`--bg-surface`,
  `--bg-input`, `--text-main`…) para los componentes propios. Un componente
  nuevo usa variables, nunca un `white` o un `#f9fafb` fijo: así era como los
  campos salían blancos con el texto claro del tema, ilegibles.
- Las variantes `dark:` de Tailwind en las vistas, que es lo preferible en una
  vista nueva.
- `css/dark.css`, cargado después de Tailwind, que traduce al oscuro las clases
  que las vistas usan sin `dark:` (`bg-white`, `text-gray-900`,
  `text-red-600`…) y da a todos los campos los colores del tema. Va con
  `:where(html.dark)` delante, que no suma especificidad: si una vista pone su
  propio `dark:`, manda la vista. `test/darkMode.test.js` falla si la app usa
  una clase clara de esas que no está traducida: añádela ahí.

La barra lateral es oscura en los dos temas (`--bg-sidebar`), así que el logo y
los enlaces llevan sus colores en `layout.css`, pensados para fondo oscuro, y el
enlace activo se marca con la clase `active`. No le pongas clases de texto del
contenido (`text-slate-600`, `text-primary`): en claro no se leían.

**En claro, los colores de texto son los tonos que se leen sobre blanco**
(4.5:1, WCAG AA): grises desde el 500 (`text-slate-500`, nunca el 400), y los
colores con significado en el 600-700 (`text-red-600`, `text-green-700`,
`text-orange-700`, `text-teal-700`). El 500 del verde o el rojo parece más
vivo, pero se queda en 2.5-3.8:1. Lo mismo para un botón con texto blanco: el
fondo verde es el 700. `test/lightContrast.test.js` falla si entra un color de
texto, una pastilla (`bg-…-100 text-…`) o un botón que no llega; al cambiar
de tono, pon su equivalente en `dark.css` para que el oscuro no cambie.

Para revisar los dos temas no basta con mirar: se comprobó midiendo el
contraste de cada texto y cada campo contra su fondo en todas las vistas y sus
formularios (WCAG AA, 4.5:1), con Playwright, en claro y con la clase `dark`
puesta en `<html>`.

**Nada viene de un CDN.** Tailwind, la fuente Inter, los iconos (Material
Symbols) y Chart.js se sirven desde `public/`: un script de terceros tiene acceso a toda la
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
- Las fuentes y Chart.js de `public/vendor/` se copian de `node_modules` con el
  mismo comando; el mismo test comprueba que coinciden con las versiones
  instaladas. Chart.js va en su versión UMD (`chart.umd.js`, un solo fichero
  sin imports) y deja `Chart` en `window`; `index.html` lo carga con `defer`
  antes de `app.js`.

**Los gráficos (Chart.js) siguen unas reglas fijas**, las de la pestaña General
de Análisis:
- Cada serie tiene siempre el mismo color, de `chartTheme()` en
  `js/generalAnalysis.js`: las cuatro primeras posiciones de una paleta
  validada para daltonismo contra el blanco y contra la superficie oscura. Si
  añades una serie o cambias un color, vuelve a validarla.
- Un solo eje: nunca dos escalas en el mismo gráfico.
- Leyenda siempre que hay dos series o más, tooltip y una tabla con los mismos
  datos («Ver como tabla»). Ninguna cifra depende solo del gráfico.
- En tema oscuro, sus propios colores (`chartTheme(true)`), no una inversión.
- Destruye los gráficos (`chart.destroy()`) antes de volver a pintar.

**Los desplegables de categoría se montan con `categoryOptions()`**
(`js/categoryOptions.js`), nunca a mano. Agrupa por sección y bloque igual que
el presupuesto y solo ofrece hojas; con `groups: true` también los bloques, que
solo valen para un presupuesto o el grupo de una categoría. `sections` y `only`
filtran (las de gasto, las fijas…), y `value` decide si el valor es el id o el
nombre. Los títulos de grupo van en negrita con `select optgroup` en
`components.css`: el preflight de Tailwind se lo quitaba.

**Los iconos son de Material Symbols**: `<span class="material-symbols-outlined">nombre</span>`.
No uses otra librería de iconos.

**Los campos que se leen de la API** siguen la convención en catalán
(`saldo_actual`, `quantitat_objectiu`, `cost`), con las dos excepciones que
explica `../AGENTS.md`. Antes de inventarte un nombre, míralo en la entidad o
llama al endpoint.
