# Autenticación

La API estaba completamente abierta: cualquiera que llegara al puerto 8000
podía leer y borrar los datos financieros. Ahora todo requiere sesión, salvo
el propio inicio de sesión.

## Configuración

Añade esto a tu `.env`. Sin `AUTH_USERNAME`, sin contraseña o sin `JWT_SECRET`
**el backend no arranca**: es preferible que falle a que se quede abierto sin
que nadie se dé cuenta.

```bash
AUTH_USERNAME=tu-usuario
AUTH_PASSWORD=tu-contraseña
JWT_SECRET=<cadena aleatoria de 32 caracteres como mínimo>
```

Para generar el secreto:

```bash
node -e "console.log(require('crypto').randomBytes(48).toString('base64url'))"
```

### Guardar la contraseña como hash

`AUTH_PASSWORD` queda escrita en claro en el `.env`. Si prefieres evitarlo,
usa `AUTH_PASSWORD_HASH` con un hash BCrypt, que tiene prioridad sobre la
contraseña en claro:

```bash
AUTH_PASSWORD_HASH=$2a$10$...
```

En ambos casos la comparación se hace siempre contra un hash BCrypt; la
diferencia es solo si la contraseña en claro llega a tocar el disco.

### Opciones adicionales

| Variable | Por defecto | Qué hace |
|---|---|---|
| `AUTH_SESSION_DURATION` | `P7D` (7 días) | Cuánto dura la sesión |
| `AUTH_MAX_LOGIN_ATTEMPTS` | `5` | Intentos fallidos antes de bloquear |
| `AUTH_LOGIN_LOCK_DURATION` | `PT1M` (1 minuto) | Duración del bloqueo |
| `AUTH_SECURE_COOKIE` | `false` | Ponlo a `true` **solo si sirves por HTTPS** |

## Cómo funciona

La sesión es un JWT firmado con HMAC-SHA256 que viaja en una cookie
`budget_session`. No hay estado de sesión en el servidor.

La cookie es **httpOnly**, así que JavaScript no puede leerla: un XSS futuro no
podría robarla, cosa que sí pasaría con un token en `localStorage`. Y es
**SameSite=Strict**, lo que impide que se envíe en peticiones iniciadas desde
otro sitio; esa es la protección contra CSRF, y por eso no hace falta el token
CSRF de Spring.

El frontend (`localhost:3000`) y el backend (`localhost:8000`) se consideran el
mismo *site* porque el puerto no forma parte del *site*, así que la cookie sí
viaja en las llamadas normales de la aplicación. Como sí son orígenes
distintos, CORS necesita `allowCredentials`, que a su vez exige una lista
concreta de orígenes: con `*` el navegador lo rechazaría.

### Endpoints

| Ruta | Sesión | Qué hace |
|---|---|---|
| `POST /auth/login` | no | Devuelve la cookie si las credenciales son correctas |
| `POST /auth/logout` | no | Caduca la cookie |
| `GET /auth/me` | **sí** | El frontend lo llama al arrancar; un 401 significa "no hay sesión" |
| Todo lo demás | **sí** | 401 sin sesión |

### Protecciones

- **Mensaje de error único.** El backend nunca dice si ha fallado el usuario o
  la contraseña; decirlo sería regalar media credencial.
- **Tiempo de respuesta constante.** La comprobación de la contraseña se
  ejecuta aunque el usuario no coincida, para que el tiempo no delate cuál de
  los dos campos ha fallado.
- **Límite de intentos.** Cinco fallos seguidos bloquean el login un minuto. Es
  un contador en memoria y global, no por IP: con un solo usuario no tiene
  sentido distinguir quién prueba, y contar por IP solo serviría para que un
  atacante las rotase. Reiniciar el backend limpia el contador.

## Limitaciones conocidas

- **Es un solo usuario.** No hay tabla de usuarios ni aislamiento de datos:
  quien inicia sesión ve todo. Para varios usuarios haría falta `user_id` en
  las ocho tablas de datos y filtrar todas las consultas.
- **No hay forma de cambiar la contraseña desde la aplicación.** Se cambia en
  el `.env` y se reinicia el backend.
- **No se pueden revocar sesiones.** Al ser un JWT sin estado, un token robado
  vale hasta que caduca. Cambiar `JWT_SECRET` invalida todas las sesiones de
  golpe, que es el procedimiento de emergencia.
- **El frontend no está protegido**, solo la API. Cualquiera puede cargar el
  HTML y el JavaScript en el puerto 3000; lo que no puede es obtener ningún
  dato sin sesión.
