-- =====================================================================================
--  Cloture d'un credit : datee, et rattachee au traitement qui l'a prononcee
--
--  Un credit integralement rembourse restait ACTIVE : rien ne le cloturait. Chaque arrete
--  le reexaminait a vie — exigibilite, retard, classification —, le portefeuille « en
--  cours » gonflait, et rien ne pouvait etre archive. La cloture est desormais prononcee
--  par l'arrete, apres la classification, qui a repris la provision d'un encours devenu nul.
--
--  closed_run_id porte la reversibilite : l'annulation du traitement qui a clos un credit
--  le rend actif, comme elle rend exigibles les echeances qu'il avait reclamees.
-- =====================================================================================

ALTER TABLE loan_contract ADD COLUMN closed_on DATE;
ALTER TABLE loan_contract ADD COLUMN closed_run_id UUID;

ALTER TABLE loan_contract ADD CONSTRAINT ck_contract_closed
    CHECK (status <> 'CLOSED' OR closed_on IS NOT NULL);

CREATE INDEX idx_contract_closed_run ON loan_contract(closed_run_id)
    WHERE closed_run_id IS NOT NULL;
