# Budget AI

Gestor de finanzas personales. Importa extractos bancarios en CSV, los clasifica
con Google Gemini y lleva el control de cuentas, presupuestos, metas de ahorro,
transferencias y gastos recurrentes.

Aplicación de **un solo usuario**: toda la API está detrás de un inicio de
sesión, pero no hay separación de datos entre usuarios.

## Stack

| Capa | Tecnología |
|---|---|
| Frontend | JavaScript sin framework (módulos ES), servido por Express |
| Backend | Java 17 + Spring Boot 3.2 |
| Base de datos | PostgreSQL 17 |
| IA | Google Gemini 2.5 Flash |
| Orquestación | Docker Compose |

## Puesta en marcha

Necesitas Docker y Docker Compose.

### 1. Crea el fichero `.env` en la raíz

```bash
# Base de datos
DB_HOST=db
DB_NAME=budget_db
DB_USER=tu_usuario
DB_PASSWORD=tu_contraseña

# Clasificación automática (opcional: sin clave, la importación
# funciona igual pero sin categorizar)
GEMINI_API_KEY=tu_clave

# Autenticación (obligatorio: sin esto el backend no arranca)
AUTH_USERNAME=tu_usuario
AUTH_PASSWORD=tu_contraseña
JWT_SECRET=cadena_aleatoria_de_32_caracteres_minimo
```

Para generar el secreto:

```bash
node -e "console.log(require('crypto').randomBytes(48).toString('base64url'))"
```

El `.env` está en el `.gitignore` y no debe subirse nunca.

### 2. Levanta todo

```bash
docker compose up -d --build
```

### 3. Abre `http://localhost:3000`

El frontend está en el puerto 3000 y la API en el 8000.

## Comandos habituales

```bash
docker compose up -d --build backend   # aplicar cambios de Java
docker compose restart backend         # reiniciar sin reconstruir
docker compose logs -f backend         # ver logs
```

## Tests

```bash
cd backend-java && ./gradlew test              # 61 unitarios, sin Docker
cd backend-java && ./gradlew integrationTest   # 26, requieren Docker
cd frontend && npm test                        # 17
```

Se ejecutan solos en cada push y cada pull request. Ver [TESTING.md](TESTING.md).

## Documentación

| Documento | Qué explica |
|---|---|
| [AGENTS.md](AGENTS.md) | Contexto y reglas del proyecto para asistentes de IA |
| [ARQUITECTURA.md](ARQUITECTURA.md) | Cómo funciona todo por dentro |
| [AUTENTICACION.md](AUTENTICACION.md) | El inicio de sesión, configuración y limitaciones |
| [TESTING.md](TESTING.md) | Qué cubre cada test y por qué |
| [HISTORIAL.md](HISTORIAL.md) | Los fallos que se han corregido y cómo |
| [backend-java/API_ENDPOINTS.md](backend-java/API_ENDPOINTS.md) | Referencia de endpoints |

## Migraciones de esquema

El esquema **no** se genera solo: `spring.jpa.hibernate.ddl-auto` está en
`validate`, así que Hibernate comprueba que las tablas cuadren con las
entidades y falla al arrancar si no es así.

- Una instalación nueva parte de [`backend-java/init.sql`](backend-java/init.sql).
- Una instalación existente aplica los ficheros de
  [`backend-java/migrations/`](backend-java/migrations/) a mano, en orden.

## Limitaciones conocidas

- **Un solo usuario.** No hay tabla de usuarios ni aislamiento de datos.
- **No se pueden revocar sesiones** una por una; cambiar `JWT_SECRET` las
  invalida todas.
- **Tailwind se carga por CDN**, lo que muestra un aviso en consola y no es
  adecuado para producción.
- **La interfaz mezcla catalán y castellano** según la vista.
- El backend de Python en `backend-python-legacy/` ya no se usa.
