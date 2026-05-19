# Budget AI - Sistema de Clasificación de Gastos 💰🤖

## ¿Qué hace este proyecto?

Este sistema **almacena automáticamente tus gastos bancarios en una base de datos PostgreSQL** usando:
- **FastAPI** para la API backend
- **Gemini AI** para clasificar los gastos automáticamente
- **PostgreSQL** para almacenar los datos
- **Docker** para ejecutar todo fácilmente

## 🚀 Inicio Rápido

### 1. Iniciar el sistema
```powershell
cd C:\Projectes\budget-ai
docker-compose up --build
```

### 2. Acceder al frontend
Abre tu navegador en: **http://localhost:3000**

### 3. Subir tu CSV bancario
- Haz clic en "Seleccionar archivo"
- Escoge tu extracto bancario (CSV con formato `;`)
- Haz clic en "Pujar i Classificar amb IA"

¡Listo! Los gastos se clasificarán y guardarán automáticamente en PostgreSQL.

## 📊 Flujo de Datos

```
CSV → API (/upload-csv) → bank_reader.py → ai_engine.py (Gemini) → PostgreSQL
                                                                      ↓
                                               Frontend ← GET /gastos ←
```

## 🗄️ Base de Datos

Los datos se guardan en PostgreSQL con esta estructura:

```sql
CREATE TABLE despeses (
    id SERIAL PRIMARY KEY,
    data DATE NOT NULL,
    empresa VARCHAR(255),
    categoria VARCHAR(100),
    descripcio_curta TEXT,
    cost NUMERIC(10, 2),
    concepte_original TEXT,
    metode VARCHAR(50)
);
```

## 📡 Endpoints

### POST /upload-csv
Sube un CSV, clasifica con IA y guarda en la BD.

### GET /gastos
Obtiene todos los gastos guardados.

## 🔧 Configuración

### Variables de entorno (opcional)

Crea un archivo `.env` en la raíz:
```env
GEMINI_API_KEY=tu_api_key_aqui
DB_HOST=db
DB_NAME=budget_db
DB_USER=albert
DB_PASSWORD=1234567
```

Por defecto, usa la API key configurada en `docker-compose.yml`.

## 📁 Estructura del Proyecto

```
budget-ai/
├── backend/
│   ├── main.py              # API FastAPI (AQUÍ SE GUARDA EN BD)
│   ├── ai_engine.py         # Clasificación con Gemini
│   ├── bank_reader.py       # Limpieza del CSV
│   ├── init.sql             # Esquema PostgreSQL
│   └── requirements.txt
├── frontend/
│   └── public/index.html    # Interfaz web
└── docker-compose.yml       # Orquestación Docker
```

## 🧠 Categorías de IA

El sistema clasifica automáticamente en 18 categorías:
- Menjar i supermercat
- Bars i restaurants
- Transport
- Allotjament
- Compres i roba
- Higiene i bellesa
- Salut i farmàcia
- Gimnàs i esport
- Cultura, oci i entreteniment
- ... y más

## 🛠️ Comandos Útiles

Ver logs del backend:
```powershell
docker logs -f budget_api
```

Ver la base de datos:
```powershell
docker exec -it budget_db psql -U albert -d budget_db
```

Consultar gastos:
```sql
SELECT * FROM despeses ORDER BY data DESC LIMIT 10;
```

Limpiar todo y empezar de nuevo:
```powershell
docker-compose down -v
docker-compose up --build
```

## 📝 Formato del CSV

Tu CSV bancario debe tener este formato:

```csv
Concepto;Fecha;Importe
COMPRA EN MERCADONA;15/01/2026;-45,32 EUR
NETFLIX;10/01/2026;-12,99 EUR
GASOLINA;08/01/2026;-65,00 EUR
```

## 🔐 Seguridad

⚠️ La API Key de Gemini está configurada en `docker-compose.yml`. Para producción, usa variables de entorno seguras.

## 📦 Sobre `assistent-financer-personal`

Es un proyecto **separado** de Google AI Studio. No está conectado con este sistema budget-ai.

---

**¿Problemas?** Revisa que Docker esté corriendo y que todos los servicios estén activos con `docker ps`.
