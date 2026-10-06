-- Dividir un moviment en parts.
--
-- Una mateixa línia de l'extracte pot ser diverses coses alhora. La
-- transferència a Trade Republic porta estalvi, la devolució d'un préstec, el
-- que es guarda per pagar l'assegurança i uns diners que no han de comptar.
-- Amb una sola categoria per moviment, el pressupost la comptava sencera en un
-- sol lloc.
--
-- El moviment no es toca: el saldo es mou un sol cop, pel total, i el hash
-- segueix identificant la línia de l'extracte. Les parts diuen com es reparteix
-- aquest total, i quan n'hi ha, manen elles: la categoria, l'exclòs i el deute
-- del moviment deixen de comptar. Han de sumar exactament l'import del
-- moviment; ho comprova el backend.
--
-- Només afegeix una taula: cap moviment existent canvia de significat.

BEGIN;

CREATE TABLE IF NOT EXISTS transaction_parts (
    id BIGSERIAL PRIMARY KEY,
    transaction_id BIGINT NOT NULL REFERENCES transactions(id) ON DELETE CASCADE,
    import DECIMAL(15, 2) NOT NULL CHECK (import > 0),
    -- Sempre una fulla. Sense ON DELETE: esborrar una categoria amb parts es
    -- rebutja abans, com amb els moviments.
    category_id BIGINT NOT NULL REFERENCES categories(id),
    exclos_pressupost BOOLEAN NOT NULL DEFAULT FALSE,
    deute_id BIGINT REFERENCES debts(id) ON DELETE SET NULL,
    descripcio TEXT
);
CREATE INDEX IF NOT EXISTS transaction_parts_transaction_idx ON transaction_parts (transaction_id);

COMMIT;
