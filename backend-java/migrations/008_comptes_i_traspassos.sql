-- Cada moviment amb el seu compte, i els traspassos fora del pressupost.
--
-- EL PROBLEMA
--
-- Amb tres comptes, els diners es comptaven dues vegades. Un traspàs de 100 €
-- del Compte Principal a Revolut surt a l'extracte de tots dos:
--
--   Principal  −100  "traspàs a Revolut"   comptava com a DESPESA
--   Revolut    +100  "entrada"             comptava com a INGRÉS
--   Revolut    −100  "compra ETF"          comptava com a DESPESA
--
-- 200 € de despesa on només n'hi ha 100, i a més l'entrada inflava el bot a
-- repartir amb diners que ja hi eren.
--
-- LA REGLA
--
-- Un cop els diners surten del compte principal, ja estan comptats. El que
-- facin després —arribar a Revolut, comprar-hi un ETF— és comptabilitat: mou
-- saldos, però no torna a comptar al pressupost.
--
-- Per això la columna diu "exclòs del pressupost" i no "és traspàs": la compra
-- de dins de Revolut no és cap traspàs, però tampoc s'ha de tornar a comptar.

BEGIN;

-- 1. La marca. Per defecte FALSE: els moviments que ja hi ha compten, com fins
--    ara, i només s'exclou el que es marqui expressament.
ALTER TABLE transactions
    ADD COLUMN IF NOT EXISTS exclos_pressupost BOOLEAN NOT NULL DEFAULT FALSE;

-- 2. El hash identifica una línia d'extracte, i ara els extractes són de
--    comptes diferents. Amb la unicitat només sobre el hash, el mateix import
--    el mateix dia a dos comptes —que és exactament el que produeix un
--    traspàs— es prenia per un duplicat i el segon es descartava en silenci.
--
--    La fórmula del hash no es toca: canviar-la deixaria els moviments ja
--    importats amb una identitat antiga i tornar a pujar el mateix fitxer els
--    duplicaria. El que s'amplia és l'abast de la unicitat.
ALTER TABLE transactions DROP CONSTRAINT IF EXISTS transactions_hash_verificacio_key;
ALTER TABLE transactions
    ADD CONSTRAINT transactions_hash_compte_key UNIQUE (hash_verificacio, account_id);

COMMIT;
