-- Torna a activar els recurrents que l'edició havia desactivat.
--
-- Editar un recurrent des de la pantalla de Recurrents li deixava "activa" a
-- NULL: el formulari no envia el camp i el backend en copiava tots, també els
-- que no arribaven. El pressupost i el processament només llegeixen els
-- actius, així que des d'aquella edició el recurrent havia deixat de comptar.
--
-- Res de la interfície posa mai un recurrent a FALSE ni a NULL: un NULL és
-- sempre aquell error, i el valor que li tocava era TRUE (el per defecte de la
-- columna). El compte que també perdia no es pot recuperar: no se sap quin era.
--
-- Es pot aplicar dues vegades: la segona no troba cap NULL.

UPDATE recurring_transactions SET activa = TRUE WHERE activa IS NULL;
