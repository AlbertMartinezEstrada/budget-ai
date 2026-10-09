-- Deutes per mes: cada pagament paga un rebut, i els rebuts es poden treure.
--
-- Fins ara el calendari d'un deute comparava amb el dia exacte i repartia el
-- que s'havia retornat per ordre: un pagament fet a l'octubre tapava el rebut
-- de setembre, i el d'octubre sortia endarrerit l'endemà del dia 8. Ara els
-- rebuts van per mes, i cada moviment vinculat a un deute pot dir quin rebut
-- paga (deute_rebut). Sense, paga el que toca: el primer que no està pagat.
--
-- Un rebut també es pot treure del calendari: saltat (aquell mes no toca i el
-- pla s'allarga pel final) o descomptat del deute (una rebaixa).
--
-- Els moviments que ja hi ha no es toquen: sense rebut triat, van al que toca.
-- Es pot aplicar dues vegades: IF NOT EXISTS.

ALTER TABLE transactions
    ADD COLUMN IF NOT EXISTS deute_rebut DATE;

CREATE TABLE IF NOT EXISTS debt_removed_receipts (
    id BIGSERIAL PRIMARY KEY,
    deute_id BIGINT NOT NULL REFERENCES debts(id) ON DELETE CASCADE,
    data DATE NOT NULL,
    descompte DECIMAL(15, 2) NOT NULL DEFAULT 0 CHECK (descompte >= 0),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT debt_removed_receipts_deute_data_key UNIQUE (deute_id, data)
);
