@echo off
rem Restaura una copia de seguretat de Budget AI (Windows).
rem
rem   scripts\restore.bat backups\budget_2026-09-25_030000.sql
rem
rem SUBSTITUEIX tot el que hi ha ara a la base de dades pel contingut de la
rem copia. Abans de restaurar en fa una de l'estat actual, per si de cas.

setlocal EnableExtensions

set "BACKUP_FILE=%~1"
if "%BACKUP_FILE%"=="" (
    echo Us: scripts\restore.bat ^<fitxer.sql^>
    exit /b 1
)
if not exist "%BACKUP_FILE%" (
    echo ERROR: no existeix %BACKUP_FILE%
    exit /b 1
)
rem Ruta absoluta: mes avall es canvia de carpeta, i una ruta relativa com
rem "backups\..." deixaria de trobar el fitxer.
for %%f in ("%BACKUP_FILE%") do set "BACKUP_FILE=%%~ff"

rem Una copia tallada no dona cap error en restaurar-la: psql ignora
rem l'ultima sentencia a mitges i acaba be, amb les taules esborrades i sense
rem tornar-les a crear. Per aixo es comprova abans que sigui sencera.
findstr /c:"PostgreSQL database dump complete" "%BACKUP_FILE%" >nul
if errorlevel 1 (
    echo ERROR: la copia esta incompleta; no es restaura.
    exit /b 1
)

echo Aixo SUBSTITUIRA totes les dades actuals per les de:
echo   %BACKUP_FILE%
set /p "CONFIRM=Escriu SI per continuar: "
if /i not "%CONFIRM%"=="SI" (
    echo Cancel.lat.
    exit /b 1
)

rem docker compose s'ha d'executar des de l'arrel del projecte.
pushd "%~dp0.."

echo Copia de l'estat actual abans de restaurar...
call "%~dp0backup.bat"
if errorlevel 1 goto :failed

rem Amb el backend parat ningu escriu a mitja restauracio.
docker compose stop backend

rem Tot en una sola transaccio i aturant-se al primer error: si alguna cosa
rem falla a mitges, la base de dades es queda exactament com estava.
rem La sortida va a /dev/null perque no ompli la pantalla de "setval".
docker exec -i budget_db sh -c "psql -v ON_ERROR_STOP=1 --single-transaction -q -o /dev/null -U $POSTGRES_USER -d $POSTGRES_DB" < "%BACKUP_FILE%"
set "RESTORE_RESULT=%errorlevel%"

docker compose start backend
popd

if not "%RESTORE_RESULT%"=="0" (
    echo ERROR: la restauracio ha fallat i no s'ha canviat res.
    exit /b 1
)
echo Restaurada: %BACKUP_FILE%
exit /b 0

:failed
popd
echo ERROR: no s'ha pogut fer la copia previa; no es restaura res.
exit /b 1
