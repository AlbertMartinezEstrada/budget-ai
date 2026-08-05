# 🤖 Budget AI - Tu Copiloto Financiero con Gemini

Bienvenido al portal central de **Budget AI**, un ecosistema financiero inteligente diseñado para darte el control total de tu economía personal. Este proyecto ha evolucionado de un simple clasificador de gastos a un **gestor financiero integral** impulsado por la IA de Google Gemini.

---

## 🌟 Visión General

Budget AI no es solo una hoja de cálculo bonita. Es un sistema proactivo que entiende tus hábitos, automatiza tus tareas repetitivas y te ayuda a alcanzar tus metas de ahorro.

### ¿Qué hace a Budget AI diferente?
- **Categorización Inteligente**: Olvídate de clasificar transacciones a mano. Gemini analiza tus extractos bancarios y los organiza con precisión quirúrgica.
- **Gestión Multi-cuenta**: Visualiza tu patrimonio total, desde cuentas corrientes hasta inversiones y efectivo.
- **Control de Presupuestos**: Define límites y recibe alertas visuales antes de que sea demasiado tarde.
- **Metas con Propósito**: Ahorra para lo que de verdad importa con seguimiento de progreso en tiempo real.
- **Automatización**: Procesamiento de gastos fijos y transferencias periódicas sin intervención manual.

---

## 🏗️ Arquitectura del Sistema

El proyecto se basa en una arquitectura moderna y robusta, diseñada para escalar y ser fácil de mantener.

### 🌐 Frontend (El "Lienzo")
- **Tecnología**: Modern Vanilla JavaScript (ES Modules).
- **Estilo**: Tailwind CSS para una interfaz limpia y profesional.
- **Filosofía SPA**: Una Single Page Application fluida donde el contenido se inyecta dinámicamente sin recargar la página.

### ⚙️ Backend (El "Motor")
- **Principal**: **Java con Spring Boot 3**. Una API REST profesional que gestiona toda la lógica de negocio, validaciones y cálculos.
- **Legacy**: Contamos con una base sólida en Python (FastAPI) de la cual hemos migrado el núcleo de inteligencia.
- **Base de Datos**: PostgreSQL, con un esquema relacional optimizado para integridad y rendimiento.

### 🧠 Inteligencia Artificial
- **Modelo**: Google Gemini 2.5 Flash.
- **Función**: Clasificación automática de entidades (empresas) y categorías financieras a partir de descripciones bancarias crudas.

---

## 📊 Módulos Implementados

| Módulo | Estado | Funcionalidad Clave |
| :--- | :--- | :--- |
| **Cuentas** | ✅ Listo | Gestión de saldos, tipos de cuenta y personalización visual. |
| **Transacciones** | ✅ Listo | Historial detallado con vinculación a cuentas y categorías. |
| **IA Reader** | ✅ Listo | Procesador de CSV bancarios con clasificación automática. |
| **Presupuestos** | ✅ Listo | Límites mensuales por categoría con tracking de gasto actual. |
| **Metas de Ahorro** | ✅ Listo | Objetivos financieros con barras de progreso y fechas límite. |
| **Recurrentes** | ✅ Listo | Automatización de nóminas, alquileres y suscripciones. |
| **Transferencias** | ✅ Listo | Movimiento de fondos entre cuentas con validación de saldo. |
| **Analytics** | ✅ Listo | Resúmenes mensuales, anuales y tendencias de gasto. |

---

## 🚀 Próximos Pasos & Hoja de Ruta

Nuestra misión es convertir Budget AI en la herramienta definitiva. Estas son las tareas que tenemos por delante:

1.  **Seguridad & Usuarios**: Implementar JWT y Spring Security para que cada usuario tenga su propio espacio privado.
2.  **Interfaz Completa**: Desarrollar las vistas de Cuentas, Metas y Analytics en el frontend para aprovechar la potencia del nuevo backend Java.
3.  **Notificaciones**: Alertas por email o push cuando te acerques a tus límites de presupuesto.
4.  **Predicciones con IA**: "Gemini, ¿cuánto habré ahorrado en Navidad si sigo gastando así?" - Integrar predicciones basadas en histórico.

---

## 🛠️ Guía Rápida para Desarrolladores

Para poner en marcha todo el ecosistema:

```bash
# Levantar todo con Docker (Frontend, Backend, DB)
docker-compose up --build
```

- **Frontend**: `http://localhost:3000`
- **Backend API**: `http://localhost:8000`
- **Admin DB**: `docker exec -it budget_db psql -U "$DB_USER" -d "$DB_NAME"` (credenciales en el `.env`, nunca en el repositorio)

---

*Creado con ❤️ por Gemini CLI para transformar tu relación con el dinero.*
