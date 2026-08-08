-- Migració: categories jeràrquiques amb naturalesa de cost
--
-- Afegeix a "categories":
--   parent_id   grup al qual pertany la categoria (NULL = primer nivell)
--   tipus_cost  FIXED o VARIABLE (NULL als grups)
--
-- No toca cap dada existent. Totes les categories actuals queden com a
-- fulles de primer nivell amb tipus_cost a NULL, que el càlcul tracta com a
-- VARIABLE: exactament el comportament que ja tenien. L'arbre es construeix
-- després, des de la interfície o amb els UPDATE d'exemple del final.
--
-- Com aplicar-la (amb els contenidors aixecats):
--   docker exec -i budget_db psql -U "$DB_USER" -d "$DB_NAME" \
--     < backend-java/migrations/002_categories_jerarquiques.sql
--
-- És idempotent: es pot executar diverses vegades sense efecte addicional.

BEGIN;

ALTER TABLE categories
    ADD COLUMN IF NOT EXISTS parent_id BIGINT,
    ADD COLUMN IF NOT EXISTS tipus_cost VARCHAR(20);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'categories_parent_id_fkey'
    ) THEN
        ALTER TABLE categories
            ADD CONSTRAINT categories_parent_id_fkey
            FOREIGN KEY (parent_id) REFERENCES categories(id) ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'categories_tipus_cost_check'
    ) THEN
        ALTER TABLE categories
            ADD CONSTRAINT categories_tipus_cost_check
            CHECK (tipus_cost IN ('FIXED', 'VARIABLE'));
    END IF;
END $$;

COMMIT;

-- ---------------------------------------------------------------------------
-- Exemple d'organització, comentat a propòsit.
--
-- Descomenta'l i adapta'l només si vols muntar l'arbre des de SQL; també es
-- pot fer des de la pantalla de Categories de l'aplicació.
--
-- INSERT INTO categories (nom) VALUES ('Gastos passius'), ('Cotxe personal')
--     ON CONFLICT (nom) DO NOTHING;
--
-- UPDATE categories SET parent_id = (SELECT id FROM categories WHERE nom = 'Gastos passius'),
--                       tipus_cost = 'FIXED'
--  WHERE nom IN ('Allotjament');
--
-- UPDATE categories SET parent_id = (SELECT id FROM categories WHERE nom = 'Cotxe personal'),
--                       tipus_cost = 'VARIABLE'
--  WHERE nom IN ('Transport');
-- ---------------------------------------------------------------------------
