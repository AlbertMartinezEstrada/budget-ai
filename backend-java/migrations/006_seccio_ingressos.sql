-- Els ingressos surten de la secció de despeses variables.
--
-- Hi eren perquè una categoria d'ingrés és una categoria com qualsevol altra i
-- queia a variables per descart. Però allà no volia dir res: sortia com un bloc
-- amb zero euros assignats, competint per un bot que precisament és seu.
--
-- Ara "INCOME" és una tercera secció, declarada al bloc igual que FIXED i
-- VARIABLE. Les seves fulles no es mesuren pel gasto sinó pel que hi ha entrat.
--
-- S'hi afegeix "Regals i premis" per als diners que arriben i no són la nòmina.
-- Sense una categoria on posar-los, un regal o un premi no es podia importar
-- com a ingrés, i és justament el que fa pujar el bot dels variables aquell mes.
--
-- Només toca dades, no esquema.

BEGIN;

-- El CHECK de la columna només admetia FIXED i VARIABLE, que eren les dues
-- úniques maneres de mesurar una fulla. Ara la mateixa columna també diu la
-- secció d'un bloc, i les seccions són tres.
ALTER TABLE categories DROP CONSTRAINT IF EXISTS categories_tipus_cost_check;
ALTER TABLE categories ADD CONSTRAINT categories_tipus_cost_check
    CHECK (tipus_cost IN ('FIXED', 'VARIABLE', 'INCOME'));

UPDATE categories SET tipus_cost = 'INCOME' WHERE nom = 'Ingressos';

INSERT INTO categories (nom, parent_id, tipus_cost)
SELECT 'Regals i premis', (SELECT id FROM categories WHERE nom = 'Ingressos'), NULL
ON CONFLICT (nom) DO NOTHING;

COMMIT;
