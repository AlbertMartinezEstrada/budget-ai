-- L'idioma de la interfície: ca, es, pt o en.
--
-- Buit vol dir el del navegador. Per això no es posa cap valor per defecte:
-- una instal·lació que ja existia continua com estava fins que no se'n tria un
-- a Configuració.
--
-- Es pot aplicar dues vegades: IF NOT EXISTS.
ALTER TABLE settings ADD COLUMN IF NOT EXISTS language VARCHAR(5);
