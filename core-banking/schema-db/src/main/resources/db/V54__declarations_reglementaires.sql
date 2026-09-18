--liquibase formatted sql
--changeset socle:54 splitStatements:false
--comment declarations reglementaires
-- =====================================================================================
--  Declarations reglementaires
--
--  Ce que le superviseur attend n'est pas un fichier : c'est un etat qu'on peut lui
--  reproduire. Trois consequences de conception, et elles commandent tout ce qui suit.
--
--  1. La declaration est du parametrage. Les destinataires, les periodicites, les delais
--     et les seuils changent par circulaire — parfois deux fois dans l'annee. Les coder
--     obligerait a livrer pour deplacer un seuil, et une banque qui attend la prochaine
--     version est en retard declaratif.
--
--  2. L'etat produit est fige. Il porte ses lignes, et le parametrage sous lequel il a
--     ete produit. Un etat regenere six mois plus tard doit etre identique a celui qui a
--     ete transmis ; s'il ne l'est pas, ce n'est pas l'etat qui a bouge, ce sont les
--     donnees — et c'est exactement ce que l'inspection cherche.
--
--  3. L'echeance est une donnee, pas une convention. Le retard declaratif est en
--     lui-meme un manquement : il se constate a l'arrete, il ne se decouvre pas quand le
--     superviseur appelle.
-- =====================================================================================

CREATE TABLE regulatory_declaration (
    id                UUID PRIMARY KEY,
    legal_entity_id   UUID NOT NULL REFERENCES legal_entity(id),
    code              TEXT NOT NULL,
    label             TEXT NOT NULL,

    -- A qui l'etat est adresse. Le destinataire n'est pas decoratif : il decide du secret
    -- (le bureau d'information sur le credit exige le consentement du client) et du
    -- circuit de transmission.
    recipient         TEXT NOT NULL
                      CHECK (recipient IN ('CENTRAL_BANK','BANKING_COMMISSION',
                                           'CREDIT_BUREAU','TAX_AUTHORITY')),

    -- Ce que le code sait produire. Ajouter une methode est une livraison : elle change
    -- ce que la banque sait declarer.
    method            TEXT NOT NULL
                      CHECK (method IN ('ACCOUNTING_SITUATION','CREDIT_REGISTRY',
                                        'PAYMENT_INCIDENTS','CREDIT_BUREAU')),

    frequency         TEXT NOT NULL
                      CHECK (frequency IN ('MONTHLY','QUARTERLY','YEARLY')),
    -- Delai de transmission apres la fin de periode, en jours calendaires.
    deadline_days     INTEGER NOT NULL CHECK (deadline_days > 0),
    -- Seuil de declaration, quand la methode en admet un : la centrale des risques ne
    -- recense que les engagements qui comptent.
    threshold_amount  NUMERIC(23,5) CHECK (threshold_amount IS NULL OR threshold_amount > 0),

    valid_from        DATE NOT NULL,
    valid_to          DATE,
    created_by        UUID NOT NULL,
    approved_by       UUID NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_declaration_code UNIQUE (legal_entity_id, code, valid_from),
    CONSTRAINT ck_declaration_validity CHECK (valid_to IS NULL OR valid_to >= valid_from),
    -- Une declaration engage la banque devant son superviseur : elle se decide a deux.
    CONSTRAINT ck_declaration_approval CHECK (approved_by <> created_by)
);
CREATE INDEX idx_declaration_entity ON regulatory_declaration(legal_entity_id, code, valid_from);

-- -------------------------------------------------------------------------------------
--  L'etat produit, et ce qu'il contient
-- -------------------------------------------------------------------------------------

CREATE TABLE report_filing (
    id                UUID PRIMARY KEY,
    legal_entity_id   UUID NOT NULL REFERENCES legal_entity(id),
    declaration_id    UUID NOT NULL REFERENCES regulatory_declaration(id),
    declaration_code  TEXT NOT NULL,

    period_start      DATE NOT NULL,
    period_end        DATE NOT NULL,
    due_on            DATE NOT NULL,
    produced_on       DATE NOT NULL,

    -- Le parametrage sous lequel l'etat a ete produit, recopie. Le lire dans la
    -- declaration six mois plus tard donnerait le seuil d'aujourd'hui, pas celui du jour
    -- de la production : l'etat ne serait plus reproductible.
    threshold_used    NUMERIC(23,5),
    method            TEXT NOT NULL,

    line_count        INTEGER NOT NULL CHECK (line_count >= 0),
    total_amount      NUMERIC(23,5) NOT NULL,
    currency          CHAR(3) NOT NULL REFERENCES currency(code),

    status            TEXT NOT NULL DEFAULT 'PRODUCED'
                      CHECK (status IN ('PRODUCED','TRANSMITTED','CANCELLED')),
    produced_by       UUID NOT NULL,
    transmitted_on    DATE,
    transmission_reference TEXT,
    transmitted_by    UUID,
    approved_by       UUID,
    cancelled_on      DATE,
    cancellation_reason TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_filing_period CHECK (period_end >= period_start),
    CONSTRAINT ck_filing_due CHECK (due_on > period_end),
    -- Transmise, elle porte sa preuve de depot : la date, la reference rendue, et les
    -- deux personnes qui l'ont engagee.
    CONSTRAINT ck_filing_transmitted CHECK (
        status <> 'TRANSMITTED'
        OR (transmitted_on IS NOT NULL AND transmission_reference IS NOT NULL
            AND transmitted_by IS NOT NULL AND approved_by IS NOT NULL
            AND approved_by <> transmitted_by)),
    CONSTRAINT ck_filing_cancelled CHECK (
        status <> 'CANCELLED'
        OR (cancelled_on IS NOT NULL AND cancellation_reason IS NOT NULL))
);

-- Une seule declaration en vigueur par periode : deux etats transmis pour le meme mois
-- seraient deux verites, et le superviseur ne saurait pas laquelle est la bonne.
CREATE UNIQUE INDEX uq_filing_period ON report_filing(declaration_id, period_start, period_end)
    WHERE status <> 'CANCELLED';
CREATE INDEX idx_filing_entity ON report_filing(legal_entity_id, declaration_code, period_end);

CREATE TABLE report_filing_line (
    filing_id         UUID NOT NULL REFERENCES report_filing(id) ON DELETE CASCADE,
    line_no           INTEGER NOT NULL,

    -- Ce que la ligne designe : un tiers (centrale des risques, bureau du credit), un
    -- compte (incidents de paiement), un compte general (situation comptable).
    subject_kind      TEXT NOT NULL CHECK (subject_kind IN ('PARTY','ACCOUNT','GL_ACCOUNT')),
    subject_id        UUID NOT NULL,
    subject_reference TEXT NOT NULL,
    label             TEXT NOT NULL,

    amount            NUMERIC(23,5) NOT NULL,
    currency          CHAR(3) NOT NULL REFERENCES currency(code),
    -- Le detail que la methode porte : engagement hors bilan, classe de risque, nombre
    -- d'incidents. Ce qui n'a pas de sens pour une methode y est nul.
    off_balance       NUMERIC(23,5),
    classification    TEXT,
    days_past_due     INTEGER,
    occurrences       INTEGER,
    detail            TEXT,

    PRIMARY KEY (filing_id, line_no)
);
CREATE INDEX idx_filing_line_subject ON report_filing_line(subject_id);

-- -------------------------------------------------------------------------------------
--  Consentement au bureau d'information sur le credit
-- -------------------------------------------------------------------------------------

-- Le client consent, ou il ne consent pas — et il peut revenir sur son consentement. Sans
-- lui, rien de son historique ne sort de la banque : c'est une donnee personnelle, et la
-- declarer sans accord est une faute, pas un oubli.
CREATE TABLE party_credit_bureau_consent (
    party_id          UUID PRIMARY KEY REFERENCES party(id),
    legal_entity_id   UUID NOT NULL REFERENCES legal_entity(id),
    granted           BOOLEAN NOT NULL,
    granted_on        DATE NOT NULL,
    revoked_on        DATE,
    recorded_by       UUID NOT NULL,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_consent_revocation CHECK (granted OR revoked_on IS NOT NULL)
);

-- -------------------------------------------------------------------------------------
--  Cloisonnement par entite
-- -------------------------------------------------------------------------------------

ALTER TABLE regulatory_declaration ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON regulatory_declaration
    USING (legal_entity_id = current_setting('app.entity_id', true)::uuid)
    WITH CHECK (legal_entity_id = current_setting('app.entity_id', true)::uuid);

ALTER TABLE report_filing ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON report_filing
    USING (legal_entity_id = current_setting('app.entity_id', true)::uuid)
    WITH CHECK (legal_entity_id = current_setting('app.entity_id', true)::uuid);

ALTER TABLE report_filing_line ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON report_filing_line
    USING (EXISTS (SELECT 1 FROM report_filing f WHERE f.id = filing_id
                     AND f.legal_entity_id = current_setting('app.entity_id', true)::uuid))
    WITH CHECK (EXISTS (SELECT 1 FROM report_filing f WHERE f.id = filing_id
                          AND f.legal_entity_id = current_setting('app.entity_id', true)::uuid));

ALTER TABLE party_credit_bureau_consent ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON party_credit_bureau_consent
    USING (legal_entity_id = current_setting('app.entity_id', true)::uuid)
    WITH CHECK (legal_entity_id = current_setting('app.entity_id', true)::uuid);
