#!/usr/bin/env bash
# Aplica a la base de dades les migracions que li falten (Linux i macOS).
#
#   scripts/migrate.sh                 fa una còpia i aplica les pendents
#   scripts/migrate.sh --estat         només diu quines hi ha i quines falten
#   scripts/migrate.sh --sense-copia   aplica sense fer la còpia abans
#
# Les migracions són els fitxers backend-java/migrations/NNN_*.sql, per ordre.
# Les aplicades s'apunten a la taula schema_migrations i la vegada següent se
# salten. Després cal reconstruir el backend: docker compose up -d --build backend
#
# Abans s'aplicaven a mà, una per una, i era fàcil saltar-se'n alguna: el
# backend no arrencava ("missing column vigent_des_de") i res deia quines
# faltaven. L'ordre amb cometes dobles, a més, no funcionava al Mac.
#
# Una base de dades sense schema_migrations les ha rebut a mà i no se sap
# quines. No es poden tornar a aplicar totes a cegues: la 008 falla si ja hi
# és, i la 004 tornaria les categories als blocs d'origen. Per això es mira
# l'esquema i s'apunten com a aplicades les que ja s'hi veuen. Les migracions
# noves (013 en endavant) no ho necessiten: s'han d'escriure perquè aplicar-les
# dues vegades no faci res (IF NOT EXISTS, ON CONFLICT DO NOTHING).

set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
migrations_dir="$script_dir/../backend-java/migrations"
container="budget_db"

usage() {
    sed -n '2,6p' "$0" | sed 's/^# \{0,1\}//'
}

mode="aplicar"
make_backup=1
for argument in "$@"; do
    case "$argument" in
        --estat) mode="estat" ;;
        --sense-copia) make_backup=0 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Opció desconeguda: $argument" >&2; usage >&2; exit 1 ;;
    esac
done

# psql dins del contenidor, amb l'usuari i la base de dades que ja hi té. Les
# cometes simples són a posta: amb dobles, el shell d'aquí substituiria
# $POSTGRES_USER per una variable seva, buida, i psql rebria "-U -d".
run_sql() {
    docker exec -i "$container" sh -c \
        'psql -X -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" "$@"' psql "$@"
}

if ! docker info > /dev/null 2>&1; then
    echo "ERROR: Docker no està en marxa. Obre Docker Desktop i torna-ho a provar." >&2
    exit 1
fi
if [ "$(docker inspect -f '{{.State.Running}}' "$container" 2> /dev/null)" != "true" ]; then
    echo "ERROR: el contenidor $container no està en marxa. Arrenca'l amb «docker compose up -d db»." >&2
    exit 1
fi

# Crea el registre, si no hi és, i hi apunta les migracions que ja es veuen a
# l'esquema. Es fa sempre i no només la primera vegada: una còpia restaurada
# o una migració aplicada a mà també queden ben apuntades.
#
# Les que només afegeixen categories (004, 005, 007) es donen per aplicades
# també si n'hi ha alguna de posterior que canvia l'esquema: aplicar-les de nou
# tornaria les categories a com eren, i que en faltin només vol dir que falta
# alguna categoria per defecte.
register_sql="
SET client_min_messages = warning;
CREATE TABLE IF NOT EXISTS schema_migrations (
    nom VARCHAR(255) PRIMARY KEY,
    aplicada_el TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
WITH esquema AS (
    SELECT
        EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'categories_tipus_cost_check'
                AND pg_get_constraintdef(oid) LIKE '%INCOME%') AS seccio_ingressos,
        EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'transactions_hash_compte_key') AS hash_per_compte,
        to_regclass('public.import_rules') IS NOT NULL AS regles,
        EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_name = 'recurring_transactions' AND column_name = 'vigent_des_de') AS vigencia,
        to_regclass('public.debts') IS NOT NULL AS deutes,
        to_regclass('public.transaction_parts') IS NOT NULL AS parts
)
INSERT INTO schema_migrations (nom)
SELECT migracio.nom
FROM esquema, LATERAL (VALUES
    ('001_money_to_numeric.sql', EXISTS (SELECT 1 FROM information_schema.columns
        WHERE table_name = 'transactions' AND column_name = 'import' AND data_type = 'numeric')),
    ('002_categories_jerarquiques.sql', EXISTS (SELECT 1 FROM information_schema.columns
        WHERE table_name = 'categories' AND column_name = 'parent_id')),
    ('003_percentatges_sou.sql', to_regclass('public.monthly_income') IS NOT NULL),
    ('004_blocs_de_repartiment.sql', EXISTS (SELECT 1 FROM categories WHERE nom = 'Gast mensual')
        OR seccio_ingressos OR hash_per_compte OR regles OR vigencia OR deutes OR parts),
    ('005_blocs_de_despeses_fixes.sql', EXISTS (SELECT 1 FROM categories WHERE nom = 'Llar')
        OR seccio_ingressos OR hash_per_compte OR regles OR vigencia OR deutes OR parts),
    ('006_seccio_ingressos.sql', seccio_ingressos),
    ('007_categoria_delivery.sql', EXISTS (SELECT 1 FROM categories WHERE nom = 'Delivery')
        OR hash_per_compte OR regles OR vigencia OR deutes OR parts),
    ('008_comptes_i_traspassos.sql', hash_per_compte),
    ('009_regles_importacio.sql', regles),
    ('010_vigencia_costos_fixos.sql', vigencia),
    ('011_deutes.sql', deutes),
    ('012_parts_moviments.sql', parts)
) AS migracio(nom, aplicada)
WHERE migracio.aplicada
ON CONFLICT (nom) DO NOTHING;
"

# Amb --estat, el mateix càlcul dins d'una transacció que es desfà: no canvia res.
if [ "$mode" = "estat" ]; then
    applied="$(printf 'BEGIN;\n%s\nSELECT nom FROM schema_migrations;\nROLLBACK;\n' "$register_sql" | run_sql -tA)"
else
    applied="$(printf '%s\nSELECT nom FROM schema_migrations;\n' "$register_sql" | run_sql -tA)"
fi

# Amb un comptador i no amb ${#pending[@]}: el bash que porta el Mac (3.2) dona
# "unbound variable" en expandir un array buit amb set -u.
pending=()
pending_count=0
for file in "$migrations_dir"/[0-9][0-9][0-9]_*.sql; do
    name="$(basename "$file")"
    if printf '%s\n' "$applied" | grep -qxF "$name"; then
        if [ "$mode" = "estat" ]; then echo "  ✓ $name"; fi
    else
        if [ "$mode" = "estat" ]; then echo "  · $name  (pendent)"; fi
        pending+=("$file")
        pending_count=$((pending_count + 1))
    fi
done

if [ "$mode" = "estat" ]; then
    echo
    echo "$pending_count pendents. Per aplicar-les: scripts/migrate.sh"
    exit 0
fi

if [ "$pending_count" -eq 0 ]; then
    echo "La base de dades ja té totes les migracions. No cal fer res."
    exit 0
fi

echo "Migracions pendents:"
for file in "${pending[@]}"; do echo "  · $(basename "$file")"; done
echo

if [ "$make_backup" -eq 1 ]; then
    echo "Primer, una còpia de seguretat:"
    if ! "$script_dir/backup.sh"; then
        echo "ERROR: no s'ha pogut fer la còpia, així que no s'aplica res." >&2
        echo "Si vols aplicar-les igualment: scripts/migrate.sh --sense-copia" >&2
        exit 1
    fi
    echo
fi

for file in "${pending[@]}"; do
    name="$(basename "$file")"
    echo "→ $name"
    if ! output="$(run_sql < "$file" 2>&1)"; then
        echo "$output" >&2
        echo >&2
        echo "ERROR: la migració $name ha fallat. Les anteriors queden aplicades i apuntades;" >&2
        echo "aquesta i les següents, no. Si cal tornar enrere, la còpia d'abans és a la carpeta de còpies." >&2
        exit 1
    fi
    # Un avís de "ja existeix" no és cap error: les migracions es poden repetir.
    if [ -n "$output" ]; then echo "$output" | sed 's/^/    /'; fi
    run_sql -c "INSERT INTO schema_migrations (nom) VALUES ('$name') ON CONFLICT (nom) DO NOTHING;" < /dev/null
done

echo
echo "Fet: $pending_count migracions aplicades."
echo "Ara reconstrueix el backend perquè agafi el codi nou:"
echo "  docker compose up -d --build backend"
