-- Comanda de menjar a domicili, separada del supermercat.
--
-- Glovo, Uber Eats i companyia queien a "Menjar i supermercat", i barrejaven
-- dues coses que no es decideixen igual: la compra setmanal és una despesa que
-- toca fer, i demanar sopar és una que es pot retallar. Amb totes dues al
-- mateix sac, el sostre mensual no deia res útil.
--
-- Va sota "Gast mensual" i és variable, com les seves veïnes.
--
-- La IA la veurà sola: des d'ara la llista de categories del prompt surt de
-- les fulles de la base de dades i no d'una llista escrita a mà.
--
-- Només toca dades, no esquema.

INSERT INTO categories (nom, parent_id, tipus_cost)
SELECT 'Delivery', (SELECT id FROM categories WHERE nom = 'Gast mensual'), 'VARIABLE'
ON CONFLICT (nom) DO NOTHING;
