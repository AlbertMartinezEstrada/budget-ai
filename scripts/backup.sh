#!/usr/bin/env bash
# Còpia de seguretat de la base de dades de Budget AI (Linux i macOS).
#
#   scripts/backup.sh [carpeta]
#
# La carpeta de destí, per ordre: l'argument, BUDGET_BACKUP_DIR del .env, o
# "backups" a l'arrel del projecte. Val la pena que sigui fora del disc on
# viu la base de dades: una còpia al mateix disc no salva res si el disc falla.
#
# Es conserven les còpies dels últims 30 dies. Cada execució queda apuntada a
# logs/backup.log: des de cron no es veu cap missatge. Mateix comportament que
# backup.bat; si en canvies un, canvia l'altre.

set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$script_dir/../logs"
log_file="$script_dir/../logs/backup.log"

# Escriu el missatge a la pantalla i al registre, amb data i hora.
log() {
    echo "$1"
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1" >> "$log_file"
}

# Es llegeix del .env com la resta de la configuració, buscant-lo al costat de
# l'script i no a la carpeta actual. Sense carregar-lo sencer: només cal aquesta
# línia, i el .env té secrets. Es treuen les cometes i el \r d'un .env de Windows.
env_backup_dir=""
if [ -f "$script_dir/../.env" ]; then
    env_backup_dir="$({ grep -E '^BUDGET_BACKUP_DIR=' "$script_dir/../.env" || true; } | tail -n 1 | cut -d= -f2- \
        | tr -d '\r' | sed -e 's/^"\(.*\)"$/\1/' -e "s/^'\(.*\)'$/\1/")"
fi

backup_dir="${1:-${env_backup_dir:-$script_dir/../backups}}"
log "Inici. Carpeta de destí: $backup_dir"

# Sense Docker en marxa, docker exec pot quedar-se esperant: millor fallar de
# seguida i amb un missatge clar.
if ! docker info > /dev/null 2>&1; then
    log "ERROR: Docker no està en marxa."
    exit 1
fi

if ! mkdir -p "$backup_dir" 2> /dev/null; then
    log "ERROR: no es pot crear la carpeta $backup_dir"
    exit 1
fi

backup_file="$backup_dir/budget_$(date +%Y-%m-%d_%H%M%S).sql"

fail() {
    rm -f "$backup_file"
    log "ERROR: no s'ha pogut fer la còpia. Està en marxa el contenidor budget_db?" >&2
    exit 1
}

# L'usuari i la base de dades es llegeixen dins del contenidor, que ja els té:
# així no cal carregar el .env. --clean fa que restaurar la còpia substitueixi
# el que hi hagi; sense propietari ni permisos, es pot restaurar en un altre
# servidor amb un altre usuari.
docker exec budget_db sh -c \
    'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists --no-owner --no-privileges' \
    > "$backup_file" 2>> "$log_file" < /dev/null || fail

# pg_dump acaba sempre amb aquesta línia. Si no hi és, la còpia s'ha tallat pel
# camí i no serveix: restaurar-la esborraria les taules sense tornar-les a crear.
grep -q "PostgreSQL database dump complete" "$backup_file" || fail

log "Còpia feta: $backup_file"

find "$backup_dir" -maxdepth 1 -name 'budget_*.sql' -mtime +30 -delete
