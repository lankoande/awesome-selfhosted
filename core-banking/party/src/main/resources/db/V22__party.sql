-- =====================================================================================
--  Referentiel client
--
--  Le client est une entite distincte du compte : il detient N comptes et N credits, un
--  compte a N titulaires. Le modeliser comme un attribut du compte rend impossibles la
--  vue client, l'agregation des risques et la contagion de declassement — qui, jusqu'ici,
--  portait sur un identifiant nu.
--
--  Le dedoublonnage est porte par la base : un identifiant officiel — piece d'identite,
--  identifiant fiscal, registre du commerce, identifiant a la centrale des risques —
--  designe une seule personne dans l'entite. Un client en double casse les plafonds
--  d'engagement et les etats de concentration.
-- =====================================================================================
CREATE TABLE party (
    id                         UUID PRIMARY KEY,
    legal_entity_id            UUID NOT NULL REFERENCES legal_entity(id),
    reference                  TEXT NOT NULL,
    kind                       TEXT NOT NULL CHECK (kind IN ('NATURAL_PERSON','LEGAL_PERSON')),
    display_name               TEXT NOT NULL,
    birth_or_registration_date DATE,
    country_code               CHAR(2) NOT NULL,
    segment                    TEXT,

    -- Connaissance client : niveau de diligence derive du risque, statut, dates de
    -- verification et de revue. Un dossier verifie porte toujours ses deux dates.
    kyc_level                  TEXT NOT NULL DEFAULT 'STANDARD'
                               CHECK (kyc_level IN ('SIMPLIFIED','STANDARD','ENHANCED')),
    kyc_status                 TEXT NOT NULL DEFAULT 'PENDING'
                               CHECK (kyc_status IN ('PENDING','VERIFIED','EXPIRED','BLOCKED')),
    kyc_verified_on            DATE,
    kyc_review_due             DATE,
    kyc_verified_by            UUID,
    risk_rating                TEXT CHECK (risk_rating IN ('LOW','MEDIUM','HIGH')),

    status                     TEXT NOT NULL DEFAULT 'ACTIVE'
                               CHECK (status IN ('ACTIVE','BLOCKED','CLOSED')),
    status_reason              TEXT,
    created_by                 UUID NOT NULL,
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_party_reference UNIQUE (legal_entity_id, reference),
    CONSTRAINT ck_kyc_verified CHECK (
        kyc_status <> 'VERIFIED' OR (kyc_verified_on IS NOT NULL AND kyc_review_due IS NOT NULL))
);

CREATE INDEX idx_party_review ON party(legal_entity_id, kyc_review_due) WHERE kyc_status = 'VERIFIED';

CREATE TABLE party_identifier (
    party_id        UUID NOT NULL REFERENCES party(id),
    legal_entity_id UUID NOT NULL REFERENCES legal_entity(id),
    kind            TEXT NOT NULL CHECK (kind IN (
                        'NATIONAL_ID','PASSPORT','RESIDENCE_PERMIT','TAX_ID','TRADE_REGISTRY',
                        'CREDIT_BUREAU','PHONE','EMAIL')),
    value           TEXT NOT NULL,
    issued_on       DATE,
    expires_on      DATE,
    issuer          TEXT,
    PRIMARY KEY (party_id, kind, value)
);

-- Un identifiant officiel designe une seule personne dans l'entite. Le service le verifie
-- avant d'inserer et nomme le doublon ; l'index reste, pour deux creations concurrentes.
CREATE UNIQUE INDEX uq_party_official_identifier
    ON party_identifier(legal_entity_id, kind, value)
    WHERE kind IN ('NATIONAL_ID','PASSPORT','RESIDENCE_PERMIT','TAX_ID','TRADE_REGISTRY',
                   'CREDIT_BUREAU');

-- -------------------------------------------------------------------------------------
--  Titulaires : le lien entre un compte et les personnes qui en repondent, date.
-- -------------------------------------------------------------------------------------
CREATE TABLE account_holder (
    account_id UUID NOT NULL REFERENCES account(id),
    party_id   UUID NOT NULL REFERENCES party(id),
    role       TEXT NOT NULL CHECK (role IN ('HOLDER','JOINT_HOLDER','MANDATE','LEGAL_REPRESENTATIVE')),
    valid_from DATE NOT NULL,
    valid_to   DATE,
    created_by UUID NOT NULL,
    PRIMARY KEY (account_id, party_id, role, valid_from),
    CONSTRAINT ck_holder_dates CHECK (valid_to IS NULL OR valid_to >= valid_from)
);

CREATE INDEX idx_holder_party ON account_holder(party_id) WHERE valid_to IS NULL;

-- -------------------------------------------------------------------------------------
--  Evenements du dossier : ce qui est arrive au client, quand, par qui — et par quel
--  traitement lorsque c'est un arrete, pour que son annulation le defasse.
-- -------------------------------------------------------------------------------------
CREATE TABLE party_event (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    party_id     UUID NOT NULL REFERENCES party(id),
    kind         TEXT NOT NULL CHECK (kind IN ('CREATED','KYC_VERIFIED','KYC_EXPIRED','BLOCKED',
                                                'UNBLOCKED','SCREENING_MATCH','IDENTIFIER_ADDED')),
    occurred_on  DATE NOT NULL,
    actor_id     UUID NOT NULL,
    approver_id  UUID,
    detail       TEXT,
    batch_run_id UUID,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_party_event ON party_event(party_id, occurred_on);
CREATE INDEX idx_party_event_run ON party_event(batch_run_id) WHERE batch_run_id IS NOT NULL;
