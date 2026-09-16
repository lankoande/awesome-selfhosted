-- =====================================================================================
--  Dossier client : documents, politique de diligence, beneficiaires effectifs, relations
--
--  Le dossier de connaissance client ne se resume pas a un statut : il se compose de pieces
--  datees, dont certaines expirent. Ce que la banque exige — quelles pieces, pour quelle
--  nature de tiers, a quel niveau de diligence, et si les beneficiaires effectifs doivent
--  etre connus — est du parametrage, pas une constante applicative : il se declare a deux et
--  se lit. Un dossier incomplet ne bloque pas les comptes existants ; il empeche d'en ouvrir
--  de nouveaux. La restriction est progressive, jamais un blocage brutal non annonce.
-- =====================================================================================

-- -------------------------------------------------------------------------------------
--  Politique de diligence : par nature de tiers et niveau, ce que la banque exige.
-- -------------------------------------------------------------------------------------
CREATE TABLE kyc_policy (
    id                          UUID PRIMARY KEY,
    legal_entity_id             UUID NOT NULL REFERENCES legal_entity(id),
    party_kind                  TEXT NOT NULL CHECK (party_kind IN ('NATURAL_PERSON','LEGAL_PERSON')),
    kyc_level                   TEXT NOT NULL CHECK (kyc_level IN ('SIMPLIFIED','STANDARD','ENHANCED')),
    beneficial_owners_required  BOOLEAN NOT NULL DEFAULT FALSE,
    -- Part de detention a partir de laquelle un beneficiaire effectif doit etre connu.
    ownership_threshold_percent NUMERIC(5,2) NOT NULL DEFAULT 25
                                CHECK (ownership_threshold_percent > 0
                                       AND ownership_threshold_percent <= 100),
    created_by                  UUID NOT NULL,
    approved_by                 UUID NOT NULL,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_kyc_policy UNIQUE (legal_entity_id, party_kind, kyc_level),
    CONSTRAINT ck_kyc_policy_approval CHECK (approved_by <> created_by),
    -- Les beneficiaires effectifs sont une exigence des personnes morales : une personne
    -- physique n'en a pas.
    CONSTRAINT ck_kyc_policy_owners CHECK (
        NOT beneficial_owners_required OR party_kind = 'LEGAL_PERSON')
);

CREATE TABLE kyc_policy_document (
    policy_id     UUID NOT NULL REFERENCES kyc_policy(id) ON DELETE CASCADE,
    document_kind TEXT NOT NULL CHECK (document_kind IN (
        'IDENTITY','ADDRESS_PROOF','INCOME_PROOF','ARTICLES','TRADE_REGISTRY_EXTRACT',
        'TAX_CERTIFICATE','SIGNATURE_SPECIMEN','PHOTO','OTHER')),
    PRIMARY KEY (policy_id, document_kind)
);

-- -------------------------------------------------------------------------------------
--  Pieces du dossier. Une piece remplacee reste : le dossier garde ce qu'il a connu, et
--  l'audit doit pouvoir dire sur quelle piece une ouverture a ete decidee.
-- -------------------------------------------------------------------------------------
CREATE TABLE party_document (
    id              UUID PRIMARY KEY,
    legal_entity_id UUID NOT NULL REFERENCES legal_entity(id),
    party_id        UUID NOT NULL REFERENCES party(id),
    kind            TEXT NOT NULL CHECK (kind IN (
        'IDENTITY','ADDRESS_PROOF','INCOME_PROOF','ARTICLES','TRADE_REGISTRY_EXTRACT',
        'TAX_CERTIFICATE','SIGNATURE_SPECIMEN','PHOTO','OTHER')),
    reference       TEXT,
    issuer          TEXT,
    issued_on       DATE,
    expires_on      DATE,
    collected_on    DATE NOT NULL,
    superseded_by   UUID REFERENCES party_document(id),
    created_by      UUID NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_document_dates CHECK (expires_on IS NULL OR issued_on IS NULL
                                        OR expires_on >= issued_on)
);
CREATE INDEX idx_document_current ON party_document(party_id, kind) WHERE superseded_by IS NULL;
CREATE INDEX idx_document_expiry ON party_document(legal_entity_id, expires_on)
    WHERE superseded_by IS NULL AND expires_on IS NOT NULL;

-- -------------------------------------------------------------------------------------
--  Beneficiaires effectifs d'une personne morale : qui la detient, et pour quelle part.
-- -------------------------------------------------------------------------------------
CREATE TABLE beneficial_owner (
    id                UUID PRIMARY KEY,
    legal_entity_id   UUID NOT NULL REFERENCES legal_entity(id),
    party_id          UUID NOT NULL REFERENCES party(id),
    owner_party_id    UUID NOT NULL REFERENCES party(id),
    ownership_percent NUMERIC(5,2) NOT NULL
                      CHECK (ownership_percent > 0 AND ownership_percent <= 100),
    declared_on       DATE NOT NULL,
    valid_to          DATE,
    created_by        UUID NOT NULL,
    approved_by       UUID NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_owner_distinct CHECK (party_id <> owner_party_id),
    CONSTRAINT ck_owner_approval CHECK (approved_by <> created_by),
    CONSTRAINT ck_owner_dates CHECK (valid_to IS NULL OR valid_to >= declared_on)
);
CREATE UNIQUE INDEX uq_owner_current ON beneficial_owner(party_id, owner_party_id)
    WHERE valid_to IS NULL;

-- -------------------------------------------------------------------------------------
--  Relations entre tiers : mandataire, representant legal, conjoint, groupe.
-- -------------------------------------------------------------------------------------
CREATE TABLE party_relationship (
    id              UUID PRIMARY KEY,
    legal_entity_id UUID NOT NULL REFERENCES legal_entity(id),
    from_party_id   UUID NOT NULL REFERENCES party(id),
    to_party_id     UUID NOT NULL REFERENCES party(id),
    kind            TEXT NOT NULL CHECK (kind IN (
        'LEGAL_REPRESENTATIVE','MANDATE','SPOUSE','PARENT_COMPANY','GROUP_MEMBER')),
    valid_from      DATE NOT NULL,
    valid_to        DATE,
    created_by      UUID NOT NULL,
    approved_by     UUID NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_relationship_distinct CHECK (from_party_id <> to_party_id),
    CONSTRAINT ck_relationship_approval CHECK (approved_by <> created_by),
    CONSTRAINT ck_relationship_dates CHECK (valid_to IS NULL OR valid_to >= valid_from)
);
CREATE UNIQUE INDEX uq_relationship_current
    ON party_relationship(from_party_id, to_party_id, kind) WHERE valid_to IS NULL;
CREATE INDEX idx_relationship_to ON party_relationship(to_party_id) WHERE valid_to IS NULL;

-- Le dossier s'enrichit d'evenements : ce qui lui est arrive se lit dans un seul journal.
ALTER TABLE party_event DROP CONSTRAINT party_event_kind_check;
ALTER TABLE party_event ADD CONSTRAINT party_event_kind_check CHECK (kind IN (
    'CREATED','KYC_VERIFIED','KYC_EXPIRED','BLOCKED','UNBLOCKED','SCREENING_MATCH',
    'IDENTIFIER_ADDED','DOCUMENT_ADDED','DOCUMENT_EXPIRED','RELATIONSHIP_ADDED',
    'RELATIONSHIP_ENDED','BENEFICIAL_OWNER_ADDED','BENEFICIAL_OWNER_ENDED'));

ALTER TABLE kyc_policy ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON kyc_policy
    USING (legal_entity_id = ledger_current_entity())
    WITH CHECK (legal_entity_id = ledger_current_entity());
ALTER TABLE party_document ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON party_document
    USING (legal_entity_id = ledger_current_entity())
    WITH CHECK (legal_entity_id = ledger_current_entity());
ALTER TABLE beneficial_owner ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON beneficial_owner
    USING (legal_entity_id = ledger_current_entity())
    WITH CHECK (legal_entity_id = ledger_current_entity());
ALTER TABLE party_relationship ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON party_relationship
    USING (legal_entity_id = ledger_current_entity())
    WITH CHECK (legal_entity_id = ledger_current_entity());
