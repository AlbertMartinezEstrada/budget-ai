-- L'estalvi passa a ser una secció del pressupost, i "Trade Republic" es diu
-- "Estalvis".
--
-- La categoria deia on són els diners —un banc— i no per a què són. I com que
-- era un bloc variable, l'estalvi sortia com un gasto ("gastat 300 de 300") i
-- competia amb el supermercat pel mateix bot. Ara SAVINGS és una secció més,
-- declarada al bloc igual que FIXED, VARIABLE i INCOME, que es reparteix
-- després dels fixos i abans dels variables.
--
-- No es crea cap categoria: es reanomena la que hi ha, i els seus moviments,
-- pressupostos i recurrents la segueixen perquè hi apunten per id. Les regles
-- d'importació, en canvi, guarden el nom: per això es canvien aquí també.
--
-- Es pot aplicar dues vegades: la segona ja no troba cap "Trade Republic". Si
-- algú ja té una categoria "Estalvis", no es toca res: no se sap quina de les
-- dues és la bona, i el nom ha de ser únic.

BEGIN;

ALTER TABLE categories DROP CONSTRAINT IF EXISTS categories_tipus_cost_check;
ALTER TABLE categories ADD CONSTRAINT categories_tipus_cost_check
    CHECK (tipus_cost IN ('FIXED', 'VARIABLE', 'INCOME', 'SAVINGS'));

-- Abans que la categoria: després ja no se sabria si "Estalvis" és la
-- reanomenada o una que ja hi era.
UPDATE import_rules SET categoria = 'Estalvis'
WHERE categoria = 'Trade Republic'
  AND EXISTS (SELECT 1 FROM categories WHERE nom = 'Trade Republic')
  AND NOT EXISTS (SELECT 1 FROM categories WHERE nom = 'Estalvis');

-- La secció només es posa si és un bloc de primer nivell. A una fulla,
-- tipus_cost vol dir com es mesura, i SAVINGS no hi diria res.
UPDATE categories
SET nom = 'Estalvis',
    tipus_cost = CASE WHEN parent_id IS NULL THEN 'SAVINGS' ELSE tipus_cost END
WHERE nom = 'Trade Republic'
  AND NOT EXISTS (SELECT 1 FROM categories WHERE nom = 'Estalvis');

COMMIT;
