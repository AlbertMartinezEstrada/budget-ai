# 🏗️ ¿Cómo funciona la arquitectura de este Frontend? (SPA vs MPA)

Es normal que te sorprenda ver que no hay archivos `.html` para cada página (como `dashboard.html`, `transacciones.html`, etc.). Lo que hemos implementado es una **Single Page Application (SPA)**.

Aquí te explico la diferencia y cómo funciona "la magia".

---

## 1. El modelo Clásico (Multi-Page Application - MPA)

En el modelo tradicional que comentas, cada vez que el usuario hace clic en un enlace, el navegador **pide un archivo nuevo al servidor**.

*   **Usuario:** Clic en "Transacciones".
*   **Navegador:** Pide `transacciones.html` al servidor.
*   **Servidor:** Envía el archivo.
*   **Navegador:** **Recarga toda la página**, borra la memoria, vuelve a cargar CSS, JS y pinta el nuevo HTML.

**Desventajas:** Es más lento, hay un "parpadeo" blanco entre páginas y no se siente como una aplicación fluida.

---

## 2. El modelo Moderno (Single Page Application - SPA)

En este modelo (el que hemos hecho), **solo existe un archivo HTML real**: `index.html`.

### ¿Cómo funciona?

Imagina que tu web es un **marco de un cuadro**.
*   El **Marco** (Sidebar y Header) es fijo, nunca se mueve ni se recarga.
*   El **Lienzo** (El área central `main-content`) es lo único que cambiamos.

Cuando haces clic en "Transacciones":
1.  **No recargamos la página.**
2.  Javascript (`app.js`) detecta el clic.
3.  Javascript **borra** lo que hay en el área central.
4.  Javascript **inyecta** el nuevo HTML (que está guardado dentro de `TransactionList.js`).

### 🧩 Las Piezas del Puzzle

#### A. El Esqueleto (`index.html`)
Solo tiene el contenedor vacío donde se pintarán las cosas:
```html
<!-- index.html -->
<body>
    <nav class="sidebar">...</nav> <!-- Esto siempre está visible -->
    
    <main>
        <!-- AQUÍ ES DONDE OCURRE LA MAGIA -->
        <div id="main-content"></div> 
    </main>
    
    <script type="module" src="js/app.js"></script>
</body>
```

#### B. El Cerebro (`app.js`)
Es el encargado de decidir qué se muestra. Actúa como un "Router" (Enrutador).

```javascript
// app.js (simplificado)
import { initDashboard } from './features/dashboard/Dashboard.js';
import { initTransactions } from './features/transactions/TransactionList.js';

function navigate(vista) {
    const contenedor = document.getElementById('main-content');
    
    // 1. Borrar contenido actual
    contenedor.innerHTML = ''; 

    // 2. Cargar la nueva vista según el botón pulsado
    if (vista === 'dashboard') {
        initDashboard(contenedor);
    } else if (vista === 'transactions') {
        initTransactions(contenedor);
    }
}
```

#### C. Las Vistas (`Dashboard.js`, `TransactionList.js`)
Aquí es donde entra tu duda. **¿Por qué son JS y no HTML?**
Porque necesitamos que sean **componentes reutilizables** que tengan lógica y vista juntas.

En lugar de escribir HTML estático, escribimos una función que **genera** ese HTML:

```javascript
// Dashboard.js
export function initDashboard(contenedor) {
    // 1. Pintamos el HTML (Template String)
    contenedor.innerHTML = `
        <h1>Bienvenido al Dashboard</h1>
        <div class="stats">...</div>
    `;

    // 2. Añadimos la lógica (Eventos, llamadas a API)
    // Esto antes lo hacías en un <script> al final del body, 
    // ahora vive junto a su HTML.
    fetchDatosDeLaAPI();
}
```

---

## 3. ¿Por qué hacerlo así?

1.  **Velocidad:** La página nunca se recarga. La navegación es instantánea.
2.  **Experiencia de Usuario (UX):** Se siente como una app nativa (como Spotify o Slack), no como una web antigua.
3.  **Organización (Screaming Architecture):**
    *   Antes tenías el HTML en una carpeta y el JS en otra. Si querías cambiar el botón de "Subir Archivo", tenías que abrir dos archivos en carpetas distintas.
    *   Ahora, todo lo relacionado con "Subir Archivo" (su HTML, su lógica y sus estilos específicos) vive en `features/upload/`.

## Resumen

*   **`index.html`**: El escenario vacío.
*   **`app.js`**: El director de la obra que dice quién sale a escena.
*   **`Feature.js`**: Los actores. Traen su propio vestuario (HTML) y guion (Lógica JS) y se ponen en el escenario cuando el director los llama.
