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
      categories/  goals/  debts/  transfers/  recurring/  analytics/
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
| `POST/PUT/DELETE /gastos` | Lo mismo con un movimiento suelto: editar y borrar revierten antes |

Un traspaso entre cuentas propias (`compte_contrapart_id`) mueve en cualquiera
de ellas **los dos saldos**: el de su cuenta y el de la otra.

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

**Los desplegables de categoría salen todos de `categoryOptions.js`**, agrupados
como el presupuesto: primero Gastos fijos, luego Gastos variables e Ingresos, y
dentro un grupo por bloque («Gastos fijos · Llar») con sus hojas por orden
alfabético. La sección de cada bloque se calcula igual que en
`BudgetService.sectionOf`: si se calculara de otra forma, el desplegable diría
una sección y el presupuesto contaría otra. Por defecto solo ofrece hojas; los
bloques, solo donde tienen sentido (asignar un presupuesto, elegir el grupo de
una categoría). Antes cada pantalla montaba el suyo: unos en el orden de la base
de datos, otros con bloques que no se podían elegir, y con cuarenta categorías
no se encontraba nada.

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
plan               el mismo prorrateo           el mismo prorrateo, si tiene recurrente
caja               gasto real del mes           gasto real del mes
```

En las dos, un importe asignado al mes manda sobre el prorrateo. En una
variable el recurrente es el tope con el que se compara lo gastado: la luz
prevé 60 € y gasta lo que diga la factura. Antes una variable no tomaba el plan
de sus recurrentes, y el recurrente de la luz no salía en ningún sitio.

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

### Recurrentes: la plantilla de cada mes

El menú **Recurrentes** de Presupuestos (`/fixed-costs`; empezó llamándose
«Costes fijos» y el código aún los llama así) no es una tabla aparte: son las
recurrentes de gasto de las hojas de gasto, fijas o variables. Como el plan de
una hoja sin importe propio ya sale del prorrateo de sus recurrentes, lo que se
define ahí aparece solo en cada mes, sin copiar nada. Son las mismas filas que
la pantalla de Recurrentes.

- **Un ingreso recurrente es la previsión de su hoja de ingresos** cuando el
  mes no tiene una propia, y entra en lo que se reparte igual que una previsión
  puesta a mano: cuenta lo mayor entre lo previsto y lo recibido.
- **Una recurrente va a una hoja de su sentido, y la categoría es
  obligatoria.** `CategoryHierarchyService.requireRecurringCategory` rechaza un
  bloque —se contaría dos veces, y al procesarla crearía un movimiento en un
  bloque— y una hoja del otro sentido, donde no contaría: en una de ingresos el
  presupuesto mira lo que entra. Antes se aceptaba todo y el recurrente
  desaparecía del presupuesto sin avisar.
- **Cambiar un importe solo en un mes** es asignarle un presupuesto a la hoja
  para ese mes: manda sobre el recurrente solo en ese mes. Quitarlo devuelve
  el recurrente. «Copiar mes anterior» no copia estos cambios puntuales en
  hojas con recurrente: si lo hiciera, dejarían de ser de un solo mes.
- **Un cambio en el recurrente vale desde el mes que se está mirando.** Las
  recurrentes tienen `vigent_des_de` y `vigent_fins` (`NULL` = sin límite).
  Editar una que ya contaba antes cierra la versión vieja el mes anterior y
  crea una nueva; quitarla la cierra en vez de borrarla. Si se modificara la
  fila, el plan de todos los meses pasados cambiaría con ella. Si empezó ese
  mismo mes, no ha contado nunca antes y se edita o borra directamente.
- Una versión cerrada no genera cargos posteriores a su cierre (`POST
  /recurring/process`), y la nueva empieza su calendario dentro de su
  vigencia: el mismo cargo no sale dos veces.
- **En Presupuestos, el recurrente es el plan, no el gasto.** «Gastado» sale
  de `caixa_real`, los movimientos importados del mes, y no de
  `cost_vida_real`, que da un fijo por su prorrateo aunque no haya ningún
  movimiento: un alquiler salía «gastado 800 de 800» antes de subir el CSV.
  El mes que cae un cargo anual, el gasto supera el plan de ese mes; es lo que
  marca «cargo este mes».
- `GET /recurring` no devuelve las versiones ya cerradas, para que la
  pantalla de Recurrentes no las muestre como duplicados. Editar desde esa
  pantalla sí modifica la fila tal cual: el historial solo se conserva
  cambiando desde el menú de Presupuestos.
- **Editar desde la pantalla de Recurrentes solo cambia lo que llega.** Antes
  copiaba todos los campos, y como el formulario no envía `activa` la dejaba a
  `null`: cada edición sacaba el recurrente del presupuesto, que solo lee los
  activos, y le quitaba la cuenta. La migración `013` vuelve a activar los que
  se quedaron así (nada de la interfaz pone nunca uno a `false` ni a `null`);
  la cuenta no se puede recuperar.
- **La pantalla de Recurrentes dice cómo cuenta cada uno**: la categoría y lo
  que pone al mes en el presupuesto o, si no cuenta, por qué (inactivo, sin
  categoría, en un bloque o en una categoría del otro sentido). El backend ya no
  acepta ninguno de estos, pero los de antes siguen ahí.

### El endpoint

`GET /budgets/monthly-summary?year=&month=` devuelve el árbol con
`cost_vida_pla`, `cost_vida_real`, `caixa_real`, `prorrateig_mensual`,
`quotes_deutes`, `carrec_puntual_aquest_mes` y `subcategories` en cada nodo.

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
    └──> VARIABLES   lo que queda: ingresos − fijos (el ahorro es uno más)
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

Los dos están **ligados**: guardar el sueldo de un mes
(`PUT /budgets/monthly-income/{periodo}`) escribe también esa cifra como
previsión de la nómina del mes, en la misma transacción. Antes eran
independientes, y fijar el sueldo no cambiaba nada en cuanto existía un bloque
de ingresos. La nómina se reconoce por el nombre de la hoja («Nòmina», «Sou»,
«Salari», «Sueldo», «Salario», sin distinguir acentos); si no hay ninguna, el
sueldo se guarda igual y sigue haciendo de respaldo. Borrar el sueldo del mes
quita la previsión solo si sigue siendo la que puso él: si se ha cambiado a
mano, es una decisión del usuario y se queda.

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

### Dinero que se mueve entre tus propias cuentas

Con varias cuentas, el mismo dinero aparece varias veces. Un traspaso de 100 €
de la cuenta principal a Revolut sale en los extractos de las dos:

```
Principal  −100  «traspàs a Revolut»   contaba como GASTO
Revolut    +100  «entrada»             contaba como INGRESO
Revolut    −100  «compra accions»      contaba como GASTO
```

200 € de gasto donde solo hay 100. Y la entrada era peor: desde que la cabecera
del presupuesto sale de la suma de ingresos, **ensanchaba el bote a repartir con
euros que ya estaban dentro**.

**Un traspaso entre tus cuentas no es ni gasto ni ingreso**: el dinero sigue
siendo tuyo. Es un movimiento con `compte_contrapart_id`, la otra cuenta, y lo
que cuenta es lo que se hace con el dinero:

| Traspaso | Cómo cuenta | Ejemplo |
|---|---|---|
| Entre cuentas del día a día | No cuenta: se guarda con `exclos_pressupost` | Principal → Revolut. Cuenta lo que se paga desde Revolut, cada pago en su categoría (Claude en Subscripcions) |
| Hacia una cuenta de ahorro (`AHORRO`, `INVERSIONES`) | Cuenta, en su categoría | Principal → Trade Republic, en Estalvis |
| Desde una cuenta de ahorro | Resta de la categoría donde se apartó; no es ingreso | Sacar 200 € de Trade Republic |

```
Principal  −100  «traspàs a Revolut»   ⇄ Revolut, no cuenta
Revolut    −20   «Claude»              Subscripcions · Claude
Principal  −300  «Trade Republic»      ⇄ Trade Republic, Estalvis: 300 de ahorro
```

**Una sola fila por traspaso, y mueve los dos saldos.** La fila vive en la
cuenta de cuyo extracto sale, y `InternalTransferService` suma o resta lo mismo
en la otra cuenta. Así Trade Republic sube sin importar su extracto. Borrar el
traspaso revierte los dos saldos, y editarlo es revertir y volver a aplicar,
como cualquier movimiento.

**La otra pata se reconoce al importar.** Al subir el extracto de Revolut, la
entrada de 100 € es el mismo dinero que ya movió el traspaso. En la revisión,
una línea con el mismo importe, el sentido contrario y una fecha a ±3 días de
un traspaso guardado hacia esa cuenta sale **desmarcada**, con el aviso «ya
guardado como traspaso». Cada traspaso reconoce como mucho una línea: dos
traspasos iguales el mismo día son dos. Da igual qué extracto se importe
primero: el que llega antes crea el traspaso, y el otro lo reconoce.

**Las reglas de importación lo hacen solas**: una regla puede decir «es un
traspaso a la cuenta X» (`compte_traspas_id`). Si apunta a la cuenta del mismo
extracto, se ignora: «REVOLUT» → Revolut no significa nada leyendo el extracto
de Revolut. En una regla de traspaso, `marca_exclos` no se aplica: si cuenta o
no lo deciden las dos cuentas.

**Se guarda marcado, no se calcula al leer.** Un traspaso entre cuentas del día
a día se guarda con `exclos_pressupost`, y así el presupuesto, las deudas, el
análisis y el panel lo respetan sin saber nada de traspasos. El panel y el
análisis tampoco suman traspasos en sus totales de ingresos y gastos. Si
después cambias el tipo de una cuenta, los traspasos ya guardados no se
recalculan.

**Antes la regla era la contraria**: «una vez el dinero sale de la cuenta
principal, ya está contado», y lo que se pagaba desde Revolut se marcaba a mano
como no contado. Funcionaba para el ahorro, pero cada pago desde Revolut perdía
su categoría: todo acababa en un solo movimiento «traspàs a Revolut». Los
movimientos de antes no se tocan, porque no se sabe cuáles eran traspasos.
`exclos_pressupost` sigue sirviendo para lo que no es ni gasto ni ingreso y no
es un traspaso.

La identidad de un movimiento importado pasa a ser **hash + cuenta**. Un
traspaso deja el mismo importe el mismo día en dos extractos, y con la unicidad
solo sobre el hash la segunda pata se tomaba por un duplicado y se descartaba en
silencio — justo el movimiento que se quiere ver en la otra cuenta. La fórmula
del hash no se toca: cambiarla dejaría los movimientos ya importados con una
identidad vieja y volver a subir el mismo fichero los duplicaría.

### Las secciones

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

**El ahorro es un bloque variable más**, a propósito: «Estalvis». De lo que
sobra de los fijos se reparten porcentajes, y el ahorro es uno de ellos. Se
probó una sección de ahorro aparte, entre fijos y variables (migración `014`),
y se quitó (migración `016`): el porcentaje del ahorro dejaba de salir del
mismo bote que los demás, y el reparto ya no era el que se hace de verdad. Lo
que sí se quedó de aquel cambio es el nombre: la categoría se llamaba «Trade
Republic», que dice dónde está el dinero y no para qué es.

Una categoría de primer nivel sin subcategorías también elige su sección en
Categorías, no su naturaleza: con el desplegable de fijo o variable, editar un
bloque de ingresos sin hojas le quitaba la sección.

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

## Deudas y préstamos

Cuando me prestan 1.000 €, el saldo sube pero no soy más rico: los debo. Un
préstamo no es ni un ingreso ni un gasto, y la pantalla de Deudas solo lleva
**quién debe qué y cómo se ha acordado devolverlo**. Funciona en los dos
sentidos: `DEC` (me lo han prestado) y `EM_DEUEN` (lo he prestado yo).

### Cómo cuenta en el presupuesto

Un préstamo deja tres movimientos: la entrada (+1.000), lo que se compra con
ella (−1.000) y las devoluciones (−1.000 en total). La compra cuenta siempre.
Para que cada euro cuente una sola vez, las otras dos patas **cuentan juntas o
no cuenta ninguna**:

| Entrada | Devoluciones | Qué pasa |
|---|---|---|
| cuenta | cuentan | el mes del préstamo sale a 0 y el coste llega con las cuotas ✅ |
| no cuenta | no cuentan | el mes de la compra sale en rojo; las cuotas no se ven ✅ |
| no cuenta | cuentan | la compra cuenta dos veces ❌ |
| cuenta | no cuentan | 1.000 € regalados ❌ |

Se usa la primera. La entrada va a «Préstecs rebuts», una hoja de Ingressos
separada de la nómina, y las devoluciones a «Pagament de deutes», dentro del
bloque fijo «Deutes i préstecs». El presupuesto reparte el dinero que hay cada
mes: el del préstamo sí hay más, y los de las cuotas hay menos.

Lo que yo presto es el espejo: sale por «Préstecs fets» y vuelve por
«Cobrament de préstecs». Si el dinero prestado no se va a gastar, se marcan
como excluidas la entrada y las devoluciones, igual que un traspaso.

### Lo devuelto sale de los movimientos

No hay tabla de pagos. Un movimiento se vincula a su deuda con
`transactions.deute_id`, y lo devuelto es la suma de los vinculados **en el
sentido de devolución**: salidas si la debo, entradas si me la deben. El
movimiento del otro sentido es el préstamo en sí y no descuenta nada. Una tabla
de pagos aparte duplicaría cada línea del extracto y las dos copias acabarían
diciendo cosas distintas.

El vínculo es **independiente de la categoría**. Si un amigo me paga la cena y
se la devuelvo por Bizum, ese Bizum es un gasto de «Bars i restaurants» y a la
vez salda la deuda: el vínculo dice cuánto falta, la categoría cómo cuenta. En
el formulario, elegir la deuda propone la categoría según el sentido, y se
puede cambiar.

Borrar una deuda **no borra sus movimientos**: pasaron de verdad y el saldo
depende de ellos. Solo pierden el vínculo. Para desvincular un movimiento se
envía `deute_id: -1`, por la misma razón que el `parent_id` negativo.

### La forma de devolverlo

`forma_retorn` es `LLIURE` (sin calendario), `UNIC` (todo de golpe un día) o
`QUOTES` (una cuota cada semana, mes o trimestre). El calendario lo calcula
`RepaymentSchedule` y es una lista de **recibos**.

- **La última cuota es lo que falte**: 1.000 a 300 son 300, 300, 300 y 100.
- **Los meses se cuentan desde el primer pago**, no desde el anterior: si no,
  un calendario que empieza el 31 de enero se quedaría en el 28 desde febrero.
- Más de 600 pagos se rechaza: es una cuota mal escrita, no un plan.

**Los recibos van por periodos, no por días.** El recibo del 8 de octubre es
el de octubre: mientras dura el mes sale como `TOCA`, y solo pasa a
`ENDARRERIT` cuando el mes se ha acabado sin pagarlo. Antes se comparaba con el
día exacto, y un recibo del día 8 pagado el 10 salía atrasado. El periodo es la
semana (de lunes a domingo) en los semanales, el mes en los mensuales y en
`UNIC`, y los tres meses que empiezan el del recibo en los trimestrales.
`endarrerit` suma lo que falta de los recibos atrasados.

**Cada pago paga un recibo**: el que se eligió al vincularlo
(`transactions.deute_rebut`, el día del recibo) o, si no, **el que toca**: el
primero que no está pagado. Primero se reparten los que eligieron, y los demás
llenan por orden de fecha los que quedan libres. Las partes de un movimiento
dividido pagan siempre el que toca.

- Un recibo está **pagado** cuando lo que le ha llegado lo cubre. Una
  diferencia de céntimos —el 1 % del recibo, como mucho 1 €— también lo da por
  pagado: es redondeo, y dejarlo a medias por 0,03 € haría que el pago del mes
  siguiente fuera a taparlo y ese mes quedara sin pagar.
- **Lo que sobra acorta el final**, no adelanta los meses siguientes: 200 € en
  un recibo de 100 dejan ese mes pagado y el plan acaba un mes antes. Los
  céntimos que faltan de un recibo pagado van al último. Si al final quedarían
  solo céntimos, el último recibo se los queda: no hay recibos de 0,20 €.
- Un recibo a medias se queda lo que vale, y lo que falta es suyo (`PARCIAL`, o
  `ENDARRERIT` cuando acaba su periodo).

**Un recibo se puede quitar del calendario** (`debt_removed_receipts`), de dos
maneras: **saltado** (`SALTAT`, descuento cero), y ese mes no toca pagar pero lo
que se debe no cambia, así que el plan se alarga un recibo; o **descontado**
(`DESCOMPTAT`), y su importe se resta de la deuda (una rebaja). `import` sigue
siendo lo prestado; el descuento sale aparte como `descomptat` y se resta de
`pendent`. Un recibo que algún pago eligió no se puede quitar: el pago no
tendría adónde ir. Los que solo tienen pagos que les tocaron sí: esos pagos
pasan al siguiente. Se deshace con `DELETE /debts/{id}/rebuts-eliminats/{data}`.

### La cuota se reserva sola

Con la cuota pactada ya se sabe cuánto está comprometido cada mes. El resumen
mensual lo reserva en el plan de la hoja que diga la deuda (`category_id`, por
defecto «Pagament de deutes») y lo expone como `quotes_deutes`. Va aparte del
prorrateo porque no es un coste fijo: no tiene versiones y se acaba al saldar
la deuda.

Cada deuda reserva **lo que vale su recibo de ese mes** en el calendario de
verdad, el que ya tiene en cuenta lo pagado: si se adelanta dinero, el plan
acaba antes y los últimos meses no reservan nada; un mes saltado tampoco. Una
asignación puesta a mano en la hoja manda sobre la reserva, como con
cualquier coste fijo.

Lo que me deben **no se reserva ni se prevé**. Un dinero que aún no ha llegado
no debe ensanchar lo que se reparte: si se retrasa, el presupuesto habría
contado con él.

## Movimientos divididos en partes

Una misma línea del extracto puede ser varias cosas. La transferencia mensual
a Trade Republic es, a la vez:

| Parte | Categoría | Cómo cuenta |
|---|---|---|
| 300 € de ahorro | Trade Republic | gasto de ese bloque |
| 100 € que devuelven un autopréstamo | Pagament de deutes | gasto, y descuenta de la deuda |
| 60 € guardados para el seguro | Assegurances | gasto del seguro |
| 40 € que reponen lo que se cogió | Trade Republic | marcada «no cuenta»: no suma en ningún sitio |

Con una sola categoría por movimiento, el presupuesto contaba los 500 € enteros
en un sitio.

### El movimiento no se toca

Las partes van en `transaction_parts` y el movimiento sigue igual: el saldo se
movió una vez, por el total, y el hash sigue identificando la línea del
extracto. Dividir no es ningún movimiento nuevo, así que no pasa por el código
que mueve saldos. Lo único que se exige es que **las partes sumen exactamente el
importe** (y con dos decimales: `NUMERIC(15,2)` redondearía el tercero en
silencio y dejarían de sumarlo).

Cada parte va a una hoja, como cualquier dinero: una parte en un grupo se
volvería a sumar por sus hijos. Una sola parte no se acepta —para cambiar la
categoría ya está la edición— y una lista vacía quita la división.

### Mandan las partes

Si un movimiento tiene partes, su categoría, su «no cuenta» y su deuda dejan de
contar. `TransactionLines` convierte los movimientos en **líneas**: una por
movimiento sin dividir, o una por parte, con la fecha y el sentido del
movimiento. Presupuesto, deudas y análisis suman líneas. Es la única puerta: un
sitio que sumara movimientos contaría el dividido entero en su categoría de
antes.

Al dividir, la deuda del movimiento pasa a la parte que toca y el movimiento se
queda sin ella; si no, la ficha de la deuda lo contaría dos veces. Por la misma
razón, un movimiento dividido no puede cambiar de importe ni recibir una deuda
desde la edición.

`GET /gastos` devuelve cada movimiento con sus `parts`, cargadas en una sola
consulta para toda la lista: no es una relación de JPA porque el presupuesto lee
todos los movimientos de golpe y una colección por movimiento haría una consulta
por cada uno. El filtro por categoría encuentra un movimiento dividido por sus
partes, no por la categoría que aún lleva.

En la lista de Transacciones **las partes salen plegadas**. La pastilla
«dividit en N» las despliega, y su título ya dice dónde va cada parte; el botón
«Desplegar les parts» las abre o cierra todas. Lo desplegado se mantiene al
filtrar o volver a cargar la lista. Con todas abiertas, cuatro partes por
movimiento hacían la lista el doble de larga y lo que se buscaba quedaba
enterrado.

Borrar el movimiento borra sus partes (`ON DELETE CASCADE`), y una categoría que
solo usan partes tampoco se puede borrar.

### También al importar

En la revisión de un extracto, cada fila tiene el mismo botón de dividir. Las
partes se guardan en la fila y viajan en `parts` con la confirmación. El backend
**valida las de todas las filas antes de mover ningún saldo**: la importación es
todo o nada, y si una fila no cuadra el mensaje dice cuál («Moviment del
2026-10-01, Trade Republic, 500.00 €: les parts sumen…»). La categoría del
movimiento pasa a ser la de la primera parte, porque la que proponía la IA
podría ser un grupo y hacer fallar el guardado.

El alta manual acepta lo mismo. La edición, en cambio, rechaza `parts` con un
400: para un movimiento ya guardado está `/gastos/{id}/parts`, y aceptarlas en
silencio sin hacer nada sería peor que avisar.

## Los errores

Todos llegan al navegador con la misma forma:

```json
{"status": "error", "message": "No existeix el moviment 12. Potser s'ha esborrat…", "path": "/gastos/12/parts"}
```

Los produce `ApiErrorHandler`, un `@RestControllerAdvice`:

| Excepción | Código | Mensaje |
|---|---|---|
| `NotFoundException` | 404 | qué no existe |
| `IllegalArgumentException` | 400 | el de la validación |
| `IllegalStateException` | 409 | el de la validación |
| ruta desconocida | 404 | que el backend no la tiene y probablemente hay que reconstruirlo |
| JSON ilegible, parámetro del tipo que no toca | 400 | qué no se ha podido leer, sin clases de Java |
| clave foránea o única | 409 | que hay datos que dependen, con referencia |
| cualquier otra | 500 | genérico, con una referencia que también sale en el log |

Antes, lo que ningún controlador resolvía llegaba con el cuerpo de error de
Spring, que no lleva mensaje, y la pantalla decía «Not Found» o «Bad Request».
Cuatro controladores, además, convertían cualquier error en un 404 vacío.

Llega después de que la excepción haya salido del método y de su
`@Transactional`, así que el rollback ya se ha hecho. Los `@ExceptionHandler`
propios de un controlador (importaciones, transferencias) tienen preferencia.

El frontend completa lo que falte: `describeError` en `api.js` prefiere el
`message` del backend y, si solo llega la frase estándar del código («Not
Found»), explica qué puede haber pasado. Un `fetch` que no llega al servidor
dice que el backend no está en marcha, en vez de «Failed to fetch», y si pasa al
abrir la aplicación sale en la pantalla de entrada en lugar de una página en
blanco.

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

Trece tablas. `accounts`, `transactions`, `transaction_parts`, `categories`,
`companies`, `budgets`, `financial_goals`, `recurring_transactions`,
`transfers`, `settings`, `monthly_income`, `import_rules` y `debts`.

`ddl-auto` está en **`validate`**: Hibernate comprueba al arrancar que las
tablas cuadren con las entidades y falla si no. No genera ni modifica nada.

Esto tiene una consecuencia práctica: **`init.sql` es la fuente de verdad para
instalaciones nuevas** y tiene que mantenerse al día a mano. Los tests de
integración levantan la base de datos desde ese mismo fichero, así que
cualquier divergencia hace fallar toda la suite — que es como se detectó que a
`init.sql` le faltaba la tabla `settings` y que todas las claves primarias eran
`SERIAL` cuando las entidades usan `Long`.

Para cambiar el esquema en una instalación existente hay que añadir un fichero
en `backend-java/migrations/` y aplicarlo con el script:

```bash
scripts/migrate.sh --estat   # qué hay aplicado y qué falta, sin cambiar nada
scripts/migrate.sh           # copia de seguridad y aplica las pendientes, en orden
docker compose up -d --build backend
```

Lo aplicado se apunta en `schema_migrations`. Aplicarlas a mano, una a una, hacía
fácil saltarse alguna: el backend no arrancaba («missing column vigent_des_de») y
nada decía cuáles faltaban.

Una base de datos sin esa tabla las recibió a mano y no se sabe cuáles. No se
pueden repetir todas a ciegas —la 008 falla si ya está, y la 004 devolvería las
categorías a sus bloques de origen—, así que el script mira el esquema y apunta
como aplicadas las que ya se ven. Las que solo añaden categorías (004, 005, 007)
se dan por aplicadas también si lo está alguna posterior que cambia el esquema:
repetirlas desharía la organización del usuario, y que falten solo quiere decir
que falta alguna categoría por defecto. Una migración nueva no necesita
comprobación, pero sí poder aplicarse dos veces sin efecto.

El usuario y la base de datos se leen dentro del contenedor, con comillas
simples. Con dobles, el shell de fuera sustituye `$POSTGRES_USER` por su propia
variable, vacía, y `psql` recibe «-U -d»: en el Mac fallaba con
`role "-d" does not exist`. No hay versión `.bat` todavía.

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
