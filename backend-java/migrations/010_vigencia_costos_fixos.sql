-- Període de vigència dels recurrents.
--
-- Els costos fixos es gestionen des de Pressupostos i serveixen de plantilla
-- per a cada mes. Quan un canvia (el lloguer puja a l'octubre), el canvi ha
-- de valer des d'aquell mes: els anteriors s'han de quedar com estaven. Sense
-- dates, editar l'import reescrivia el pla de tots els mesos passats.
--
-- NULL a vigent_des_de vol dir "des de sempre" i NULL a vigent_fins, "encara
-- vigent", així que les files existents continuen comptant igual que abans.

ALTER TABLE recurring_transactions ADD COLUMN IF NOT EXISTS vigent_des_de DATE;
ALTER TABLE recurring_transactions ADD COLUMN IF NOT EXISTS vigent_fins DATE;
