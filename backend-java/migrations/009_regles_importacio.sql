-- Regles que s'apliquen soles en importar un extracte.
--
-- El cas que les va fer falta: totes les entrades de Revolut que diuen
-- "*9469" venen del compte principal. Són diners que ja es van comptar en
-- sortir d'allà, i marcar-les a mà una per una a cada importació és feina
-- repetida i fàcil d'oblidar.
--
-- Una regla mira el concepte original del moviment i, si hi troba el seu
-- patró, li pot posar la marca de "no comptar al pressupost" i una categoria.
--
-- No decideixen res irreversible: s'apliquen a la pantalla de revisió, on
-- encara es veu tot i es pot desmarcar abans de confirmar.

CREATE TABLE IF NOT EXISTS import_rules (
    id BIGSERIAL PRIMARY KEY,
    -- Text que s'ha de trobar dins del concepte. Sense distingir majúscules:
    -- els bancs no són consistents.
    patro VARCHAR(255) NOT NULL,
    -- Marca el moviment com a ja comptat. És el motiu principal de la taula.
    marca_exclos BOOLEAN NOT NULL DEFAULT TRUE,
    -- Categoria a assignar, si la regla també la sap. NULL = no la toca.
    categoria VARCHAR(100),
    -- Per a què serveix la regla, en paraules de qui la va crear.
    notes TEXT,
    activa BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
