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

## Presupuestos: coste de vida y caja

### Categorías en árbol

`Category` tiene `parent_id`. Una categoría **con** hijos es un **grupo**
("Coche personal"); una **sin** hijos es una **hoja** ("Seguro coche").

**Las transacciones solo se asignan a hojas.** Los grupos existen para agregar.
Si un movimiento colgara de un grupo, se contaría dos veces: una por sí mismo y
otra al sumar sus hijos. El backend rechaza esa asignación al confirmar una
importación.

Las hojas llevan además `tipus_cost`: `FIXED` o `VARIABLE`. En los grupos se
deja a `null`, porque un grupo puede mezclar ambos. **Una hoja con `null` cuenta
como variable**: es el comportamiento que tenían todas las categorías antes de
existir el campo, así que los datos antiguos no cambian de significado.

### Las dos preguntas

Son dos lecturas distintas del mismo mes:

| | Qué responde | Cómo trata un fijo anual de 600 € |
|---|---|---|
| **Coste de vida** | ¿Cuánto me cuesta vivir? | 50 € **todos los meses** |
| **Caja** | ¿Cuánto ha salido de la cuenta? | 600 € **el mes que se cobra**, 0 el resto |

La primera sirve para planificar; la segunda, para cuadrar el banco.

### Cómo se calcula

Para un mes y una hoja:

```
                   FIXED                        VARIABLE
coste de vida      prorrateo del recurrente     gasto real del mes
plan               el mismo prorrateo           límite del presupuesto, si hay
caja               gasto real del mes           gasto real del mes
```

Un grupo **no mide nada por su cuenta**: suma sus hijos, a cualquier
profundidad. La excepción es el plan — si el usuario pone un límite
directamente al grupo, ese límite manda sobre la suma de los hijos, porque es el
techo que ha decidido para el conjunto.

El **prorrateo** sale de `RecurringTransaction`: se lleva el importe a base
anual y se divide entre doce, con dos decimales y `HALF_UP`. Las frecuencias
cortas usan el año real (365 días, 52 semanas) y no aproximaciones como "cuatro
semanas al mes", que dejarían cuatro semanas fuera al año.

Cada nodo trae `carrec_puntual_aquest_mes`. Sirve para entender los picos: sin
esa marca, ver 600 € de caja cuando el coste de vida dice 50 € parece un error.

**Las recurrentes no mueven dinero.** Definen cuánto cuesta algo al mes; el
dinero real lo sigue poniendo la transacción importada del CSV.

### El endpoint

`GET /budgets/monthly-summary?year=&month=` devuelve el árbol con
`cost_vida_pla`, `cost_vida_real`, `caixa_real`, `prorrateig_mensual`,
`carrec_puntual_aquest_mes` y `subcategories` en cada nodo.

Los presupuestos siguen usando `periode_inici`/`periode_fi`, sin campo de mes.
Añadir un `year`/`month` duplicaría estado que ya está en las fechas y abriría
la puerta a que se contradigan; el endpoint recibe el mes por parámetro y
considera vigente cualquier presupuesto cuyo periodo lo solape, así que un
presupuesto trimestral o anual también aparece.

**La interfaz aplica ese mismo filtro.** Sin él, el botón de editar de un
bloque abría el presupuesto de otro mes: la pantalla decía «sin asignar» y el
formulario salía lleno, y el porcentaje se guardaba calculado sobre el bote del
mes que se estaba mirando y no sobre el suyo.

`POST /budgets/copy-previous-month?year=&month=` duplica al mes indicado las
asignaciones del anterior. Como un presupuesto vale para su periodo y nada más,
cada mes empieza en blanco y el reparto habría que rehacerlo entero. Las
categorías que ya tienen asignación en el mes destino no se tocan, así que
llamarlo dos veces no duplica nada. **Se copia el porcentaje tal cual**: el
importe lo recalcula el bote del mes nuevo, que es todo el sentido de repartir
por porcentajes.

### Reparto del sueldo: en cascada, por niveles

El sueldo no se reparte de una vez entre todas las categorías. Baja por
niveles, y **cada nivel se reparte dentro de lo que le ha tocado al de encima**:

```
INGRESOS  ──> nómina, regalos, premios, trabajos puntuales
    │         su suma es lo que hay para repartir
    ├──> FIJOS       importe exacto de cada bloque
    └──> VARIABLES   lo que queda: ingresos − fijos
                     └─> bloques       % del bote de variables
                                       └─> subsecciones  % de su bloque
```

**El sueldo no es la base del presupuesto: es un bloque de ingreso más.** Al
lado puede haber un regalo, un premio o una factura suelta, y todos ensanchan
lo repartible exactamente igual. La cabecera de la pantalla es esa suma.

Cada hoja de ingreso aporta **el mayor entre su previsión y lo que ha entrado**:

| Previsión | Recibido | Aporta | Por qué |
|---|---|---|---|
| 3.300 | 0 | 3.300 | la nómina aún no está importada, pero se sabe que llega |
| 3.300 | 3.400 | 3.400 | lo que ha pasado manda sobre lo que se contaba |
| 0 | 500 | 500 | un regalo no estaba previsto, por definición |

**Tomar el máximo y no la suma** es lo que impide contar dos veces la misma
nómina: la previsión y el movimiento importado son la misma cosa vista dos
veces, no dos ingresos. Sumarlas daría 6.700 € donde hay 3.400.

Si la sección de ingresos está vacía se aplica el **sueldo de referencia** como
respaldo (`total_disponible_origen` dice cuál de los dos manda). Sin él, una
instalación recién montada no tendría nada que repartir y la pantalla se
quedaría muerta hasta dar de alta los bloques de ingreso.

Por eso **un porcentaje no es del sueldo: es del bote del nivel de encima**.
«30% de gastos variables» y «30% del sueldo» son cifras distintas, y antes no
se podían distinguir porque todo se calculaba contra el sueldo.

Un presupuesto sigue fijando su cifra de dos maneras:

- **Importe exacto** (`quantitat_limit`).
- **Porcentaje** (`percentatge`). Si está informado, **manda sobre el
  importe**: se recalcula como bote × porcentaje ÷ 100 en cada consulta, así
  que si cambia el sueldo —o lo asignado al bloque de encima—, se ajusta solo.

`quantitat_limit` sigue siendo `NOT NULL` y guarda el último importe calculado,
para que la tabla se pueda leer por sí sola.

**Cuál es el bote de cada nodo**, por orden:

1. Lo asignado a su padre, si el padre tiene una cifra propia.
2. Si no la tiene, el bote del padre. Sin esta segunda regla habría una
   pescadilla: el bote de un bloque sin asignación sale de sumar sus hijos, y
   los hijos no pueden tomar un porcentaje de una cifra que aún no existe.
3. Para un bloque de primer nivel, el bote de su sección.

### Las tres secciones

`tipus_cost` responde **dos preguntas distintas según dónde esté**:

| Dónde | Qué significa |
|---|---|
| En una **hoja** | Cómo se mide: un fijo por su prorrateo, un variable por el gasto real |
| En un **bloque** de primer nivel | A qué sección va el bloque: `FIXED`, `VARIABLE` o `INCOME` |

**`INCOME` solo tiene sentido en un bloque**, y no es una tercera forma de
gastar: es de donde sale el dinero. Sus hojas no se prorratean ni se comparan
con un techo — se miden por los movimientos de **entrada** del mes. Mientras
estuvieron entre los gastos, una categoría de ingreso caía en variables por
descarte y salía como un bloque de cero euros compitiendo por un bote que es
justamente suyo.

Son preguntas separadas porque las respuestas no tienen por qué coincidir.
**«Llar» es un gasto fijo** —el alquiler no se negocia cada mes— pero la luz y
el agua de dentro se miden por consumo real, para que un invierno caro salga
como desviación sobre lo previsto y no como un pico de caja inexplicable.
Mientras la sección salía solo de las hojas, esas dos hojas variables se
llevaban el bloque entero, alquiler incluido, a la sección de variables.

Un bloque que **no declara nada** deduce su sección: es fijo cuando **todas**
sus hojas son `FIXED`; si mezcla, va entero a variables. Partirlo por la mitad
dejaría el mismo bloque en las dos secciones y no se podría repartir ni en un
sitio ni en otro.

Para volver a la deducción automática hay que vaciar el campo, y una
actualización parcial no distingue «vacío» de «no enviado». El valor centinela
es **`AUTO`**, el mismo criterio que el `parent_id` negativo.

El bote de los fijos es el sueldo entero —son la primera mordida, no hay nada
por encima—. El de los variables es lo que queda después de ellos. Así, marcar
una categoría como fija en la pantalla de Categorías es lo único que hace falta
para mover un bloque de sección.

**De dónde sale el sueldo**, por orden:

1. El importe guardado para ese mes concreto en `monthly_income` — la paga
   extra, un mes con menos horas.
2. `settings.expected_monthly_income`, el sueldo de referencia.
3. Si no hay ninguno, los porcentajes **no producen techo**. Se devuelve `null`,
   no cero: un cero se leería como "presupuesto de 0 €", que es una afirmación
   distinta de "falta configurar el sueldo".

**Los ingresos reales importados no se usan nunca como base.** Harían bailar el
plan: un mes con la nómina aún sin importar tendría techos de cero, y una
devolución inesperada los inflaría todos. Sí se reportan al lado
(`ingressos_reals`) para ver la desviación.

El resumen mensual devuelve un objeto —no una lista— porque un techo calculado
por porcentaje no se puede interpretar sin saber sobre qué sueldo se ha
calculado:

```json
{
  "periode": "2026-03",
  "sou_base": 2000.00,
  "sou_base_origen": "PER_DEFECTE",
  "ingressos_reals": 1980.00,
  "ingressos_previstos": 2000.00,
  "total_disponible": 2000.00,
  "total_disponible_origen": "INGRESSOS",
  "total_assignat": 1700.00,
  "percentatge_assignat": 85.00,
  "seccions": [
    { "tipus": "INCOME",   "base": null,    "assignat": 2000.00, "real": 1980.00,
      "percentatge_del_sou": null, "restant": null, "grups": [ ... ] },
    { "tipus": "FIXED",    "base": 2000.00, "assignat": 800.00,
      "percentatge_del_sou": 40.00, "restant": 1200.00, "grups": [ ... ] },
    { "tipus": "VARIABLE", "base": 1200.00, "assignat": 900.00,
      "percentatge_del_sou": 45.00, "restant":  300.00, "grups": [ ... ] }
  ],
  "grups": [ ... ]
}
```

Los ingresos van **primero**: leído de arriba abajo, el mes se explica solo —lo
que entra, lo que está comprometido, lo que queda—. Su sección no reparte nada,
así que no tiene `base` ni porcentaje, y sus hojas traen `aporta_al_disponible`
con lo que cada una pone en el total.

Cada nodo trae además `base_assignacio` (sobre qué bote se mide),
`percentatge_efectiu` (qué porcentaje de ese bote representa, aunque se haya
fijado por importe), `percentatge_del_sou` y `restant` (lo que un bloque tiene
asignado y todavía no ha repartido entre sus hijos).

`percentatge_assignat` sale **de los euros**, no de sumar los porcentajes
guardados: un 25% de un bloque y un 30% del sueldo no se pueden sumar. Pasar del
100% es un error de planificación, no del programa: la interfaz lo marca en rojo
pero no lo impide.

`grups` mantiene la lista plana de bloques de primer nivel, para quien no
necesite saber en qué sección cae cada uno.

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
