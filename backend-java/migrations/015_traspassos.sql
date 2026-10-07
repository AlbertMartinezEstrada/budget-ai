-- Traspassos entre comptes propis.
--
-- Un moviment pot dir a quin altre compte meu van (o de quin venen) els seus
-- diners: Principal → Revolut, Principal → Trade Republic. No és ni despesa ni
-- ingrés. Entre comptes del dia a dia no compta al pressupost —compta el que es
-- paga des de Revolut, a la seva categoria—, i cap a un compte d'estalvi compta
-- com a estalvi. Mou el saldo dels dos comptes.
--
-- Una regla d'importació també pot dir-ho: «REVOLUT» és un traspàs a Revolut.
--
-- Els moviments que ja hi ha no es toquen: no se sap quins eren traspassos.
-- Es pot aplicar dues vegades: IF NOT EXISTS.

ALTER TABLE transactions
    ADD COLUMN IF NOT EXISTS compte_contrapart_id BIGINT REFERENCES accounts(id) ON DELETE SET NULL;

ALTER TABLE import_rules
    ADD COLUMN IF NOT EXISTS compte_traspas_id BIGINT REFERENCES accounts(id) ON DELETE SET NULL;
