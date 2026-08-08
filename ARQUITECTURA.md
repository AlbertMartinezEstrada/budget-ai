# Cómo funciona Budget AI

Documento para entender el sistema por dentro. Si solo quieres levantarlo, con
el [README](README.md) tienes bastante.

## Las tres piezas

```
navegador  ──►  frontend :3000  (Express sirve HTML/JS/CSS estáticos)
    │
    └────────►  backend :8000   (Spring Boot, API REST)
                     │
                     ├──►  PostgreSQL :5432
                     └──►  Google Gemini (clasificación de movimientos)
```

Los tres corren como contenedores separados en `docker-compose.yml`.

**El frontend no hace de intermediario.** Express solo sirve ficheros
estáticos; el navegador llama directamente a la API del puerto 8000. Por eso
hay CORS de por medio, y por eso el frontend no puede guardar secretos: todo lo
que sabe, lo sabe el navegador.

## El frontend

JavaScript sin framework, con módulos ES nativos. No hay compilación ni paso de
build: los ficheros de `frontend/public/` se sirven tal cual.

```
public/
  index.html          esqueleto: barra lateral, cabecera, <main id="main-content">
  css/                estilos propios (main, layout, components)
  js/
    api.js            TODA la comunicación con el backend
    app.js            arranque, routing y navegación
    features/
      dashboard/  transactions/  upload/  accounts/  budgets/
      categories/  goals/  transfers/  recurring/  analytics/
      settings/  auth/
```

### Cómo se renderiza una vista

Cada carpeta de `features/` exporta una función `init<Vista>(container)` que
escribe HTML dentro de `#main-content` y engancha sus propios listeners. No hay
componentes ni estado compartido: cada vista se monta de cero cada vez.

### Routing

La vista vive en el *hash* de la URL (`#accounts`, `#analytics`). `app.js`
escucha `hashchange` y llama a la `init` correspondiente. Esto hace que
recargar mantenga la pantalla, que el botón de atrás funcione y que los enlaces
se puedan compartir.

Los clics de navegación se capturan **por delegación en `document`**, no
enganchando listeners a cada enlace. Es importante: los botones creados dentro
de una vista (el "Veure tot" del tablero, por ejemplo) no existen cuando la
página carga, así que un listener puesto al arrancar nunca los alcanzaría.

### `api.js` es la única puerta de salida

Ninguna vista llama a `fetch` directamente. Todas pasan por `apiFetch`, que:

- añade la URL base del backend (derivada de `window.location`, no escrita a mano),
- añade `credentials: 'include'` para que viaje la cookie de sesión,
- y centraliza el tratamiento de errores.

Un `401` en cualquier llamada dispara un aviso que devuelve al usuario a la
pantalla de entrada, sin que cada vista tenga que comprobarlo.

`api.js` exporta además dos ayudas que se usan en todas partes:

- **`formatCurrency(importe)`** — tolera `null`, `undefined` y cadenas. Antes
  no, y `undefined.toFixed()` tumbaba rejillas enteras.
- **`escapeHtml(texto)`** — obligatorio antes de meter cualquier dato en HTML.
  Los nombres de empresa vienen del CSV del banco y de la respuesta de Gemini:
  no son datos de confianza.

## El backend

Spring Boot con la estructura habitual:

```
controller/   rutas HTTP
service/      lógica de negocio
repository/   acceso a datos (Spring Data JPA)
model/        entidades JPA
security/     sesión y filtros
config/       CORS
```

### Los nombres de los campos JSON

Esto es lo más fácil de romper del proyecto y conviene entenderlo.

Las entidades usan `@JsonProperty` para exponer nombres en catalán y
`snake_case`, distintos de los nombres de los campos Java:

| Entidad | Campo Java | JSON |
|---|---|---|
| Account | `currentBalance` | `saldo_actual` |
| Transaction | `amount` | `cost` |
| FinancialGoal | `targetAmount` | `quantitat_objectiu` |
| Transfer | `amount` | `import` |

**Dos excepciones que hay que recordar:**

1. **`Settings` no lleva `@JsonProperty`**, así que se serializa en camelCase
   (`userName`, `notificationsExpenses`).
2. **`Transfer` expone las cuentas como `sourceAccount` y
   `destinationAccount`** — los nombres de campo Java, no los de las columnas
   (`account_origen_id`).

Un campo mal escrito en el frontend **no da error**: en JavaScript es
`undefined`, y se renderiza como cero o rompe en silencio. Por eso existe
`JsonContractTest`, que fija estos nombres. Si lo tocas, tienes que tocar
también el frontend que lo lee.

### El dinero

Todos los importes son `BigDecimal` en Java y `NUMERIC(15,2)` en PostgreSQL.

No es un detalle estético. Con `double`, un saldo real de la base de datos era
`112.97000000000018` y un balance `28.25999999999999`. Las operaciones usan
`add`, `subtract` y `compareTo`; nunca `==` ni aritmética de coma flotante.

Los valores por defecto (`saldo_actual = 0`, `moneda = EUR`, `activa = true`)
se aplican en **`@PrePersist`**, no como inicializadores de campo. La razón es
sutil: Jackson construye la entidad con el constructor vacío y luego asigna
solo lo que venga en la petición. Si el campo tuviera un valor por defecto en
la declaración, una actualización parcial llegaría con ese valor —no con
`null`— y sería imposible distinguir "no me han enviado este campo" de "me lo
quieren poner a cero". Editar una cuenta le borraba el saldo por esto.

### Operaciones que mueven dinero

Tres sitios tocan saldos, y los tres son `@Transactional`:

| Operación | Qué hace |
|---|---|
| `POST /transfers` | Resta del origen, suma al destino, guarda la transferencia |
| `DELETE /transfers/{id}` | **Revierte** el movimiento y borra la fila |
| `POST /confirm-upload` | Guarda los movimientos y ajusta el saldo de la cuenta |

Detalle importante: dentro de estos métodos las excepciones se **relanzan**, no
se capturan para devolver un `ResponseEntity`. Capturarlas impediría el
rollback, que es justamente lo que hace falta cuando el dinero ya se ha movido.

## La importación de extractos

```
CSV ──► BankReaderService ──► filtro por hash ──► Gemini ──► pantalla de
        (parsea y hashea)     (descarta los ya      (clasifica)   revisión
                               importados)                           │
                                                                     ▼
                         base de datos  ◄──  /confirm-upload  ◄── el usuario
                         (+ ajuste de saldo)   (revalida hashes)     confirma
```

### El parser de importes

Detecta el separador decimal mirando cuál de los dos (`.` o `,`) aparece más a
la derecha. Suena rebuscado, pero la versión anterior borraba todos los puntos
y convertía `"45.30"` en `4530`: un error de ×100 en cualquier extracto en
formato anglosajón.

Formatos soportados: `-45.30`, `-1.234,56`, `-2,500.75`, `-80`, `1.500,00 EUR`.

Un importe ilegible **aborta la importación**. Antes se guardaba como cero en
silencio, que es peor que fallar.

### El hash de duplicados

Cada movimiento lleva un SHA-256 de `fecha + concepto + importe + saldo`. Se
comprueba dos veces: al subir el fichero y otra vez al confirmar. La segunda es
la que importa — entre ambos momentos el usuario puede hacer doble clic o
reintentar, y sin ella se duplicaban movimientos y se volvía a restar del saldo.
La columna además tiene una restricción de unicidad en la base de datos, como
última barrera.

### Qué puede decidir la IA, y qué no

Gemini recibe la lista de movimientos y devuelve empresa, categoría y
descripción. **Solo eso se aplica.** El importe, la fecha, el tipo y el hash se
conservan siempre del CSV original.

Si la IA devuelve un número de filas distinto del que se le envió, el
emparejamiento por posición deja de ser fiable y **se descarta la clasificación
entera**, conservando los movimientos originales. Antes, en ese caso se
guardaban las transacciones inventadas por la IA: sin hash, sin tipo y con el
importe que ella dijera.

Sin `GEMINI_API_KEY`, la importación funciona igual pero sin clasificar.

## La sesión

Resumen; el detalle está en [AUTENTICACION.md](AUTENTICACION.md).

Un JWT firmado con HMAC-SHA256 dentro de una cookie `budget_session` que es
**httpOnly** (JavaScript no puede leerla, así que un XSS no puede robarla) y
**SameSite=Strict** (no viaja en peticiones iniciadas desde otro sitio, que es
la protección contra CSRF).

El frontend y el backend son el mismo *site* aunque estén en puertos distintos
—el puerto no cuenta para el cálculo del *site*—, así que la cookie sí viaja en
las llamadas normales. Como sí son orígenes distintos, CORS necesita
`allowCredentials`, y eso obliga a una lista concreta de orígenes: con `*` el
navegador lo rechazaría.

Todo requiere sesión salvo `/auth/login` y `/auth/logout`.

## El esquema

Nueve tablas. `accounts`, `transactions`, `categories`, `companies`, `budgets`,
`financial_goals`, `recurring_transactions`, `transfers` y `settings`.

`ddl-auto` está en **`validate`**: Hibernate comprueba al arrancar que las
tablas cuadren con las entidades y falla si no. No genera ni modifica nada.

Esto tiene una consecuencia práctica: **`init.sql` es la fuente de verdad para
instalaciones nuevas** y tiene que mantenerse al día a mano. Los tests de
integración levantan la base de datos desde ese mismo fichero, así que
cualquier divergencia hace fallar toda la suite — que es como se detectó que a
`init.sql` le faltaba la tabla `settings` y que todas las claves primarias eran
`SERIAL` cuando las entidades usan `Long`.

Para cambiar el esquema en una instalación existente hay que añadir un fichero
en `backend-java/migrations/` y aplicarlo a mano:

```bash
docker exec -i budget_db psql -U "$DB_USER" -d "$DB_NAME" < backend-java/migrations/001_....sql
```

## Integración continua

`.github/workflows/ci.yml` ejecuta cuatro trabajos en paralelo en cada push a
`main` y en cada pull request:

| Trabajo | Qué hace |
|---|---|
| Backend · unitarios | `./gradlew test` |
| Backend · integración | `./gradlew integrationTest` (Testcontainers) |
| Frontend | `npm ci && npm test` |
| Imágenes | Construye los dos Dockerfile |

Los tests de integración están separados porque necesitan Docker; los unitarios
tienen que poder ejecutarse siempre.

El trabajo de imágenes existe porque el `Dockerfile` puede romperse sin que
ningún test se entere: de hecho, encontró que `gradle build` arrastraba los
tests de integración dentro de la construcción de la imagen y la rompía.

## Cosas que sorprenden

Recopilación de detalles que cuestan tiempo si no se saben:

- **El backend escucha en el 8000**, no en el 8080. El `EXPOSE 8080` del
  Dockerfile es residual.
- **Los ficheros estáticos se sirven con `Cache-Control: no-cache`.** Sin eso el
  navegador se quedaba con el CSS y el JavaScript antiguos, y parecía que los
  cambios no se aplicaban.
- **Tailwind viene por CDN** y genera las clases mirando el DOM. Funciona con
  contenido inyectado dinámicamente, pero **no puede generar clases construidas
  en tiempo de ejecución**: `bg-${color}-500` no existe. Hay que enumerarlas.
- **El tema oscuro tiene dos mitades**: las variables CSS de `main.css` bajo
  `html.dark`, y las variantes `dark:` de Tailwind en las vistas. Si tocas una,
  mira la otra.
- **`gradlew` y `package-lock.json` sí se versionan.** Estuvieron ignorados un
  tiempo, lo que impedía compilar tras clonar.
