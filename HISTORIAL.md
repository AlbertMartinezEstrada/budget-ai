# Historial de correcciones

Resumen de una tanda de saneamiento del proyecto: ocho pull requests, de
`#1` a `#8`. Se documenta aquí porque varios de los fallos eran invisibles —no
petaba nada, simplemente los números estaban mal— y saber que existieron ayuda
a no reintroducirlos.

---

## Punto de partida: dos historiales de git incompatibles

El repositorio local y `origin/main` **no compartían ningún ancestro**. En su
día se había rehecho el repositorio en GitHub para eliminar una API key
filtrada (commits `Commit inicial seguro` y `quit api key`), pero el clon local
se había quedado con el historial antiguo.

El local tenía 845 ficheros y trackeaba `.env`, `.idea/`, `node_modules/` y la
clave de Gemini en claro dentro de `ai_engine.py`. El remoto tenía 87 ficheros,
estaba limpio y era **más nuevo**.

Se alineó el local con el remoto, conservando el historial viejo en una rama de
respaldo que se borró más tarde. Publicar el local habría requerido un
`--force` y habría vuelto a filtrar la clave.

---

## PR #1 — `.gitignore`

`.idea/` y `node_modules/` ya estaban ignorados, pero seguían trackeados porque
se habían commiteado antes de existir la regla. Se amplió el fichero con lo que
faltaba: `.env.*` (el patrón `*.env` no cubre `.env.local`), `uploads/`,
`*.class`, logs y ficheros de Eclipse.

También se corrigió el anclaje de las negaciones del wrapper de Gradle: estaban
como `!gradle/wrapper/...`, y al llevar barra git las ancla a la raíz del
repositorio, así que nunca coincidían con `backend-java/gradle/wrapper/...`.

---

## PR #2 — Contratos JSON

**El origen de la mayoría de fallos graves: el frontend leía campos que el
backend no ha producido nunca.** Un campo inexistente en JavaScript es
`undefined`, no un error, así que la interfaz mostraba ceros y rejillas vacías
sin que nada fallara visiblemente.

### La página de Análisis no funcionaba entera

| Se leía | La API devuelve | Efecto |
|---|---|---|
| `total_expenses` | `total_expense` | Gastos siempre a 0,00 €, ahorro siempre 100% |
| `item.month`, `income`, `expenses` | `period`, `total_income`, `total_expense` | Gráfico de tendencia vacío |
| `cat.category.nom` | `category` (cadena) | Todas las barras decían "Sin categoría" |

Con datos reales, febrero pasó de mostrar 0,00 € de gastos a 262,95 €.

### Objetivos financieros no funcionaba ni para crear ni para listar

El formulario enviaba `name` / `target_amount` / `deadline`; el modelo espera
`nom` / `quantitat_objectiu` / `data_objectiu`, y los dos primeros son
`NOT NULL`. Jackson descartaba lo desconocido y la inserción daba **500**.

Al listar, `formatCurrency(undefined)` lanzaba un `TypeError` que tumbaba la
rejilla entera, no solo una tarjeta.

### Además

- `formatCurrency` pasó a tolerar `null`, `undefined` y cadenas.
- Se añadió `escapeHtml`.
- Los errores de la API se leían siempre como JSON, pero varios endpoints
  responden texto plano: **todos los errores de subida llegaban a la interfaz
  como "Error desconegut"**, lo que ocultaba el resto de problemas.

---

## PR #3 — Integridad, seguridad y navegación

### Dinero

- **Borrar una transferencia no revertía los saldos.** Solo se borraba la fila:
  descuadre permanente e invisible.
- `createTransfer` y `confirmUpload` **no eran atómicos**: movían dinero y
  guardaban después. Un error a medias dejaba saldos tocados sin los
  movimientos que los justificaran.
- `confirmUpload` **no revalidaba los hashes**: confirmar dos veces el mismo
  lote duplicaba movimientos y volvía a restar del saldo.
- Los importes eran `Double`. El saldo real en base de datos era
  `112.97000000000018`. Se migraron a `BigDecimal` y `NUMERIC(15,2)`.
- `cleanNumber` borraba todos los puntos: **`"45.30"` se convertía en `4530`**,
  un error de ×100 en cualquier extracto en formato anglosajón.
- El merge de la IA emparejaba por posición sin comprobar tamaños. Si Gemini
  devolvía otro número de filas, se guardaban **movimientos sin hash, sin tipo
  y con el importe que dijera la IA**.

### Seguridad

- Los ocho controllers tenían `@CrossOrigin(origins = "*")`: cualquier web
  abierta en el navegador podía leer y borrar los datos financieros.
- Escapado de HTML en todas las vistas.
- Los `onclick` con datos interpolados pasaron a delegación de eventos. Una
  cuenta llamada `O'Brien` rompía la fila entera.
- Las credenciales salieron de `application.properties`.

### Interfaz

- **Routing por hash**: recargar mantiene la pantalla y el botón de atrás
  funciona.
- El botón "Veure tot" del tablero **no hacía nada**: los listeners se
  enganchaban una sola vez al cargar y ese botón se crea después.
- **El modo oscuro no oscurecía nada**: no había ninguna regla que respondiera a
  la clase `dark`, y `main.css` tenía `background-color: --bg-body` sin `var()`.
- Se implementó el arrastrar y soltar, que la interfaz anunciaba y no existía.
- Si la IA proponía una categoría fuera de la lista, el navegador seleccionaba
  la primera y el movimiento se guardaba como "Menjar i supermercat".
- `Cache-Control: no-cache` en los ficheros estáticos.

### Encontrado al verificar

- **Crear una transferencia desde la interfaz no había funcionado nunca**: el
  formulario enviaba `account_origen_id` (nombre de columna) cuando el modelo
  espera `sourceAccount`.
- Budgets y Recurring llamaban a `http://localhost:8000` a pelo.

---

## PR #4 — Documento del portal

Se versionó `BUDGET_AI_PORTAL.md` y se añadió `notion_update.py` al
`.gitignore`: contiene un token de Notion en claro y había estado a punto de
entrar en un commit dos veces.

---

## PR #5 — Tests unitarios

58 tests. El más valioso es `JsonContractTest`, que fija los nombres de las
propiedades JSON: es la clase de fallo que había causado casi todo lo grave.

**Los tests encontraron que el arreglo de actualizaciones parciales del PR #3
no funcionaba.** Las entidades tenían valores por defecto en los campos
(`currentAmount = BigDecimal.ZERO`), así que un cuerpo parcial nunca producía
`null` y la comprobación `if (campo != null)` no protegía nada. Editar un
objetivo ponía a cero el dinero ahorrado.

El diagnóstico que se había dado en el PR #3 era además **incorrecto**: se dijo
que los campos quedaban a `null`, cuando quedaban con el valor por defecto. Los
valores por defecto se movieron a `@PrePersist`.

---

## PR #6 — Autenticación

Toda la API estaba abierta. Se cerró detrás de un inicio de sesión de un solo
usuario, con JWT en cookie httpOnly y `SameSite=Strict`.

Protecciones: mensaje de error único que no revela qué campo falló, tiempo de
respuesta constante, y bloqueo tras cinco intentos fallidos.

Ver [AUTENTICACION.md](AUTENTICACION.md).

---

## PR #7 — Tests de integración

26 tests con PostgreSQL real vía Testcontainers, cubriendo el comportamiento
transaccional que hasta entonces solo se había verificado a mano.

**Encontraron dos errores de esquema que impedían arrancar una instalación
nueva**, ambos invisibles en la instalación existente porque las tablas las
había creado Hibernate cuando `ddl-auto` era `update`:

1. `init.sql` no creaba la tabla `settings`.
2. Todas las claves primarias eran `SERIAL` (entero) cuando las entidades usan
   `Long`, y las foráneas `INTEGER` en vez de `BIGINT`.

Con el cambio a `ddl-auto=validate` del PR #3, cualquiera de los dos impedía
arrancar tras un clon limpio. Con H2 no se habrían detectado.

---

## PR #8 — Integración continua

Cuatro trabajos en GitHub Actions.

**Destapó una regresión ya mergeada**: el PR #7 hizo que `check` dependiera de
`integrationTest`, y el Dockerfile ejecutaba `gradle build`, que arrastra
`check`. La construcción de la imagen intentaba levantar PostgreSQL con
Testcontainers desde dentro del build de Docker. **`docker compose up --build`
estuvo roto** desde ese commit. Se cambió a `gradle assemble`.

También se sacaron del `.gitignore` `gradlew`, `gradlew.bat` y
`package-lock.json`: hasta entonces **quien clonara el repositorio no podía
compilar**.

---

## Lecciones que quedan

**Un campo mal escrito no da error.** En JavaScript es `undefined`, y se
renderiza como cero. Es el fallo más caro del proyecto y el más difícil de ver.
`JsonContractTest` existe para eso.

**El estado de tu máquina esconde fallos.** El volumen de la base de datos
tapaba dos errores de esquema; la imagen de Docker ya construida tapaba un
Dockerfile roto. Los dos aparecieron al reproducir desde cero.

**Con H2 no se detectan los problemas de PostgreSQL.** Los tests de integración
usan la base de datos de verdad a propósito.

**Un arreglo verificado a mano puede no funcionar.** Las actualizaciones
parciales se dieron por corregidas en el PR #3 con un diagnóstico equivocado, y
solo un test lo demostró.
