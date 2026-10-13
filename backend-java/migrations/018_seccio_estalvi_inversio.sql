-- Secció "Estalvi i inversió": l'estalvi es reparteix amb els variables, però
-- compta com a estalvi i no com a despesa.
--
-- La 016 va tornar Estalvis als variables perquè el repartiment és del que
-- sobra dels fixos, per percentatges, i l'estalvi n'és un. Però així els
-- gastos del mes portaven dins el que s'apartava. Ara SAVINGS és una secció
-- que es reparteix del mateix bot que els variables (el percentatge no canvia)
-- i que mesura el que passa a un compte d'estalvi o d'inversió.
--
--   Estalvi i inversió
--   ├── Estalvis
--   ├── Regals i altres          l'estalvi per a objectius
--   │   └── Regals i detalls
--   └── Inversió                 bloc nou
--       ├── Fons d'inversió
--       │   └── Inversions
--       └── Inversió de risc
--
-- "Altres" passa a Gast mensual: és on cauen els moviments sense categoria, i
-- dins d'un bloc d'estalvi un gasto sense classificar hauria comptat com a
-- estalvi.
--
-- No es crea ni s'esborra cap moviment: les categories es mouen, i els
-- moviments, pressupostos i recurrents les segueixen perquè hi apunten per id.
-- Els percentatges de Fons d'inversió i d'Inversió de risc es mantenen: el bloc
-- Inversió no té assignació pròpia, i sense, els fills es reparteixen el mateix
-- bot que el pare.
--
-- Es pot aplicar dues vegades: cada pas mira si ja està fet.

BEGIN;

ALTER TABLE categories DROP CONSTRAINT IF EXISTS categories_tipus_cost_check;
ALTER TABLE categories ADD CONSTRAINT categories_tipus_cost_check
    CHECK (tipus_cost IN ('FIXED', 'VARIABLE', 'INCOME', 'SAVINGS'));

-- Abans de canviar Regals i altres de secció.
UPDATE categories
SET parent_id = (SELECT id FROM categories WHERE nom = 'Gast mensual')
WHERE nom = 'Altres'
  AND parent_id = (SELECT id FROM categories WHERE nom = 'Regals i altres')
  AND EXISTS (SELECT 1 FROM categories WHERE nom = 'Gast mensual');

INSERT INTO categories (nom, tipus_cost) VALUES ('Inversió', 'SAVINGS')
ON CONFLICT (nom) DO NOTHING;

-- Només si encara són blocs de primer nivell: si algú ja els ha posat en un
-- altre lloc, és decisió seva.
UPDATE categories
SET parent_id = (SELECT id FROM categories WHERE nom = 'Inversió')
WHERE nom IN ('Fons d''inversió', 'Inversió de risc')
  AND parent_id IS NULL;

UPDATE categories SET tipus_cost = 'SAVINGS'
WHERE parent_id IS NULL
  AND nom IN ('Estalvis', 'Regals i altres', 'Inversió');

COMMIT;
