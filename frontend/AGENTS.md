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
npm test        # 17 tests, sin dependencias externas
npm ci          # instalar exactamente las versiones del lockfile
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

**Tailwind viene por CDN** y genera las clases mirando el DOM. Funciona con
contenido inyectado, pero no puede generar `bg-${color}-500`: enumera las
clases enteras.

**Los campos que se leen de la API** siguen la convención en catalán
(`saldo_actual`, `quantitat_objectiu`, `cost`), con las dos excepciones que
explica `../AGENTS.md`. Antes de inventarte un nombre, míralo en la entidad o
llama al endpoint.
