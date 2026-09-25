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

setlocal EnableExtensions

set "BACKUP_DIR=%~1"

rem Es llegeix del .env com la resta de la configuracio. Es busca al costat
rem de l'script i no a la carpeta actual, perque el Programador de tasques
rem l'executa des d'on vol. %%~b treu les cometes si n'hi ha.
if "%BACKUP_DIR%"=="" if exist "%~dp0..\.env" (
    for /f "usebackq eol=# tokens=1,* delims==" %%a in ("%~dp0..\.env") do (
        if /i "%%a"=="BUDGET_BACKUP_DIR" set "BACKUP_DIR=%%~b"
    )
)

if "%BACKUP_DIR%"=="" set "BACKUP_DIR=%~dp0..\backups"
if not exist "%BACKUP_DIR%" mkdir "%BACKUP_DIR%"

rem %date% depen de l'idioma de Windows; PowerShell dona sempre el mateix format.
for /f %%i in ('powershell -NoProfile -Command "Get-Date -Format yyyy-MM-dd_HHmmss"') do set "STAMP=%%i"
set "BACKUP_FILE=%BACKUP_DIR%\budget_%STAMP%.sql"

rem L'usuari i la base de dades es llegeixen dins del contenidor, que ja els
rem te: aixi no cal carregar el .env. --clean fa que restaurar la copia
rem substitueixi el que hi hagi en comptes de barrejar-s'hi; sense propietari
rem ni permisos, es pot restaurar en un altre servidor amb un altre usuari.
docker exec budget_db sh -c "pg_dump -U $POSTGRES_USER -d $POSTGRES_DB --clean --if-exists --no-owner --no-privileges" > "%BACKUP_FILE%"
if errorlevel 1 goto :failed

rem pg_dump acaba sempre amb aquesta linia. Si no hi es, la copia s'ha tallat
rem pel cami, encara que docker no s'hagi queixat, i no serveix: restaurar-la
rem esborraria les taules sense tornar-les a crear.
findstr /c:"PostgreSQL database dump complete" "%BACKUP_FILE%" >nul
if errorlevel 1 goto :failed

echo Copia feta: %BACKUP_FILE%

rem forfiles es queixa si no troba res per esborrar; no es cap error.
forfiles /p "%BACKUP_DIR%" /m budget_*.sql /d -30 /c "cmd /c del @path" >nul 2>&1

exit /b 0

:failed
if exist "%BACKUP_FILE%" del "%BACKUP_FILE%"
echo ERROR: no s'ha pogut fer la copia. Esta en marxa el contenidor budget_db?
exit /b 1
