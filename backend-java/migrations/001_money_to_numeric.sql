-- Migració: passar tots els imports de DOUBLE PRECISION a NUMERIC(15,2)
--
-- Context: init.sql ja declarava DECIMAL(10,2), però les taules de les
-- instal·lacions existents les va acabar creant Hibernate amb ddl-auto=update
-- a partir d'entitats que feien servir Double, de manera que les columnes van
-- quedar en coma flotant. Això es veu a ull nu a les dades: saldos com
-- 112.97000000000018 i balanços com 28.25999999999999.
--
-- Aquesta migració arregla el tipus i, de passada, arrodoneix a dos decimals
-- els valors que ja havien acumulat soroll.
--
-- Com aplicar-la (amb els contenidors aixecats):
--   docker exec -i budget_db psql -U "$DB_USER" -d "$DB_NAME" \
--     < backend-java/migrations/001_money_to_numeric.sql
--
-- És idempotent: si una columna ja és numeric, l'ALTER no la canvia.

BEGIN;

ALTER TABLE accounts
    ALTER COLUMN saldo_actual TYPE NUMERIC(15, 2) USING ROUND(saldo_actual::numeric, 2);

ALTER TABLE transactions
    ALTER COLUMN import TYPE NUMERIC(15, 2) USING ROUND(import::numeric, 2),
    ALTER COLUMN saldo_resultant TYPE NUMERIC(15, 2) USING ROUND(saldo_resultant::numeric, 2);

ALTER TABLE budgets
    ALTER COLUMN quantitat_limit TYPE NUMERIC(15, 2) USING ROUND(quantitat_limit::numeric, 2);

ALTER TABLE financial_goals
    ALTER COLUMN quantitat_objectiu TYPE NUMERIC(15, 2) USING ROUND(quantitat_objectiu::numeric, 2),
    ALTER COLUMN quantitat_actual TYPE NUMERIC(15, 2) USING ROUND(quantitat_actual::numeric, 2);

ALTER TABLE recurring_transactions
    ALTER COLUMN import TYPE NUMERIC(15, 2) USING ROUND(import::numeric, 2);

ALTER TABLE transfers
    ALTER COLUMN import TYPE NUMERIC(15, 2) USING ROUND(import::numeric, 2);

COMMIT;
