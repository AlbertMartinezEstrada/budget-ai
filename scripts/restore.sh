#!/usr/bin/env bash
# Restaura una còpia de seguretat de Budget AI (Linux i macOS).
#
#   scripts/restore.sh backups/budget_2026-09-25_030000.sql
#
# SUBSTITUEIX tot el que hi ha ara a la base de dades pel contingut de la
# còpia. Abans de restaurar en fa una de l'estat actual, per si de cas.
# Mateix comportament que restore.bat; si en canvies un, canvia l'altre.

set -euo pipefail

if [ $# -ne 1 ]; then
    echo "Ús: scripts/restore.sh <fitxer.sql>" >&2
    exit 1
fi
if [ ! -f "$1" ]; then
    echo "ERROR: no existeix $1" >&2
    exit 1
fi
# Ruta absoluta: més avall es canvia de carpeta.
backup_file="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
script_dir="$(cd "$(dirname "$0")" && pwd)"

# Una còpia tallada no dona cap error en restaurar-la: psql ignora l'última
# sentència a mitges i acaba bé, amb les taules esborrades i sense tornar-les a
# crear. Per això es comprova abans que sigui sencera.
if ! grep -q "PostgreSQL database dump complete" "$backup_file"; then
    echo "ERROR: la còpia està incompleta; no es restaura." >&2
    exit 1
fi

echo "Això SUBSTITUIRÀ totes les dades actuals per les de:"
echo "  $backup_file"
read -r -p "Escriu SI per continuar: " confirm
if [ "$confirm" != "SI" ] && [ "$confirm" != "si" ]; then
    echo "Cancel·lat."
    exit 1
fi

echo "Còpia de l'estat actual abans de restaurar..."
"$script_dir/backup.sh"

# docker compose s'ha d'executar des de l'arrel del projecte.
cd "$script_dir/.."

# Amb el backend parat ningú escriu a mitja restauració, i es torna a
# engegar passi el que passi.
docker compose stop backend
trap 'docker compose start backend' EXIT

# Tot en una sola transacció i aturant-se al primer error: si alguna cosa
# falla a mitges, la base de dades es queda exactament com estava.
if ! docker exec -i budget_db sh -c \
    'psql -v ON_ERROR_STOP=1 --single-transaction -q -o /dev/null -U "$POSTGRES_USER" -d "$POSTGRES_DB"' \
    < "$backup_file"; then
    echo "ERROR: la restauració ha fallat i no s'ha canviat res." >&2
    exit 1
fi

echo "Restaurada: $backup_file"
