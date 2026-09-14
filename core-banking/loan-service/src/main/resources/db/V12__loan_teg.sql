-- =====================================================================================
--  Taux effectif global du credit
--
--  Le taux nominal ne dit rien du cout du credit : des frais preleves au deblocage
--  reduisent la somme recue sans reduire ce qui est rembourse. Le taux effectif est le
--  seul chiffre comparable d'un credit a l'autre, et c'est lui que la reglementation
--  plafonne. Il est donc conserve avec le contrat, arrete au deblocage.
-- =====================================================================================

ALTER TABLE loan_contract ADD COLUMN upfront_fees NUMERIC(23,5) NOT NULL DEFAULT 0
    CHECK (upfront_fees >= 0);

-- Taux arrete au deblocage, avec la convention employee. Le chiffre n'a aucun sens sans
-- elle : le meme echeancier affiche 12,03 % en proportionnel et 12,71 % en actuariel.
ALTER TABLE loan_contract ADD COLUMN teg_percent NUMERIC(12,6);
ALTER TABLE loan_contract ADD COLUMN teg_method TEXT
    CHECK (teg_method IS NULL OR teg_method IN ('PROPORTIONAL','ACTUARIAL'));

ALTER TABLE loan_contract ADD CONSTRAINT ck_contract_teg CHECK (
    (teg_percent IS NULL) = (teg_method IS NULL));
