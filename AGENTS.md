# Contexto para agentes de IA

Fichero canónico de contexto del proyecto. Si trabajas sobre este repositorio
con un asistente de IA, lee esto antes de tocar código.

Léelo entero: la mayoría de reglas de aquí existen porque **ya se rompieron
una vez**, y casi ninguna de esas roturas daba error. Ver
[HISTORIAL.md](HISTORIAL.md).

## Qué es

Gestor de finanzas personales de **un solo usuario**. Importa extractos
bancarios en CSV, los clasifica con Google Gemini y lleva cuentas,
presupuestos, metas, transferencias y gastos recurrentes.

| Capa | Tecnología | Dónde |
|---|---|---|
| Frontend | JavaScript sin framework, módulos ES | `frontend/public/` |
| Servidor estático | Express | `frontend/index.js` |
| Backend | Java 17, Spring Boot 3.2 | `backend-java/` |
| Base de datos | PostgreSQL 17 | `backend-java/init.sql` |
| Legado | Python, **sin usar** | `backend-python-legacy/` |

Backend en el puerto **8000** (no 8080). Frontend en el 3000.

## Comandos

```bash
docker compose up -d --build          # levantar todo
docker compose up -d --build backend  # aplicar cambios de Java
docker compose logs -f backend        # logs

cd backend-java && ./gradlew test              # 91 unitarios, sin Docker
cd backend-java && ./gradlew integrationTest   # 54, requieren Docker
cd frontend && npm test                        # 17
```

**Ejecuta los tests antes de dar nada por terminado.** El backend hay que
reconstruirlo para ver cambios de Java; el frontend se sirve desde disco y basta
recargar.

---

## Reglas que no se pueden saltar

### 1. Los nombres de los campos JSON son un contrato

Las entidades exponen nombres en catalán y `snake_case` vía `@JsonProperty`,
distintos de los campos Java:

| Java | JSON |
|---|---|
| `Account.currentBalance` | `saldo_actual` |
| `Transaction.amount` | `cost` |
| `FinancialGoal.targetAmount` | `quantitat_objectiu` |
| `Transfer.amount` | `import` |

**Dos excepciones que hay que recordar:**

- `Settings` **no** lleva `@JsonProperty`: se serializa en camelCase
  (`userName`, `notificationsExpenses`).
- `Transfer` expone las cuentas como `sourceAccount` y `destinationAccount`
  —los nombres de campo Java—, **no** `account_origen_id`, que son los nombres
  de las columnas.

Un campo mal escrito en el frontend **no da error**: en JavaScript es
`undefined` y se renderiza como cero o rompe en silencio. Fue la causa de casi
todos los fallos graves del proyecto.

Si cambias un nombre, cambia también el frontend que lo lee y
`JsonContractTest`. Si ese test falla, **no ajustes la expectativa**: arregla el
frontend.

### 2. El dinero es `BigDecimal`, nunca `double`

`BigDecimal` en Java, `NUMERIC(15,2)` en PostgreSQL. Usa `add`, `subtract` y
`compareTo`. Nunca `==` ni aritmética de coma flotante.

Con `double`, un saldo real llegó a ser `112.97000000000018`.

### 3. Los valores por defecto van en `@PrePersist`

Nunca como inicializadores de campo:

```java
private BigDecimal currentBalance;          // ✅
private BigDecimal currentBalance = ZERO;   // ❌

@PrePersist
void applyDefaults() {
    if (currentBalance == null) currentBalance = BigDecimal.ZERO;
}
```

Jackson construye la entidad con el constructor vacío y luego asigna solo lo
que venga en la petición. Con un valor por defecto en la declaración, una
actualización parcial llega con ese valor y no con `null`, y **es imposible
distinguir "no me han enviado el campo" de "me lo quieren poner a cero"**.
Editar una cuenta le borraba el saldo por esto.

Por lo mismo, las actualizaciones parciales comprueban `if (campo != null)`
antes de asignar.

### 4. Lo que mueve dinero es `@Transactional` y relanza excepciones

Tres sitios tocan saldos: `POST /transfers`, `DELETE /transfers/{id}` y
`POST /confirm-upload`.

```java
} catch (Exception e) {
    throw new MiExcepcion(e.getMessage());   // ✅ hace rollback
    // return ResponseEntity.internalServerError()...  ❌ no lo hace
}
```

Capturar la excepción y devolver un `ResponseEntity` **impide el rollback**,
que es justo lo que hace falta cuando el dinero ya se movió.

Borrar una transferencia **revierte** los saldos; no basta con borrar la fila.

### 5. Frontend: tres cosas prohibidas

```javascript
fetch(`${API_URL}/algo`)                  // ❌ no envía la cookie de sesión
apiFetch('/algo')                         // ✅

container.innerHTML = `<td>${t.empresa}</td>`              // ❌ XSS
container.innerHTML = `<td>${escapeHtml(t.empresa)}</td>`  // ✅

<button onclick="borrar(${id})">                       // ❌ se rompe con O'Brien
<button data-action="delete" data-id="${id}">          // ✅ delegación
```

Los datos vienen del CSV del banco y de la respuesta de Gemini: **no son de
confianza**.

Tampoco construyas clases de Tailwind en tiempo de ejecución: `bg-${color}-500`
no existe, hay que enumerarlas.

`frontend/test/contract.test.js` vigila estas cuatro reglas y falla si vuelven.

### 6. El esquema no se genera solo

`ddl-auto=validate`. Hibernate comprueba que las tablas cuadren con las
entidades y **falla al arrancar** si no.

- Instalación nueva → `backend-java/init.sql`, que es la fuente de verdad.
- Instalación existente → un fichero nuevo en `backend-java/migrations/`,
  aplicado a mano.

**Si tocas una entidad, toca `init.sql` en el mismo cambio.** Los tests de
integración levantan la base de datos desde ese fichero, así que una
divergencia hace fallar toda la suite. Así se descubrió que faltaba la tabla
`settings` y que las claves primarias tenían el tipo equivocado.

### 7. Las categorías son un árbol y las transacciones van a hojas

`Category.parent_id` define grupos y hojas. **Un movimiento asignado a un grupo
se contaría dos veces**: por sí mismo y al agregar sus hijos. El backend lo
rechaza al confirmar una importación; el frontend no debe ofrecer grupos donde
se elige la categoría de un movimiento.

`tipus_cost` (`FIXED`/`VARIABLE`) **significa dos cosas distintas según dónde
esté**, y confundirlas es fácil:

- En una **hoja**, cómo se mide: un fijo entra por su prorrateo y un variable
  por su gasto real. `null` cuenta como variable.
- En un **bloque** de primer nivel, a qué sección va: `FIXED`, `VARIABLE` o
  `INCOME`. A `null`, se deduce: es fijo si todas sus hojas lo son.

Por eso un bloque fijo puede tener hojas variables dentro (el alquiler no se
mueve, la luz sí). Para vaciar el campo hace falta el centinela `AUTO`: una
actualización parcial no distingue «vacío» de «no enviado».

**`INCOME` cambia qué se mide**: las hojas de un bloque de ingresos suman los
movimientos de entrada, no los de gasto. Con el filtro de gasto sumaban siempre
cero.

**Lo que se reparte es la suma de los ingresos**, no el sueldo: el sueldo es un
bloque de ingreso más. Cada hoja aporta el **mayor** entre su previsión y lo
recibido — sumarlos contaría la misma nómina dos veces. El sueldo de referencia
solo actúa de respaldo cuando no hay sección de ingresos.

El reparto es **en cascada**: un `percentatge` es del bote del nivel de encima,
**no del total**. Ver [ARQUITECTURA.md](ARQUITECTURA.md).

### 8. La IA no decide sobre el dinero

Gemini solo puede fijar empresa, categoría y descripción. **El importe, la
fecha, el tipo y el hash de verificación se conservan siempre del CSV.**

Si devuelve un número de filas distinto del enviado, se descarta la
clasificación entera. Sin `GEMINI_API_KEY` la importación funciona igual, sin
clasificar.

---

## Qué no subir nunca

- **`.env`** — está ignorado, y contiene credenciales de base de datos, la
  clave de Gemini y `JWT_SECRET`.
- **`notion_update.py`** — ignorado a propósito: tiene un token en claro.
- Nada de credenciales en código, documentación ni ejemplos. Ya pasó dos veces:
  en `application.properties` y en el README.

Antes de commitear, comprueba qué entra. Un `git add -A` se llevó por delante
un fichero con un token y hubo que deshacerlo.

## Convenciones

- **Commits y comentarios de código**: catalán.
- **Documentación**: castellano, salvo `TESTING.md`, que está en catalán.
- **Interfaz**: mezcla catalán y castellano según la vista. Es deuda conocida.
- Los comentarios explican **por qué**, no qué. Especialmente si algo parece
  raro: casi siempre lo parece porque arregla un fallo concreto.

## Trampas conocidas

- El backend escucha en el **8000**. El `EXPOSE 8080` del Dockerfile es residual.
- El `Dockerfile` usa `gradle assemble`, no `gradle build`: `build` arrastra
  `check`, que arrastra los tests de integración, y estos necesitan Docker.
- Los ficheros estáticos van con `Cache-Control: no-cache`. Sin eso el navegador
  se queda con el CSS y el JS viejos y parece que los cambios no se aplican.
- El **tema oscuro tiene dos mitades**: variables CSS bajo `html.dark` en
  `main.css`, y variantes `dark:` de Tailwind en las vistas. Si tocas una, mira
  la otra.
- El CI **no despliega**: no hay entorno de producción. Solo ejecuta tests y
  comprueba que los `Dockerfile` construyen.

## Más contexto

| Documento | Qué explica |
|---|---|
| [ARQUITECTURA.md](ARQUITECTURA.md) | Cómo funciona todo por dentro |
| [HISTORIAL.md](HISTORIAL.md) | Los fallos corregidos y por qué existen estas reglas |
| [AUTENTICACION.md](AUTENTICACION.md) | La sesión y sus límites |
| [TESTING.md](TESTING.md) | Qué cubre cada test |
