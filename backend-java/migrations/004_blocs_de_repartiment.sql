-- Reorganitza les categories per defecte en blocs de repartiment.
--
-- Abans eren vint categories planes de primer nivell, i la pantalla de
-- pressupostos ensenyava vint targetes iguals sense cap jerarquia: no es veia
-- ni què era fix ni què era variable, ni quant pesava cada cosa.
--
-- L'estructura que en surt és la que llegeix el resum mensual:
--
--   FIXOS      blocs on totes les fulles són FIXED (Allotjament)
--   VARIABLES  la resta: Trade Republic, Gastos compartits, Fons d'inversió,
--              Gast mensual, Inversió de risc, Regals i altres
--
-- La secció no es declara enlloc: es dedueix de tipus_cost de les fulles. Per
-- moure un bloc de secció, canvia la naturalesa de les seves subcategories.
--
-- Només toca dades, no esquema. És reversible des de la pantalla de Categorías.

BEGIN;

-- 1. Els blocs de primer nivell. Els que tenen subcategories passen a ser
--    grups tot sols quan se'ls hi penja alguna cosa.
INSERT INTO categories (nom, tipus_cost) VALUES
    ('Trade Republic',   'VARIABLE'),
    ('Gastos compartits','VARIABLE'),
    ('Fons d''inversió', 'VARIABLE'),
    ('Gast mensual',     'VARIABLE'),
    ('Inversió de risc', 'VARIABLE'),
    ('Regals i altres',  'VARIABLE'),
    ('Ingressos',        'VARIABLE')
ON CONFLICT (nom) DO NOTHING;

-- 2. El dia a dia va dins de "Gast mensual".
UPDATE categories SET parent_id = (SELECT id FROM categories WHERE nom = 'Gast mensual'),
                      tipus_cost = 'VARIABLE'
 WHERE nom IN ('Menjar i supermercat', 'Bars i restaurants', 'Transport',
               'Compres i roba', 'Higiene i bellesa', 'Salut i farmàcia',
               'Gimnàs i esport', 'Cultura, oci i entreteniment',
               'Jocs de taula i videojocs', 'Festa i alcohol', 'Tecnologia',
               'Casa i mobiliari', 'Mascotes', 'Educació');

UPDATE categories SET parent_id = (SELECT id FROM categories WHERE nom = 'Regals i altres'),
                      tipus_cost = 'VARIABLE'
 WHERE nom IN ('Regals i detalls', 'Altres');

UPDATE categories SET parent_id = (SELECT id FROM categories WHERE nom = 'Fons d''inversió'),
                      tipus_cost = 'VARIABLE'
 WHERE nom IN ('Inversions');

-- 3. Els ingressos no són despesa, però són categories com les altres i
--    sortirien soltes al resum. Agrupats, són un sol bloc i no fan soroll.
UPDATE categories SET parent_id = (SELECT id FROM categories WHERE nom = 'Ingressos')
 WHERE nom IN ('Nòmina', 'Ingressos Altres');

-- 4. El lloguer és l'exemple de despesa fixa: import conegut cada mes. Un bloc
--    amb totes les fulles fixes és el que fa aparèixer la secció de fixos.
UPDATE categories SET tipus_cost = 'FIXED' WHERE nom = 'Allotjament';

COMMIT;
