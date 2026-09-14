-- =====================================================================================
--  Classification, provisionnement et suspension des interets
--
--  Le parametrage le plus lourd de consequences du socle : il decide du niveau de
--  provision de tout un portefeuille, donc du resultat publie et du ratio de solvabilite.
--  Il est date, versionne et soumis a double validation, comme un bareme tarifaire.
-- =====================================================================================

-- Rattachement d'un credit a son titulaire. Sans lui, la contagion n'a pas de peripherie.
ALTER TABLE loan_contract ADD COLUMN customer_id UUID;
CREATE INDEX idx_contract_customer ON loan_contract(customer_id) WHERE customer_id IS NOT NULL;

CREATE TABLE risk_profile (
    id                  UUID PRIMARY KEY,
    legal_entity_id     UUID NOT NULL REFERENCES legal_entity(id),
    code                TEXT NOT NULL,
    label               TEXT NOT NULL,
    valid_from          DATE NOT NULL,
    valid_to            DATE,
    contagion           TEXT NOT NULL CHECK (contagion IN ('NONE','CUSTOMER')),
    -- Classe a partir de laquelle les interets cessent d'etre constates en produits.
    suspend_from_bucket TEXT,
    status              TEXT NOT NULL DEFAULT 'DRAFT'
                        CHECK (status IN ('DRAFT','ACTIVE','SUSPENDED','WITHDRAWN')),
    created_by          UUID NOT NULL,
    approved_by         UUID,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_profile_validity CHECK (valid_to IS NULL OR valid_to >= valid_from),
    CONSTRAINT ck_profile_approval CHECK (
        status <> 'ACTIVE' OR (approved_by IS NOT NULL AND approved_by <> created_by)),
    -- Deux grilles en vigueur a la meme date rendraient le classement dependant de l'ordre
    -- de lecture : le meme arrete rejoue provisionnerait deux montants differents.
    CONSTRAINT ex_profile_no_overlap EXCLUDE USING gist (
        legal_entity_id WITH =,
        code WITH =,
        daterange(valid_from, COALESCE(valid_to + 1, 'infinity'::date), '[)') WITH &&
    ) WHERE (status = 'ACTIVE')
);

CREATE TABLE risk_bucket (
    profile_id             UUID NOT NULL REFERENCES risk_profile(id) ON DELETE CASCADE,
    ordinal                SMALLINT NOT NULL CHECK (ordinal >= 0),
    code                   TEXT NOT NULL,
    label                  TEXT NOT NULL,
    from_days              INTEGER NOT NULL CHECK (from_days >= 0),
    to_days                INTEGER,
    provision_rate_percent NUMERIC(12,6) NOT NULL
                           CHECK (provision_rate_percent BETWEEN 0 AND 100),
    performing             BOOLEAN NOT NULL,

    PRIMARY KEY (profile_id, ordinal),
    CONSTRAINT uq_bucket_code UNIQUE (profile_id, code),
    CONSTRAINT ck_bucket_bounds CHECK (to_days IS NULL OR to_days >= from_days)
);

-- -------------------------------------------------------------------------------------
--  Classification d'un credit a une date.
--
--  Une ligne par arrete, conservee. L'historique du declassement est une piece du dossier
--  reglementaire : il faut pouvoir dire quand un credit est passe en souffrance, pourquoi,
--  et quelle provision a ete dotee ce jour-la.
-- -------------------------------------------------------------------------------------
CREATE TABLE loan_classification (
    id                     UUID PRIMARY KEY,
    contract_id            UUID NOT NULL REFERENCES loan_contract(id),
    classified_on          DATE NOT NULL,
    days_past_due          INTEGER NOT NULL CHECK (days_past_due >= 0),
    bucket_code            TEXT NOT NULL,
    bucket_ordinal         SMALLINT NOT NULL,
    performing             BOOLEAN NOT NULL,
    reason                 TEXT NOT NULL CHECK (reason IN ('AGEING','CONTAGION')),

    exposure               NUMERIC(23,5) NOT NULL CHECK (exposure >= 0),
    collateral             NUMERIC(23,5) NOT NULL CHECK (collateral >= 0),
    provision_base         NUMERIC(23,5) NOT NULL CHECK (provision_base >= 0),
    provision_rate_percent NUMERIC(12,6) NOT NULL,
    provision_amount       NUMERIC(23,5) NOT NULL CHECK (provision_amount >= 0),
    -- Dotation si positif, reprise si negatif. C'est ce qui est reellement comptabilise.
    posted_delta           NUMERIC(23,5) NOT NULL DEFAULT 0,
    -- Vrai des lors que la classe atteint le seuil de suspension du profil.
    suspended              BOOLEAN NOT NULL DEFAULT FALSE,
    -- Interets constates en produits et transferes en interets reserves ce jour-la. Non nul
    -- au seul arrete qui franchit le seuil : au-dela, les interets naissent deja reserves.
    suspended_interest     NUMERIC(23,5) NOT NULL DEFAULT 0,

    entry_id               UUID,
    batch_run_id           UUID,
    status                 TEXT NOT NULL DEFAULT 'ACTIVE'
                           CHECK (status IN ('ACTIVE','REVERSED')),
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_classification_base CHECK (provision_base <= exposure)
);

-- Une seule classification active par credit et par date : un arrete rejoue retrouve la
-- sienne au lieu d'en empiler une seconde.
CREATE UNIQUE INDEX uq_classification_active
    ON loan_classification(contract_id, classified_on) WHERE status = 'ACTIVE';

CREATE INDEX idx_classification_contract
    ON loan_classification(contract_id, classified_on DESC) WHERE status = 'ACTIVE';
CREATE INDEX idx_classification_run ON loan_classification(batch_run_id)
    WHERE batch_run_id IS NOT NULL;

CREATE OR REPLACE FUNCTION forbid_classification_mutation() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Une classification ne se supprime pas : l''annulation la passe en REVERSED.'
          USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF (NEW.contract_id, NEW.classified_on, NEW.days_past_due, NEW.bucket_code, NEW.exposure,
        NEW.collateral, NEW.provision_base, NEW.provision_amount, NEW.posted_delta)
       IS DISTINCT FROM
       (OLD.contract_id, OLD.classified_on, OLD.days_past_due, OLD.bucket_code, OLD.exposure,
        OLD.collateral, OLD.provision_base, OLD.provision_amount, OLD.posted_delta) THEN
        RAISE EXCEPTION 'La classification du % au % est figee : seul son statut evolue.',
            OLD.contract_id, OLD.classified_on USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_classification_immutable
    BEFORE UPDATE OR DELETE ON loan_classification
    FOR EACH ROW EXECUTE FUNCTION forbid_classification_mutation();

-- -------------------------------------------------------------------------------------
--  Garanties.
--
--  Modele volontairement minimal : une valeur et une quotite d'eligibilite, datees.
--  L'eligibilite reelle — nature de la surete, rang, fraicheur de l'expertise, opposabilite
--  — releve d'un module de garanties qui n'est pas ecrit. La quotite en tient lieu, et le
--  dit.
-- -------------------------------------------------------------------------------------
CREATE TABLE loan_collateral (
    id                    UUID PRIMARY KEY,
    contract_id           UUID NOT NULL REFERENCES loan_contract(id),
    label                 TEXT NOT NULL,
    kind                  TEXT NOT NULL,
    value                 NUMERIC(23,5) NOT NULL CHECK (value >= 0),
    eligible_rate_percent NUMERIC(12,6) NOT NULL
                          CHECK (eligible_rate_percent BETWEEN 0 AND 100),
    valid_from            DATE NOT NULL,
    valid_to              DATE,
    created_by            UUID NOT NULL,

    CONSTRAINT ck_collateral_validity CHECK (valid_to IS NULL OR valid_to >= valid_from)
);

CREATE INDEX idx_collateral_contract ON loan_collateral(contract_id, valid_from);
