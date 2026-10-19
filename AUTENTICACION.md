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
| `AUTH_MAX_LOGIN_ATTEMPTS` | `5` | Intentos fallidos seguidos desde una misma IP antes de bloquearla |
| `AUTH_GLOBAL_MAX_LOGIN_FAILURES` | `50` | Intentos fallidos entre todas las IP que bloquean el login para todos… |
| `AUTH_GLOBAL_LOGIN_FAILURES_WINDOW` | `PT10M` (10 minutos) | …si llegan en este rato |
| `AUTH_LOGIN_LOCK_DURATION` | `PT1M` (1 minuto) | Duración del primer bloqueo |
| `AUTH_LOGIN_LOCK_MAX_DURATION` | `PT15M` (15 minutos) | Hasta dónde crece el bloqueo |
| `AUTH_SECURE_COOKIE` | `false` | Ponlo a `true` **solo si sirves por HTTPS** |

Hasta octubre de 2026 `docker-compose.yml` no pasaba ninguna de estas opciones
al backend, y ponerlas en el `.env` no hacía nada. Ahora sí.

Cada bloqueo seguido dura el doble que el anterior (1, 2, 4, 8 minutos…) hasta
el máximo, y un login correcto desde esa IP lo reinicia. Con un minuto fijo se
podían probar unas 7.200 contraseñas al día desde cualquier aparato de la wifi;
con el máximo de 15 minutos, unas 480. Quien se equivoca escribiéndola solo
espera el primer minuto.

## Cómo funciona

La sesión es un JWT firmado con HMAC-SHA256 que viaja en una cookie
`budget_session`. El token lleva el identificador de una fila de la tabla
`sessions` (el `jti`): cada vez que se entra se crea una, y **salir la borra**.
Desde ese momento el token ya no abre nada, aunque la firma y la caducidad sigan
siendo buenas.

Hasta octubre de 2026 no había nada guardado en el servidor: salir solo le
pedía al navegador que borrara la cookie, y una copia del token (de un
ordenador compartido, de una copia de seguridad del navegador) seguía sirviendo
hasta que caducaba, siete días después.

Cada petición comprueba que su sesión sigue abierta. Para no ir a la base de
datos cada vez, una sesión confirmada se da por buena durante un minuto, y de
paso se apunta su último uso. Salir y cerrar las demás sesiones vacían esa
memoria al momento: el token deja de servir en la petición siguiente. Solo un
cambio hecho directamente en la base de datos (borrar filas a mano, restaurar
una copia) puede tardar ese minuto en notarse. Las sesiones caducadas se borran
solas cada hora.

**Al aplicar la migración 020 hay que volver a entrar**: los tokens de antes no
llevan sesión y se rechazan.

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
| `POST /auth/logout` | no | Cierra la sesión en el servidor y caduca la cookie |
| `GET /auth/me` | **sí** | El frontend lo llama al arrancar; un 401 significa "no hay sesión" |
| `GET /auth/sessions` | **sí** | Las sesiones abiertas, con la de la petición marcada (`actual`) |
| `DELETE /auth/sessions/others` | **sí** | Cierra todas las sesiones menos la de la petición |
| Todo lo demás | **sí** | 401 sin sesión |

### Sesiones abiertas

Configuración enseña las sesiones abiertas: el navegador y el sistema de cada
una (sacados del `User-Agent`), cuándo se abrió y cuándo se usó por última vez,
con la de ese navegador marcada. Si no se reconoce alguna, **«Cerrar las demás
sesiones»** las cierra todas menos esa, y los otros navegadores vuelven a la
pantalla de entrada. El identificador de las sesiones no sale de la API.

### Protecciones

- **Mensaje de error único.** El backend nunca dice si ha fallado el usuario o
  la contraseña; decirlo sería regalar media credencial.
- **Tiempo de respuesta constante.** La comprobación de la contraseña se
  ejecuta aunque el usuario no coincida, para que el tiempo no delate cuál de
  los dos campos ha fallado.
- **Límite de intentos, por IP y para todos.** Cinco fallos seguidos desde una
  IP bloquean esa IP, y el bloqueo crece con cada ronda; desde el móvil o el PC
  se sigue pudiendo entrar. Hasta octubre de 2026 el contador era uno para todo
  el backend, y cualquiera de la wifi podía dejarte fuera a propósito. Como
  solo por IP no frenaría a quien vaya cambiando de dirección, hay además un
  límite para todos, mucho más alto: 50 fallos entre todas las IP en 10 minutos
  cierran el login para todo el mundo, con el mismo bloqueo que crece. En el
  día a día no se llega.

  - **La IP es la de la conexión**, nunca la cabecera `X-Forwarded-For`: no hay
    proxy delante y cualquiera podría inventársela para empezar de cero en cada
    intento. Si algún día se pone uno (Caddy, en el servidor), hay que decirle a
    Spring cuál es de confianza (`server.forward-headers-strategy=native` y
    `server.tomcat.remoteip.internal-proxies` con su IP y nada más): las redes
    privadas que acepta por defecto incluyen la wifi de casa.
  - **Una IPv6 cuenta por su bloque /64**, que es lo que tiene una sola
    conexión: contándolas una a una, bastaría con cambiar los últimos dígitos.
  - **Memoria acotada.** Como mucho se recuerdan 10.000 direcciones, y una que
    lleva una hora sin fallar ni estar bloqueada se olvida. Reiniciar el backend
    lo limpia todo.
  - **Con Docker Desktop (Windows o Mac) el backend puede ver todas las
    conexiones con la misma IP**, la de Docker; entonces el límite por IP se
    comporta como el de antes, para todos. Cada intento fallido sale en el log
    con la IP que se ha contado (`docker compose logs backend`), y así se ve
    cuál llega. En un Linux, como el servidor, llega la de verdad.

## Limitaciones conocidas

- **Es un solo usuario.** No hay tabla de usuarios ni aislamiento de datos:
  quien inicia sesión ve todo. Para varios usuarios haría falta `user_id` en
  las ocho tablas de datos y filtrar todas las consultas.
- **No hay forma de cambiar la contraseña desde la aplicación.** Se cambia en
  el `.env` y se reinicia el backend.
- **Las sesiones se cierran de una en una solo desde su navegador.** Desde
  otro, «Cerrar las demás sesiones» las cierra todas menos la propia; no se
  puede elegir una. Cambiar `JWT_SECRET` sigue invalidándolas todas de golpe,
  incluida la propia.
- **El frontend no está protegido**, solo la API. Cualquiera puede cargar el
  HTML y el JavaScript en el puerto 3000; lo que no puede es obtener ningún
  dato sin sesión.
