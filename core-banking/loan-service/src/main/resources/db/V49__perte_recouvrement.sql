-- =====================================================================================
--  Fin de vie d'un credit : passage en perte, recouvrement, revision de taux
--
--  Le passage en perte n'eteint pas la creance. C'est la premiere chose que dit un
--  controleur et la premiere qu'oublie un progiciel : la sortie de l'actif est une decision
--  comptable, pas une remise de dette. Ce qui sort du bilan entre au hors bilan, et tout ce
--  qui est encaisse ensuite est un produit de recuperation — pas un remboursement, puisqu'il
--  n'y a plus de creance a l'actif a diminuer.
--
--  Deux regles portees par le schema :
--    * un contrat ne se passe en perte qu'une fois        -> unicite du contrat non annule
--    * le recouvre ne depasse jamais ce qui a ete sorti   -> controle par le service, verifie
-- =====================================================================================

CREATE TABLE loan_write_off (
    id                   UUID PRIMARY KEY,
    contract_id          UUID NOT NULL REFERENCES loan_contract(id),
    legal_entity_id      UUID NOT NULL REFERENCES legal_entity(id),
    written_off_on       DATE NOT NULL,

    -- Ce qui sort de l'actif.
    principal_written    NUMERIC(23,5) NOT NULL CHECK (principal_written >= 0),
    receivables_written  NUMERIC(23,5) NOT NULL CHECK (receivables_written >= 0),
    -- Ce qui l'absorbe, avant que la perte ne soit constatee.
    provision_used       NUMERIC(23,5) NOT NULL CHECK (provision_used >= 0),
    reserved_used        NUMERIC(23,5) NOT NULL CHECK (reserved_used >= 0),
    provision_released   NUMERIC(23,5) NOT NULL DEFAULT 0 CHECK (provision_released >= 0),
    loss_recognised      NUMERIC(23,5) NOT NULL CHECK (loss_recognised >= 0),

    reason               TEXT NOT NULL,
    bucket_code          TEXT,
    days_past_due        INTEGER CHECK (days_past_due IS NULL OR days_past_due >= 0),
    entry_id             UUID,
    off_balance_entry_id UUID,
    created_by           UUID NOT NULL,
    approved_by          UUID NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_write_off_approval CHECK (approved_by <> created_by),
    -- L'equilibre de la decision : ce qui sort est absorbe, puis constate en perte.
    CONSTRAINT ck_write_off_balance CHECK (
        principal_written + receivables_written + provision_released
        = provision_used + reserved_used + loss_recognised)
);

CREATE UNIQUE INDEX uq_write_off_contract ON loan_write_off(contract_id);
CREATE INDEX idx_write_off_entity ON loan_write_off(legal_entity_id, written_off_on);

-- -------------------------------------------------------------------------------------
--  Recouvrement apres perte : un encaissement sur une creance qui n'est plus a l'actif.
--  Il ne diminue aucun encours — il constate un produit et sort du hors bilan d'autant.
-- -------------------------------------------------------------------------------------
CREATE TABLE loan_recovery (
    id                   UUID PRIMARY KEY,
    write_off_id         UUID NOT NULL REFERENCES loan_write_off(id),
    recovered_on         DATE NOT NULL,
    amount               NUMERIC(23,5) NOT NULL CHECK (amount > 0),
    channel_account_id   UUID NOT NULL REFERENCES account(id),
    entry_id             UUID,
    off_balance_entry_id UUID,
    recorded_by          UUID NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_recovery_write_off ON loan_recovery(write_off_id, recovered_on);

ALTER TABLE loan_write_off ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON loan_write_off
    USING (legal_entity_id = ledger_current_entity())
    WITH CHECK (legal_entity_id = ledger_current_entity());

ALTER TABLE loan_recovery ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON loan_recovery
    USING (EXISTS (SELECT 1 FROM loan_write_off w WHERE w.id = loan_recovery.write_off_id))
    WITH CHECK (EXISTS (SELECT 1 FROM loan_write_off w WHERE w.id = loan_recovery.write_off_id));
