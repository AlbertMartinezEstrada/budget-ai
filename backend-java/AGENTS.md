# Backend · contexto para agentes de IA

Las reglas generales están en [`../AGENTS.md`](../AGENTS.md). **Léelas antes que
esto**: las de dinero, contratos JSON, `@PrePersist` y transacciones son las que
más se rompen.

## Stack

Java 17 · Spring Boot 3.5.16 · Gradle · PostgreSQL 17

Dependencias que conviene conocer: `spring-boot-starter-security` y
`jjwt` (sesión), `commons-csv` (extractos), `lombok`, y `testcontainers` en el
ámbito de test.

## Estructura

```
controller/   rutas HTTP
service/      lógica de negocio
i18n/         Messages: los mensajes para el usuario en cuatro idiomas
repository/   Spring Data JPA
model/        entidades
security/     JwtService, filtro, cookies, credenciales, límite de intentos
config/       CORS
init.sql      esquema para instalaciones nuevas (fuente de verdad)
migrations/   cambios de esquema para instalaciones existentes
```

## Comandos

```bash
./gradlew test              # 291 unitarios, rápidos, sin Docker
./gradlew integrationTest   # 191, requieren Docker
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

**Una sesión es una fila de `sessions`, no solo el token.** El JWT lleva su id
(`jti`) y `JwtAuthenticationFilter` pregunta a `SessionService` si sigue
abierta: salir la borra y el token deja de servir aunque esté bien firmado. Un
controlador que necesite saber la sesión de la petición la recibe con
`@AuthenticationPrincipal SessionUser`.

**Los mensajes para el usuario llevan clave, no texto.** Van en
`src/main/resources/i18n/messages_ca.properties` (la referencia), `_es`, `_pt` y
`_en`, con los valores como `{nombre}`, igual que los ficheros del frontend.
Sale el idioma de la cabecera `Accept-Language`, que pone `apiFetch`; sin
cabecera, o con un idioma que no está, el catalán (`spring.web.locale=ca`).

```java
throw new InvalidInputException("debts.name-missing");                    // 400
throw new ConflictException("accounts.delete-has-transactions", "count", n); // 409
throw new NotFoundException("not-found.account", id);                     // 404
return Map.of("message", Messages.get("upload.all-exist"));               // respuesta normal
return Map.of("error", Messages.forUser(exception));                      // un error capturado
```

- **Un texto nuevo va a los cuatro ficheros.** `MessagesTest` falla si falta en
  alguno, si no lleva los mismos `{valores}`, si el código usa una clave que no
  existe o si sobra alguna.
- **Frases enteras en una clave.** Un mensaje dentro de otro («Línia 4 de
  l'extracte: {reason}») se pasa como `Message` y se traduce en el mismo idioma;
  el de una excepción capturada, con `Messages.reasonOf(exception)`.
- **Con `"count"` numérico se elige el plural**: `clave.one` y `clave.other`.
- **Los importes como `BigDecimal`**: salen con el formato del idioma (1.234,50 /
  1,234.50). Un número de pagos o un identificador, como entero, sin puntos de
  miles.
- **`getMessage()` es siempre el catalán**: es lo que va al log y lo que miran
  los tests con `hasMessageContaining`. Lo que llega al navegador pasa por
  `Messages.forUser`, que ya hacen `ApiErrorHandler` y `ClientErrors`.
- No se usa `MessageSource` de Spring: su `MessageFormat` trata el apóstrofo
  como carácter especial y el catalán va lleno («L'import {0}» perdía el valor).

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
