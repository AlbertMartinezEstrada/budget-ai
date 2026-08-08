-- Migració: repartiment del sou per percentatges
--
-- Afegeix:
--   settings.expected_monthly_income  sou de referència per defecte
--   budgets.percentatge               % del sou assignat a una categoria
--   taula monthly_income              sou d'un mes concret, quan no és el de sempre
--
-- No toca cap dada existent. Els pressupostos actuals es queden amb
-- percentatge a NULL i segueixen funcionant per import fix, exactament com
-- fins ara. Els percentatges només s'apliquen on s'informin.
--
-- Com aplicar-la (amb els contenidors aixecats):
--   docker exec -i budget_db psql -U "$DB_USER" -d "$DB_NAME" \
--     < backend-java/migrations/003_percentatges_sou.sql
--
-- És idempotent.

BEGIN;

ALTER TABLE settings
    ADD COLUMN IF NOT EXISTS expected_monthly_income DECIMAL(15, 2);

ALTER TABLE budgets
    ADD COLUMN IF NOT EXISTS percentatge DECIMAL(5, 2);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'budgets_percentatge_check'
    ) THEN
        ALTER TABLE budgets
            ADD CONSTRAINT budgets_percentatge_check
            CHECK (percentatge IS NULL OR (percentatge >= 0 AND percentatge <= 100));
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS monthly_income (
    id BIGSERIAL PRIMARY KEY,
    periode VARCHAR(7) UNIQUE NOT NULL,
    import DECIMAL(15, 2) NOT NULL,
    notes TEXT
);

COMMIT;
