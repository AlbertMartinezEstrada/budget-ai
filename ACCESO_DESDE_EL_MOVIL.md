# Acceso desde el móvil (Tailscale)

La aplicación vive en tu PC y **no se abre a internet**. Para usarla desde el
móvil fuera de casa se usa Tailscale: una red privada entre tus aparatos,
gratuita para uso personal. Solo tus aparatos, con tu cuenta, pueden llegar a
la aplicación; no hay que abrir ningún puerto del router.

Solo funciona mientras el PC esté encendido y con Docker en marcha.

## 1. Instalar Tailscale

1. Crea una cuenta en <https://tailscale.com> (plan personal, gratuito).
2. Instala Tailscale en el **PC** y en el **móvil** e inicia sesión en los dos
   con la misma cuenta.
3. En el PC, abre Tailscale y apunta el **nombre del equipo** (por ejemplo
   `mi-pc`) o su **IP de Tailscale** (empieza por `100.`).

## 2. Permitir esa dirección en la aplicación

El navegador solo deja hablar con el backend a las direcciones que el backend
acepta (CORS). Añade la del PC al `.env`, **sin quitar las de `localhost`**:

```
CORS_ALLOWED_ORIGINS=http://localhost:3000,http://127.0.0.1:3000,http://mi-pc:3000
```

Si usas la IP en vez del nombre: `http://100.x.y.z:3000`. Tiene que coincidir
exactamente con lo que escribirás en el navegador del móvil.

Después, reinicia el backend para que lo lea:

```cmd
docker compose up -d backend
```

## 3. Entrar desde el móvil

Con Tailscale activado en el móvil, abre en el navegador:

```
http://mi-pc:3000
```

Pruébalo una vez **con los datos móviles** (sin wifi de casa): así compruebas
que pasa por Tailscale y no por la red local.

## Si no conecta

- **La página no carga:** el PC está apagado, Docker Desktop cerrado, o
  Tailscale desactivado en alguno de los dos aparatos.
- **La página carga pero no entra / no salen datos:** la dirección no está en
  `CORS_ALLOWED_ORIGINS`, o no coincide exactamente (nombre contra IP,
  `http` contra `https`, el puerto). Revisa el paso 2 y reinicia el backend.
- **Windows pregunta por el firewall** la primera vez: permite el acceso en
  redes privadas. Si nunca lo preguntó y no conecta, revisa en *Firewall de
  Windows → Permitir una aplicación* que Docker Desktop tenga acceso.

## Seguridad

- La conexión entre el móvil y el PC va cifrada por Tailscale, aunque la
  dirección empiece por `http://`.
- Los puertos 3000 y 8000 también son accesibles desde tu wifi de casa, como
  hasta ahora. La base de datos no: solo desde el propio PC.
- Si pierdes el móvil, quítalo de tu red en el panel de Tailscale.
