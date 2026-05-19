# Budget AI - Gestor Financiero Personal Completo

Aplicación completa para gestionar tus finanzas personales con Inteligencia Artificial. Controla múltiples cuentas, presupuestos, metas de ahorro y obtén analytics detallados de tus gastos e ingresos.

## Arquitectura

- **Frontend**: React (Node.js)
- **Backend**: Java (Spring Boot 3) - *Migrado de Python (FastAPI)*
- **Base de Datos**: PostgreSQL
- **IA**: Google Gemini 2.5 Flash

## Cómo ejecutar

Asegúrate de tener Docker y Docker Compose instalados.

1.  Crea un archivo `.env` en la raíz con tus variables:
    ```env
    POSTGRES_USER=albert
    POSTGRES_PASSWORD=1234567
    POSTGRES_DB=budget_db
    GEMINI_API_KEY=tu_api_key_aqui
    ```

2.  Ejecuta:
    ```bash
    docker-compose up --build
    ```

3.  Accede a la aplicación en `http://localhost:3000`.

### Reiniciar solo el backend (Docker)

Si has cambiado código Java/Spring en `backend-java/`, reinicia únicamente el servicio `backend`.

- Reinicio rápido (sin reconstruir imagen):
  ```bash
  docker compose restart backend
  ```

- Reinicio aplicando cambios de código (rebuild + recreate):
  ```bash
  docker compose up -d --build backend
  ```

- Ver logs del backend:
  ```bash
  docker compose logs -f backend
  ```

## Funcionalidades Principales

### 💰 Gestión de Finanzas Personales
- ✅ **Múltiples cuentas bancarias** (corriente, ahorro, efectivo, tarjetas)
- ✅ **Tracking de gastos e ingresos** con categorización automática por IA
- ✅ **Presupuestos mensuales** por categoría con alertas
- ✅ **Metas de ahorro** con seguimiento de progreso
- ✅ **Transacciones recurrentes** (nóminas, suscripciones, etc.)
- ✅ **Transferencias entre cuentas** propias
- ✅ **Analytics y reportes** (mensuales, anuales, por categoría)
- ✅ **Prevención de duplicados** con sistema de hash

### 📊 Endpoints Principales

#### Transacciones
- `POST /upload-csv` - Sube CSV del banco para clasificar con IA
- `GET /gastos` - Lista transacciones (con filtros)
- `POST /confirm-upload` - Confirma y guarda transacciones

#### Cuentas
- `GET/POST/PUT/DELETE /accounts` - Gestión de cuentas bancarias
- `POST /accounts/{id}/adjust-balance` - Ajuste manual de saldo

#### Presupuestos
- `GET/POST/PUT/DELETE /budgets` - Gestión de presupuestos
- `GET /budgets/current` - Presupuestos activos del periodo

#### Metas Financieras
- `GET/POST/PUT/DELETE /goals` - Gestión de metas de ahorro
- `POST /goals/{id}/add-amount` - Añadir dinero a una meta

#### Transacciones Recurrentes
- `GET/POST/PUT/DELETE /recurring` - Gestión de recurrentes
- `POST /recurring/process` - Procesar transacciones vencidas

#### Transferencias
- `GET/POST/DELETE /transfers` - Transferencias entre cuentas

#### Analytics
- `GET /analytics/monthly-summary` - Resumen mensual
- `GET /analytics/category-breakdown` - Gastos por categoría
- `GET /analytics/yearly-summary` - Resumen anual
- `GET /analytics/monthly-trend` - Tendencia mes a mes

📖 **Documentación completa de endpoints:** [API_ENDPOINTS.md](backend-java/API_ENDPOINTS.md)
