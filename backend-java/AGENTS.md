# Backend · contexto para agentes de IA

Las reglas generales están en [`../AGENTS.md`](../AGENTS.md). **Léelas antes que
esto**: las de dinero, contratos JSON, `@PrePersist` y transacciones son las que
más se rompen.

## Stack

Java 17 · Spring Boot 3.2.2 · Gradle · PostgreSQL 17

Dependencias que conviene conocer: `spring-boot-starter-security` y
`jjwt` (sesión), `commons-csv` (extractos), `lombok`, y `testcontainers` en el
ámbito de test.

## Estructura

```
controller/   rutas HTTP
service/      lógica de negocio
repository/   Spring Data JPA
model/        entidades
security/     JwtService, filtro, cookies, credenciales, límite de intentos
config/       CORS
init.sql      esquema para instalaciones nuevas (fuente de verdad)
migrations/   cambios de esquema para instalaciones existentes
```

## Comandos

```bash
./gradlew test              # 61 unitarios, rápidos, sin Docker
./gradlew integrationTest   # 26, requieren Docker
./gradlew check             # ambos
./gradlew assemble          # empaquetar sin verificar
```

No uses `./gradlew build` en un contenedor: arrastra `check`, que arrastra los
tests de integración, que necesitan Docker. El `Dockerfile` usa `assemble` por
eso.

`bootRun` no es útil aquí: la aplicación espera la base de datos y las
variables de entorno del `docker-compose`.

## Detalles del backend

**Configuración sin valores por defecto.** `DB_*`, `AUTH_USERNAME`,
`AUTH_PASSWORD` y `JWT_SECRET` no tienen fallback: si faltan, la aplicación no
arranca. Es intencionado — es preferible que falle a que se quede abierta o
apuntando a una base de datos equivocada.

**Toda la API requiere sesión** salvo `/auth/login` y `/auth/logout`. Si añades
un endpoint, ya queda protegido por defecto: `anyRequest().authenticated()`.

**CORS está centralizado** en `config/CorsConfig`. No pongas `@CrossOrigin` en
los controllers; los ocho lo tenían con `origins = "*"` y se quitó.

**Los servicios que devuelven listas calculan los campos derivados.** Por
ejemplo, `BudgetService` rellena `currentSpent` en todas las rutas que
devuelven presupuestos, no solo en una.

## Al añadir o cambiar una entidad

1. Campos monetarios: `BigDecimal` con `precision = 15, scale = 2`.
2. Valores por defecto en `@PrePersist`, nunca en la declaración del campo.
3. `@JsonProperty` siguiendo la convención en catalán y `snake_case`.
4. **Actualiza `init.sql` en el mismo cambio**, con `BIGSERIAL` para claves
   primarias `Long` y `BIGINT` para las foráneas.
5. Si hay instalaciones existentes, añade un fichero en `migrations/`.
6. Añade el contrato a `JsonContractTest`.

Los tests de integración validan el esquema al arrancar: una divergencia entre
`init.sql` y las entidades hace fallar la suite entera.
