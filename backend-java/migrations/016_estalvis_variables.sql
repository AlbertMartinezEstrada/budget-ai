-- L'estalvi torna a ser un variable més.
--
-- La 014 en va fer una secció a part, que es repartia després dels fixos i
-- abans dels variables. No és com es fa el pressupost: del que sobra dels fixos
-- es reparteixen percentatges, i l'estalvi n'és un. Amb la secció a part, el
-- percentatge de l'estalvi deixava de ser del mateix bot que els altres.
--
-- Estalvis es queda amb el nom: dir per a què són els diners, i no on són,
-- continua valent. Només torna a la secció de variables, i amb ella qualsevol
-- altre bloc que s'hagués marcat com a estalvi.
--
-- Es pot aplicar dues vegades: la segona ja no troba cap SAVINGS.

BEGIN;

UPDATE categories SET tipus_cost = 'VARIABLE' WHERE tipus_cost = 'SAVINGS';

ALTER TABLE categories DROP CONSTRAINT IF EXISTS categories_tipus_cost_check;
ALTER TABLE categories ADD CONSTRAINT categories_tipus_cost_check
    CHECK (tipus_cost IN ('FIXED', 'VARIABLE', 'INCOME'));

COMMIT;
