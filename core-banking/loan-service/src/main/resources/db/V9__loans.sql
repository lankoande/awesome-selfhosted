-- =====================================================================================
--  Credits : contrats, echeanciers versionnes, creances exigibles, reglements
--
--  Trois regles structurantes portees par le schema :
--    * un seul echeancier en vigueur a une date donnee   -> contrainte d'exclusion
--    * un echeancier remplace n'est jamais efface        -> versions conservees
--    * une creance ne remonte jamais                     -> declencheur sur le solde
-- =====================================================================================

CREATE TABLE loan_contract (
    id                  UUID PRIMARY KEY,
    legal_entity_id     UUID NOT NULL REFERENCES legal_entity(id),
    reference           TEXT NOT NULL,
    product_code        TEXT NOT NULL,
    currency            CHAR(3) NOT NULL REFERENCES currency(code),

    -- Compte de pret : l'encours porte a l'actif. Compte de reglement : celui du client,
    -- debite des echeances. Les separer n'est pas une commodite — l'encours doit rester
    -- lisible independamment des mouvements du compte courant.
    loan_account_id     UUID NOT NULL REFERENCES account(id),
    settlement_account_id UUID NOT NULL REFERENCES account(id),

    principal           NUMERIC(23,5) NOT NULL CHECK (principal > 0),
    disbursed_on        DATE NOT NULL,
    status              TEXT NOT NULL DEFAULT 'DRAFT'
                        CHECK (status IN ('DRAFT','ACTIVE','CLOSED','WRITTEN_OFF')),
    created_by          UUID NOT NULL,
    approved_by         UUID,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_contract_reference UNIQUE (legal_entity_id, reference),
    CONSTRAINT ck_contract_accounts CHECK (loan_account_id <> settlement_account_id),
    -- Un credit debloque engage des fonds : le decideur n'est pas celui qui saisit.
    CONSTRAINT ck_contract_approval CHECK (
        status = 'DRAFT' OR (approved_by IS NOT NULL AND approved_by <> created_by))
);

CREATE INDEX idx_contract_entity ON loan_contract(legal_entity_id, status);

-- -------------------------------------------------------------------------------------
--  Echeancier, versionne.
--
--  Un rechelonnement, un remboursement anticipe partiel ou une revision de taux produit
--  une nouvelle version. L'ancienne est conservee : l'echeancier contractuel initial doit
--  rester consultable des annees plus tard, et c'est une piece du dossier en cas de
--  contentieux.
-- -------------------------------------------------------------------------------------
CREATE TABLE loan_schedule (
    id              UUID PRIMARY KEY,
    contract_id     UUID NOT NULL REFERENCES loan_contract(id),
    version         INTEGER NOT NULL CHECK (version >= 1),
    reason          TEXT NOT NULL CHECK (reason IN (
                        'INITIAL','RESCHEDULING','EARLY_REPAYMENT','RATE_REVISION')),
    effective_from  DATE NOT NULL,
    superseded_on   DATE,
    created_by      UUID NOT NULL,
    approved_by     UUID NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_schedule_version UNIQUE (contract_id, version),
    CONSTRAINT ck_schedule_period CHECK (superseded_on IS NULL OR superseded_on >= effective_from),
    -- Rechelonner, c'est modifier ce que le client doit : meme regime de double validation
    -- qu'un parametrage tarifaire.
    CONSTRAINT ck_schedule_approval CHECK (approved_by <> created_by),
    -- Deux echeanciers en vigueur a la meme date rendraient l'exigibilite dependante de
    -- l'ordre de lecture : le meme TFJ rejoue reclamerait deux montants differents.
    CONSTRAINT ex_schedule_no_overlap EXCLUDE USING gist (
        contract_id WITH =,
        daterange(effective_from, COALESCE(superseded_on, 'infinity'::date), '[)') WITH &&
    )
);

CREATE TABLE loan_schedule_line (
    schedule_id        UUID NOT NULL REFERENCES loan_schedule(id) ON DELETE CASCADE,
    number             INTEGER NOT NULL CHECK (number >= 1),
    due_date           DATE NOT NULL,
    period_start       DATE NOT NULL,
    period_end         DATE NOT NULL,
    outstanding_before NUMERIC(23,5) NOT NULL,
    principal          NUMERIC(23,5) NOT NULL CHECK (principal >= 0),
    interest           NUMERIC(23,5) NOT NULL CHECK (interest >= 0),
    insurance          NUMERIC(23,5) NOT NULL CHECK (insurance >= 0),
    fee                NUMERIC(23,5) NOT NULL CHECK (fee >= 0),
    tax                NUMERIC(23,5) NOT NULL CHECK (tax >= 0),
    total              NUMERIC(23,5) NOT NULL,
    outstanding_after  NUMERIC(23,5) NOT NULL CHECK (outstanding_after >= 0),

    -- Exigibilite : renseignee par le TFJ qui a rendu l'echeance exigible. Remise a nul
    -- par l'annulation de ce TFJ, sans quoi l'echeance ne serait jamais reclamee.
    made_due_on        DATE,
    made_due_run_id    UUID,

    PRIMARY KEY (schedule_id, number),
    CONSTRAINT ck_line_total CHECK (total = principal + interest + insurance + fee + tax),
    CONSTRAINT ck_line_outstanding CHECK (outstanding_after = outstanding_before - principal)
);

CREATE INDEX idx_schedule_line_due ON loan_schedule_line(due_date) WHERE made_due_on IS NULL;

-- -------------------------------------------------------------------------------------
--  Creances exigibles.
--
--  Une ligne par echeance et par nature. La nature n'est pas decorative : c'est elle qui
--  decide de l'ordre d'imputation d'un reglement, et donc de ce qui reste du.
-- -------------------------------------------------------------------------------------
CREATE TABLE loan_receivable (
    id                UUID PRIMARY KEY,
    contract_id       UUID NOT NULL REFERENCES loan_contract(id),
    schedule_id       UUID REFERENCES loan_schedule(id),
    instalment_number INTEGER NOT NULL DEFAULT 0 CHECK (instalment_number >= 0),
    category          TEXT NOT NULL CHECK (category IN (
                          'RECOVERY_FEES','PENALTIES','FEES_AND_INSURANCE','LATE_INTEREST',
                          'INTEREST','PRINCIPAL','FUTURE_PRINCIPAL')),
    due_date          DATE NOT NULL,
    original_amount   NUMERIC(23,5) NOT NULL CHECK (original_amount > 0),
    outstanding       NUMERIC(23,5) NOT NULL CHECK (outstanding >= 0),
    settled_on        DATE,
    batch_run_id      UUID,
    cancelled         BOOLEAN NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_receivable_bounds CHECK (outstanding <= original_amount),
    CONSTRAINT ck_receivable_settled CHECK (
        (outstanding = 0) = (settled_on IS NOT NULL) OR cancelled)
);

-- Une creance par echeance et par nature — mais l'unicite ne survit pas a l'annulation du
-- traitement qui l'a produite. Sinon la refacturation qui suit une annulation de TFJ se
-- heurterait a la creance annulee, et l'echeance ne serait jamais reclamee.
CREATE UNIQUE INDEX uq_receivable_per_instalment
    ON loan_receivable(schedule_id, instalment_number, category) WHERE NOT cancelled;

CREATE INDEX idx_receivable_open ON loan_receivable(contract_id, due_date)
    WHERE outstanding > 0 AND NOT cancelled;

-- Une creance ne remonte jamais : son solde ne peut que decroitre, et son montant
-- d'origine ne se reecrit pas. Une creance qui remonterait ferait repartir a zero le
-- compteur de jours de retard, et avec lui le declassement et le provisionnement.
CREATE OR REPLACE FUNCTION guard_loan_receivable() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Une creance ne se supprime pas : l''annulation passe par le drapeau cancelled.'
          USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF (NEW.contract_id, NEW.category, NEW.due_date, NEW.original_amount, NEW.instalment_number)
       IS DISTINCT FROM
       (OLD.contract_id, OLD.category, OLD.due_date, OLD.original_amount, OLD.instalment_number) THEN
        RAISE EXCEPTION 'La creance % est figee : seul son solde evolue.', OLD.id
          USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF NEW.outstanding > OLD.outstanding AND NOT NEW.cancelled THEN
        RAISE EXCEPTION 'Le solde de la creance % passerait de % a % : une creance ne remonte pas.',
            OLD.id, OLD.outstanding, NEW.outstanding
          USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_loan_receivable_guard
    BEFORE UPDATE OR DELETE ON loan_receivable
    FOR EACH ROW EXECUTE FUNCTION guard_loan_receivable();

-- -------------------------------------------------------------------------------------
--  Reglements et leur ventilation.
--
--  La ventilation est conservee ligne a ligne. Sans elle, un releve de credit ne sait pas
--  dire ce qu'un versement a paye, et une contestation sur l'ordre d'imputation — qui est
--  le contentieux le plus frequent en credit — n'a aucune piece a produire.
-- -------------------------------------------------------------------------------------
CREATE TABLE loan_payment (
    id               UUID PRIMARY KEY,
    contract_id      UUID NOT NULL REFERENCES loan_contract(id),
    value_date       DATE NOT NULL,
    amount           NUMERIC(23,5) NOT NULL CHECK (amount > 0),
    allocated        NUMERIC(23,5) NOT NULL CHECK (allocated >= 0),
    unallocated      NUMERIC(23,5) NOT NULL CHECK (unallocated >= 0),
    source           TEXT NOT NULL CHECK (source IN ('DIRECT_DEBIT','MANUAL')),
    entry_id         UUID,
    batch_run_id     UUID,
    idempotency_key  TEXT NOT NULL UNIQUE,
    actor_id         UUID NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_payment_split CHECK (amount = allocated + unallocated)
);

CREATE TABLE loan_payment_allocation (
    payment_id    UUID NOT NULL REFERENCES loan_payment(id),
    receivable_id UUID NOT NULL REFERENCES loan_receivable(id),
    amount        NUMERIC(23,5) NOT NULL CHECK (amount > 0),
    PRIMARY KEY (payment_id, receivable_id)
);

CREATE OR REPLACE FUNCTION forbid_loan_payment_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Un reglement et sa ventilation sont immuables : % interdit.', TG_OP
      USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_loan_payment_immutable
    BEFORE UPDATE OR DELETE ON loan_payment
    FOR EACH ROW EXECUTE FUNCTION forbid_loan_payment_mutation();

CREATE TRIGGER trg_loan_allocation_immutable
    BEFORE UPDATE OR DELETE ON loan_payment_allocation
    FOR EACH ROW EXECUTE FUNCTION forbid_loan_payment_mutation();
