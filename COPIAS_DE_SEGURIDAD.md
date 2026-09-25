# Copias de seguridad

La base de datos tiene todo el historial financiero, y hasta ahora no había
ninguna copia: si se borraba el volumen de Docker o fallaba el disco, se perdía.

| Script | Qué hace |
|---|---|
| `scripts/backup.bat` / `scripts/backup.sh` | Copia la base de datos a un fichero `.sql` con fecha |
| `scripts/restore.bat` / `scripts/restore.sh` | Sustituye la base de datos por una copia |

`.bat` para Windows (`cmd`), `.sh` para Linux y macOS (y el futuro servidor).
Hacen lo mismo; si cambias uno, cambia el otro.

## Hacer una copia

Con Docker en marcha, desde la carpeta del proyecto:

```cmd
scripts\backup.bat
```

Crea `backups\budget_AAAA-MM-DD_HHMMSS.sql` y borra las de más de 30 días.

**Dónde guardarlas.** Una copia en el mismo disco no salva nada si el disco
falla. Mejor una carpeta sincronizada (OneDrive, Google Drive…). Se indica como
argumento o, para no repetirlo, con una variable de entorno:

```cmd
scripts\backup.bat "C:\Users\TU_USUARIO\OneDrive\BudgetAI\copias"
```

```cmd
setx BUDGET_BACKUP_DIR "C:\Users\TU_USUARIO\OneDrive\BudgetAI\copias"
```

`setx` solo afecta a las ventanas de `cmd` que abras después.

La carpeta `backups/` del proyecto está en `.gitignore`: son datos reales y no
deben acabar en el repositorio.

## Programarla cada día (Windows)

Con el **Programador de tareas**:

1. `Win + R` → `taskschd.msc` → **Crear tarea básica**.
2. Nombre: `Budget AI - copia`. Desencadenador: **Diariamente**, a una hora en
   la que el PC suela estar encendido.
3. Acción: **Iniciar un programa**.
   - Programa: `C:\Projectes\budget-ai\scripts\backup.bat`
   - Argumentos: la carpeta de destino entre comillas, por ejemplo
     `"C:\Users\TU_USUARIO\OneDrive\BudgetAI\copias"`
   - Iniciar en: `C:\Projectes\budget-ai`
4. Al terminar, abre la tarea → **Configuración** → marca **Ejecutar la tarea
   lo antes posible después de perder un inicio programado**. Así, si el PC
   estaba apagado a esa hora, la copia se hace al encenderlo.

Docker Desktop tiene que estar abierto para que la copia funcione. Si no lo
está, el script falla sin dejar ningún fichero a medias.

## Restaurar una copia

```cmd
scripts\restore.bat backups\budget_2026-09-25_210000.sql
```

**Sustituye todo** lo que hay ahora por el contenido de la copia. Por eso:

1. Pide que escribas `SI` para continuar.
2. Antes de tocar nada, **hace una copia del estado actual**, por si te
   equivocas de fichero.
3. Para el backend mientras restaura y lo vuelve a arrancar al acabar.
4. Lo hace todo en **una sola transacción**: si algo falla a mitad, la base de
   datos se queda exactamente como estaba.

### Por qué se comprueba que la copia esté completa

`pg_dump` acaba siempre con la línea `-- PostgreSQL database dump complete`.
Los dos scripts la buscan: al hacer la copia, para descartar una que se haya
cortado, y antes de restaurar.

No es un detalle. Una copia cortada **no da ningún error al restaurarla**:
`psql` ignora la última sentencia a medias y termina bien, con las tablas
borradas y sin volver a crearlas. Se comprobó al escribir los scripts: una
copia cortada dejó la base de datos con cero categorías y el comando
diciendo que había ido bien.

## Probar que las copias sirven

Una copia que nunca se ha restaurado no se sabe si sirve. Al menos la primera
vez, y de vez en cuando:

1. `scripts\backup.bat`
2. Cambia algo pequeño en la app (por ejemplo, crea una cuenta de prueba).
3. `scripts\restore.bat` con la copia del paso 1.
4. Comprueba que el cambio del paso 2 ha desaparecido y que el resto está bien.

## Restaurar en otro servidor

Las copias se hacen sin propietario ni permisos (`--no-owner --no-privileges`),
así que se pueden restaurar en una base de datos nueva aunque el usuario tenga
otro nombre. Es lo que hará falta para pasar los datos al servidor.
