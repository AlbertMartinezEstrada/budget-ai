@echo off
rem Copia de seguretat de la base de dades de Budget AI (Windows).
rem
rem   scripts\backup.bat [carpeta]
rem
rem La carpeta de desti, per ordre: l'argument, BUDGET_BACKUP_DIR del .env,
rem o "backups" a l'arrel del projecte. Val la pena que sigui una carpeta
rem sincronitzada (OneDrive, Google Drive...): una copia al mateix disc no
rem salva res si el disc falla.
rem
rem   BUDGET_BACKUP_DIR=G:\Mi unidad\BudgetAI\copias
rem
rem Es conserven les copies dels ultims 30 dies.
rem
rem Cada execucio queda apuntada a logs\backup.log. Des del Programador de
rem tasques no es veu cap missatge, i sense el registre no hi ha manera de
rem saber per que ha fallat.

setlocal EnableExtensions

set "PROJECT_DIR=%~dp0.."
set "LOG_DIR=%PROJECT_DIR%\logs"
set "LOG_FILE=%LOG_DIR%\backup.log"
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"

set "BACKUP_DIR=%~1"

rem Es llegeix del .env com la resta de la configuracio. Es busca al costat
rem de l'script i no a la carpeta actual, perque el Programador de tasques
rem l'executa des d'on vol. %%~b treu les cometes si n'hi ha.
if "%BACKUP_DIR%"=="" if exist "%PROJECT_DIR%\.env" (
    for /f "usebackq eol=# tokens=1,* delims==" %%a in ("%PROJECT_DIR%\.env") do (
        if /i "%%a"=="BUDGET_BACKUP_DIR" set "BACKUP_DIR=%%~b"
    )
)
if "%BACKUP_DIR%"=="" set "BACKUP_DIR=%PROJECT_DIR%\backups"

call :log "Inici. Carpeta de desti: %BACKUP_DIR%"

rem Sense Docker en marxa, docker exec pot quedar-se esperant. Es comprova
rem abans per fallar de seguida i amb un missatge clar.
docker info >nul 2>&1
if errorlevel 1 (
    call :log "ERROR: Docker no esta en marxa. Obre Docker Desktop."
    exit /b 1
)

if not exist "%BACKUP_DIR%" mkdir "%BACKUP_DIR%" 2>nul
if not exist "%BACKUP_DIR%" (
    call :log "ERROR: no es pot crear la carpeta. Si es a Google Drive, esta obert?"
    exit /b 1
)

rem %date% depen de l'idioma de Windows; PowerShell dona sempre el mateix format.
rem -NonInteractive i "<NUL": llancat des del Programador de tasques, PowerShell
rem es quedava esperant una entrada que no arriba mai i la tasca no acabava.
for /f %%i in ('powershell -NoProfile -NonInteractive -Command "Get-Date -Format yyyy-MM-dd_HHmmss" ^<NUL') do set "STAMP=%%i"
if "%STAMP%"=="" (
    call :log "ERROR: no s'ha pogut obtenir la data amb PowerShell."
    exit /b 1
)
set "BACKUP_FILE=%BACKUP_DIR%\budget_%STAMP%.sql"

rem L'usuari i la base de dades es llegeixen dins del contenidor, que ja els
rem te: aixi no cal carregar el .env. --clean fa que restaurar la copia
rem substitueixi el que hi hagi en comptes de barrejar-s'hi; sense propietari
rem ni permisos, es pot restaurar en un altre servidor amb un altre usuari.
rem Els errors de docker van al registre.
docker exec budget_db sh -c "pg_dump -U $POSTGRES_USER -d $POSTGRES_DB --clean --if-exists --no-owner --no-privileges" > "%BACKUP_FILE%" 2>>"%LOG_FILE%" <NUL
if errorlevel 1 goto :failed

rem pg_dump acaba sempre amb aquesta linia. Si no hi es, la copia s'ha tallat
rem pel cami, encara que docker no s'hagi queixat, i no serveix: restaurar-la
rem esborraria les taules sense tornar-les a crear.
findstr /c:"PostgreSQL database dump complete" "%BACKUP_FILE%" >nul
if errorlevel 1 goto :failed

call :log "Copia feta: %BACKUP_FILE%"

rem forfiles es queixa si no troba res per esborrar; no es cap error.
forfiles /p "%BACKUP_DIR%" /m budget_*.sql /d -30 /c "cmd /c del @path" >nul 2>&1

exit /b 0

:failed
if exist "%BACKUP_FILE%" del "%BACKUP_FILE%"
call :log "ERROR: no s'ha pogut fer la copia. Esta en marxa el contenidor budget_db?"
exit /b 1

rem Escriu el missatge a la pantalla i al registre, amb data i hora.
:log
echo %~1
>>"%LOG_FILE%" echo [%date% %time%] %~1
exit /b 0
