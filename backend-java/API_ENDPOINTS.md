# Budget AI - API Endpoints Documentation

## 📊 **Gestión de Transacciones**

### Transacciones existentes
- `GET /gastos` - Lista todas las transacciones
  - Query params: `categoryId`, `companyId` (filtros opcionales)
- `POST /upload-csv` - Sube un CSV del banco para clasificar
- `POST /confirm-upload` - Confirma y guarda transacciones revisadas

### Categorías y Empresas
- `GET /categories` - Lista todas las categorías
- `GET /categories/{id}` - Obtiene una categoría
- `POST /categories` - Crea una categoría
- `PUT /categories/{id}` - Actualiza (parcial: un campo ausente no borra)
- `DELETE /categories/{id}` - Elimina. **409** si tiene movimientos o subcategorías
- `GET /companies` - Lista todas las empresas

Las categorías forman un árbol. Campos JSON:

| Campo | Qué es |
|---|---|
| `parent_id` | Grupo al que pertenece. `null` = primer nivel |
| `tipus_cost` | `FIXED` o `VARIABLE`. `null` en los grupos; en una hoja cuenta como variable |
| `es_fix` | Derivado, solo lectura |

**Las transacciones solo se asignan a hojas** (categorías sin hijos).
`POST /confirm-upload` rechaza un movimiento cuya categoría sea un grupo.

Para sacar una categoría de su grupo, envía `parent_id` negativo:

```json
PUT /categories/12
{"parent_id": -1}
```

---

## 💰 **Gestión de Cuentas** (`/accounts`)

- `GET /accounts` - Lista todas las cuentas
  - Query param: `activeOnly=true` para solo activas
- `GET /accounts/{id}` - Obtiene una cuenta específica
- `POST /accounts` - Crea una nueva cuenta
  ```json
  {
    "nom": "Cuenta Ahorro",
    "tipus": "AHORRO",
    "saldo_actual": 1000.00,
    "moneda": "EUR",
    "activa": true,
    "color": "#FF5722"
  }
  ```
- `PUT /accounts/{id}` - Actualiza una cuenta
- `DELETE /accounts/{id}` - Elimina una cuenta
- `POST /accounts/{id}/adjust-balance` - Ajusta el saldo manualmente
  ```json
  {
    "amount": 100.00,
    "operation": "ADD"  // o "SUBTRACT"
  }
  ```

**Tipos de cuenta:** `CORRIENTE`, `AHORRO`, `EFECTIVO`, `TARJETA`, `INVERSIONES`

---

## 📅 **Gestión de Presupuestos** (`/budgets`)

- `GET /budgets` - Lista todos los presupuestos
  - Query param: `activeOnly=true` para solo activos
- `GET /budgets/current` - Presupuestos activos en la fecha actual
  - Query param: `date` (opcional, formato: yyyy-MM-dd)
- `GET /budgets/monthly-summary` - **Resumen del mes por grupos y subcategorías**
  - Query params: `year`, `month` (opcionales; por defecto, el mes actual)
- `GET /budgets/monthly-income` - Sueldos ajustados por mes
- `PUT /budgets/monthly-income/{YYYY-MM}` - Fija el sueldo de un mes concreto
- `DELETE /budgets/monthly-income/{YYYY-MM}` - Vuelve al sueldo por defecto

Un presupuesto puede llevar `percentatge` (0-100) en vez de importe fijo: el
techo se calcula sobre el sueldo del mes. Enviar un `percentatge` negativo al
actualizar lo elimina. El sueldo por defecto es `expectedMonthlyIncome` en
`PUT /settings`.
- `GET /budgets/{id}` - Obtiene un presupuesto específico
- `POST /budgets` - Crea un nuevo presupuesto
  ```json
  {
    "category": {"id": 1},
    "quantitat_limit": 500.00,
    "periode_inici": "2026-03-01",
    "periode_fi": "2026-03-31",
    "actiu": true
  }
  ```
- `PUT /budgets/{id}` - Actualiza un presupuesto
- `DELETE /budgets/{id}` - Elimina un presupuesto

---

### Resumen mensual: coste de vida vs caja

`GET /budgets/monthly-summary?year=2026&month=3`

Devuelve el árbol de categorías con dos lecturas del mismo mes:

- **Coste de vida**: los gastos fijos entran prorrateados (600 €/año = 50 €/mes),
  caiga el cargo cuando caiga. Los variables, por su gasto real.
- **Caja**: lo que ha salido de la cuenta este mes, tal cual.

```json
[
  {
    "categoria": { "id": 1, "nom": "Coche", "parent_id": null, "tipus_cost": null },
    "es_grup": true,
    "quantitat_limit": 700.00,
    "cost_vida_pla": 130.00,
    "cost_vida_real": 95.00,
    "caixa_real": 645.00,
    "carrec_puntual_aquest_mes": true,
    "subcategories": [
      {
        "categoria": { "id": 2, "nom": "Seguro", "parent_id": 1, "tipus_cost": "FIXED" },
        "prorrateig_mensual": 50.00,
        "cost_vida_real": 50.00,
        "caixa_real": 600.00,
        "carrec_puntual_aquest_mes": true
      },
      {
        "categoria": { "id": 3, "nom": "Combustible", "parent_id": 1, "tipus_cost": "VARIABLE" },
        "prorrateig_mensual": 0,
        "cost_vida_real": 45.00,
        "cost_vida_pla": 80.00,
        "caixa_real": 45.00,
        "carrec_puntual_aquest_mes": false
      }
    ]
  }
]
```

`carrec_puntual_aquest_mes` avisa de que un fijo se ha cobrado este mes. Sin esa
marca, ver 645 € de caja cuando el coste de vida dice 95 € parece un error.

Un límite puesto directamente sobre un grupo manda sobre la suma de sus hijos.

---

## 🎯 **Gestión de Metas Financieras** (`/goals`)

- `GET /goals` - Lista todas las metas
  - Query param: `completed=false` para activas, `completed=true` para completadas
- `GET /goals/{id}` - Obtiene una meta específica
- `POST /goals` - Crea una nueva meta
  ```json
  {
    "nom": "Vacaciones en Italia",
    "descripcio": "Ahorrar para viaje de verano",
    "quantitat_objectiu": 3000.00,
    "quantitat_actual": 500.00,
    "data_objectiu": "2026-07-01",
    "account": {"id": 2}
  }
  ```
- `PUT /goals/{id}` - Actualiza una meta
- `POST /goals/{id}/add-amount` - Añade dinero a una meta
  ```json
  {
    "amount": 100.00
  }
  ```
- `DELETE /goals/{id}` - Elimina una meta

---

## 🔄 **Gestión de Transacciones Recurrentes** (`/recurring`)

- `GET /recurring` - Lista todas las transacciones recurrentes
  - Query param: `activeOnly=true` para solo activas
- `GET /recurring/{id}` - Obtiene una transacción recurrente específica
- `POST /recurring` - Crea una nueva transacción recurrente
  ```json
  {
    "nom": "Suscripción Netflix",
    "category": {"id": 9},
    "company": {"id": 15},
    "import": 12.99,
    "tipus": "EXPENSE",
    "frequencia": "MENSUAL",
    "proxima_data": "2026-04-01",
    "account": {"id": 1},
    "activa": true,
    "descripcio": "Suscripción mensual"
  }
  ```
- `PUT /recurring/{id}` - Actualiza una transacción recurrente
- `DELETE /recurring/{id}` - Elimina una transacción recurrente
- `POST /recurring/process` - Procesa transacciones recurrentes vencidas

**Frecuencias:** `DIARIA`, `SETMANAL`, `MENSUAL`, `TRIMESTRAL`, `ANUAL`

---

## 🔁 **Gestión de Transferencias** (`/transfers`)

- `GET /transfers` - Lista todas las transferencias
  - Query param: `accountId` para filtrar por cuenta
- `GET /transfers/{id}` - Obtiene una transferencia específica
- `POST /transfers` - Crea una nueva transferencia entre cuentas
  ```json
  {
    "sourceAccount": {"id": 1},
    "destinationAccount": {"id": 2},
    "import": 500.00,
    "data": "2026-02-27",
    "descripcio": "Ahorro mensual"
  }
  ```
- `DELETE /transfers/{id}` - Elimina una transferencia

---

## 📈 **Analytics y Reportes** (`/analytics`)

### Resumen Mensual
- `GET /analytics/monthly-summary` - Resumen del mes actual
  - Query params: `year`, `month` (opcionales)
  ```json
  {
    "period": "2026-02",
    "total_income": 2500.00,
    "total_expense": 1800.00,
    "balance": 700.00,
    "transaction_count": 45
  }
  ```

### Desglose por Categorías
- `GET /analytics/category-breakdown` - Gastos por categoría del mes
  - Query params: `year`, `month` (opcionales)
  ```json
  [
    {
      "category": "Menjar i supermercat",
      "total": 450.00,
      "percentage": 25.0
    }
  ]
  ```

### Resumen Anual
- `GET /analytics/yearly-summary` - Resumen del año
  - Query param: `year` (opcional)
  ```json
  {
    "year": 2026,
    "total_income": 30000.00,
    "total_expense": 21600.00,
    "balance": 8400.00,
    "average_monthly_expense": 1800.00,
    "average_monthly_income": 2500.00
  }
  ```

### Tendencia Mensual
- `GET /analytics/monthly-trend` - Evolución mes a mes del año
  - Query param: `year` (opcional)

---

## 🎨 **Frontend Integration Notes**

1. **Colores para cuentas**: Usar formato hex (#FF5722)
2. **Fechas**: Formato ISO 8601 (yyyy-MM-dd)
3. **Montos**: Siempre en formato decimal (1234.56)
4. **CORS**: Habilitado para todos los orígenes

## 🔐 **Base de Datos**

Al iniciar con Docker, se crean automáticamente:
- Categorías predefinidas (20 categorías)
- Cuenta principal por defecto ("Compte Principal")
- Todas las tablas necesarias con constraints y FKs

## 🚀 **Próximas Funcionalidades Sugeridas**

- [ ] Dashboard con gráficos
- [ ] Alertas cuando se excede un presupuesto
- [ ] Exportación de reportes a PDF/Excel
- [ ] Soporte multi-usuario con autenticación
- [ ] App móvil
