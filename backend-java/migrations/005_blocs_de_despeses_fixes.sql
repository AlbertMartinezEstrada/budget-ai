-- Els blocs de despeses fixes.
--
-- Tres blocs en comptes d'una llista d'onze categories soltes perquè cada bloc
-- dona un subtotal, i el de "Subscripcions" és el que es vol mirar de tant en
-- tant: onze fulles soltes no diuen quant se'n va al mes en subscripcions.
--
--   Llar                Casa, Internet, Llum, Aigua
--   Subscripcions       Claude, iCloud, Amazon Prime, Revolut, Telèfon portuguès
--   Assegurances i salut  Assegurances, Medicare
--
-- "Llar" declara que va a la secció de fixos encara que la llum i l'aigua de
-- dins siguin variables. Són dues coses diferents: el bloc és una despesa fixa
-- —no es negocia cada mes— però el consum d'aquestes dues es mesura pel gasto
-- real i no per un prorrateig, perquè un hivern car s'ha de veure com a
-- desviació sobre el previst i no com un pic de caixa inexplicable.
--
-- Perquè un fix tingui xifra li cal una recurrent, o un import assignat al
-- pressupost. Sense cap de les dues, el seu cost de vida és zero.
--
-- Només toca dades, no esquema.

BEGIN;

-- 1. Els tres blocs. "Llar" ha de declarar la secció perquè barreja; els
--    altres dos podrien deduir-la, però es declara igualment per no dependre
--    que totes les fulles futures siguin fixes.
INSERT INTO categories (nom, tipus_cost) VALUES
    ('Llar',                 'FIXED'),
    ('Subscripcions',        'FIXED'),
    ('Assegurances i salut', 'FIXED')
ON CONFLICT (nom) DO NOTHING;

-- 2. "Allotjament" era el fix d'exemple que va deixar la 004 i vol dir el
--    mateix que "Casa". Es reanomena en comptes de crear-ne una de nova, per
--    no acabar amb dues categories per al mateix concepte. Si la instal·lació
--    és nova i no existeix, no fa res i la crea el pas següent.
UPDATE categories SET nom = 'Casa' WHERE nom = 'Allotjament';

-- 3. Les fulles. La naturalesa de cadascuna diu com es mesura, no on viu.
INSERT INTO categories (nom, parent_id, tipus_cost)
SELECT sub.nom, (SELECT id FROM categories WHERE nom = sub.bloc), sub.natura
FROM (VALUES
    -- Import contractual: es prorrategen.
    ('Casa',              'Llar',                 'FIXED'),
    ('Internet',          'Llar',                 'FIXED'),
    -- Import que es mou amb el consum: es mesuren pel gasto real del mes.
    ('Llum',              'Llar',                 'VARIABLE'),
    ('Aigua',             'Llar',                 'VARIABLE'),

    ('Claude',            'Subscripcions',        'FIXED'),
    ('iCloud',            'Subscripcions',        'FIXED'),
    ('Amazon Prime',      'Subscripcions',        'FIXED'),
    ('Revolut',           'Subscripcions',        'FIXED'),
    ('Telèfon portuguès', 'Subscripcions',        'FIXED'),

    ('Assegurances',      'Assegurances i salut', 'FIXED'),
    ('Medicare',          'Assegurances i salut', 'FIXED')
) AS sub(nom, bloc, natura)
ON CONFLICT (nom) DO NOTHING;

-- 4. La "Casa" reanomenada del pas 2 encara penja de l'arrel: se li assigna el
--    bloc, que l'INSERT no ha pogut fer perquè la fila ja existia.
UPDATE categories SET parent_id = (SELECT id FROM categories WHERE nom = 'Llar'),
                      tipus_cost = 'FIXED'
 WHERE nom = 'Casa';

COMMIT;
