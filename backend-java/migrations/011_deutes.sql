-- Deutes i préstecs, en els dos sentits: el que dec i el que em deuen.
--
-- Un préstec no és ni un ingrés ni una despesa. Quan em deixen 1.000 €, el
-- saldo puja però no soc més ric: els dec. Aquesta taula porta el compte de
-- qui deu què i com s'ha acordat tornar-ho (tot de cop, a quotes o sense
-- calendari).
--
-- El que ja s'ha retornat no es desa enlloc: surt dels moviments vinculats amb
-- transactions.deute_id. Una taula de pagaments a part duplicaria cada línia
-- de l'extracte, i les dues còpies acabarien dient coses diferents.
--
-- Al pressupost, un préstec rebut compta com a diners disponibles aquell mes
-- (fulla "Préstecs rebuts", dins d'Ingressos) i les devolucions com a despesa
-- (fulla "Pagament de deutes"). Cada euro compta una sola vegada: el préstec
-- paga el que es compra amb ell, i el cost arriba amb les quotes, que és quan
-- surt del sou. Les quotes pactades es reserven soles al pla de cada mes.

BEGIN;

CREATE TABLE IF NOT EXISTS debts (
    id BIGSERIAL PRIMARY KEY,
    nom VARCHAR(150) NOT NULL,
    -- DEC: me'ls han deixat. EM_DEUEN: els he deixat jo.
    direccio VARCHAR(10) NOT NULL CHECK (direccio IN ('DEC', 'EM_DEUEN')),
    import DECIMAL(15, 2) NOT NULL CHECK (import > 0),
    data DATE NOT NULL,
    -- LLIURE: sense calendari. UNIC: tot de cop a data_primer_pagament.
    -- QUOTES: "quota" cada "frequencia" des de data_primer_pagament.
    forma_retorn VARCHAR(10) NOT NULL DEFAULT 'LLIURE' CHECK (forma_retorn IN ('LLIURE', 'UNIC', 'QUOTES')),
    quota DECIMAL(15, 2) CHECK (quota IS NULL OR quota > 0),
    frequencia VARCHAR(20) CHECK (frequencia IN ('SETMANAL', 'MENSUAL', 'TRIMESTRAL')),
    data_primer_pagament DATE,
    -- Fulla on el pressupost reserva les quotes d'un deute que dec.
    category_id BIGINT REFERENCES categories(id) ON DELETE SET NULL,
    notes TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Esborrar un deute no esborra els moviments: van passar de debò i el saldo
-- del compte en depèn. Només perden el vincle.
ALTER TABLE transactions
    ADD COLUMN IF NOT EXISTS deute_id BIGINT REFERENCES debts(id) ON DELETE SET NULL;

INSERT INTO categories (nom, tipus_cost) VALUES ('Deutes i préstecs', 'FIXED')
ON CONFLICT (nom) DO NOTHING;

-- El JOIN fa que una fulla només es creï si el seu bloc existeix: si
-- "Ingressos" s'ha reanomenat, penjar-hi fulles d'un bloc inexistent les
-- deixaria soles al primer nivell. En aquest cas s'han de crear a mà.
INSERT INTO categories (nom, parent_id, tipus_cost)
SELECT sub.nom, parent.id, sub.natura
FROM (VALUES
    ('Pagament de deutes', 'Deutes i préstecs', 'FIXED'),
    ('Préstecs fets', 'Deutes i préstecs', 'VARIABLE'),
    ('Préstecs rebuts', 'Ingressos', NULL),
    ('Cobrament de préstecs', 'Ingressos', NULL)
) AS sub(nom, bloc, natura)
JOIN categories parent ON parent.nom = sub.bloc
ON CONFLICT (nom) DO NOTHING;

COMMIT;
