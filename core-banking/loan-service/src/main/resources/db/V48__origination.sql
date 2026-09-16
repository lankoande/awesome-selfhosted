-- =====================================================================================
--  Origination : la demande, l'instruction, la decision, les conditions
--
--  Un contrat de credit ne nait pas d'une saisie : il nait d'une demande instruite et d'une
--  decision prise par quelqu'un qui en avait le pouvoir. Le socle portait jusqu'ici le
--  contrat et son deblocage — c'est-a-dire la fin de l'histoire. Ce qui manquait est ce que
--  regarde le controle interne : qui a demande quoi, sur quels revenus, qui a decide, dans
--  quelle limite, et ce qui restait a fournir avant que l'argent sorte.
--
--  Trois regles portees par le schema :
--    * une decision se prend a deux, et elle a une duree de validite  -> contrainte + date
--    * un dossier ne devient contrat qu'une fois                      -> unicite du contrat
--    * une condition suspensive levee porte qui l'a levee, et quand   -> colonnes obligatoires
-- =====================================================================================

-- -------------------------------------------------------------------------------------
--  Politique d'octroi : ce que la banque exige d'un dossier, par produit.
--
--  Elle ne refuse pas : elle nomme les depassements. Un dossier hors politique reste
--  decidable, mais la derogation doit etre ecrite — c'est la pratique, et c'est ce qui la
--  rend verifiable. Sans politique declaree, rien n'est exige : la restriction n'apparait
--  qu'avec la regle.
-- -------------------------------------------------------------------------------------
CREATE TABLE lending_policy (
    id                            UUID PRIMARY KEY,
    legal_entity_id               UUID NOT NULL REFERENCES legal_entity(id),
    product_code                  TEXT NOT NULL,
    -- Taux d'endettement maximal : part des revenus qu'emportent les echeances, la nouvelle
    -- comprise. C'est le seul critere qui protege l'emprunteur autant que la banque.
    max_debt_service_ratio_percent NUMERIC(5,2)
                                  CHECK (max_debt_service_ratio_percent IS NULL
                                         OR (max_debt_service_ratio_percent > 0
                                             AND max_debt_service_ratio_percent <= 100)),
    max_amount                    NUMERIC(23,5) CHECK (max_amount IS NULL OR max_amount > 0),
    max_term_months               INTEGER CHECK (max_term_months IS NULL OR max_term_months > 0),
    min_down_payment_percent      NUMERIC(5,2)
                                  CHECK (min_down_payment_percent IS NULL
                                         OR (min_down_payment_percent >= 0
                                             AND min_down_payment_percent < 100)),
    collateral_required           BOOLEAN NOT NULL DEFAULT FALSE,
    -- Duree de validite de l'offre : une decision prise sur une situation ancienne n'est plus
    -- une decision. Passe ce delai, le dossier expire et se reinstruit.
    decision_validity_days        INTEGER NOT NULL DEFAULT 30
                                  CHECK (decision_validity_days > 0),
    valid_from                    DATE NOT NULL,
    valid_to                      DATE,
    created_by                    UUID NOT NULL,
    approved_by                   UUID NOT NULL,
    created_at                    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_lending_policy_approval CHECK (approved_by <> created_by),
    CONSTRAINT ck_lending_policy_dates CHECK (valid_to IS NULL OR valid_to >= valid_from),
    CONSTRAINT ex_lending_policy EXCLUDE USING gist (
        legal_entity_id WITH =, product_code WITH =,
        daterange(valid_from, valid_to, '[]') WITH &&)
);

-- -------------------------------------------------------------------------------------
--  La demande.
-- -------------------------------------------------------------------------------------
CREATE TABLE loan_application (
    id                    UUID PRIMARY KEY,
    legal_entity_id       UUID NOT NULL REFERENCES legal_entity(id),
    branch_id             UUID REFERENCES branch(id),
    reference             TEXT NOT NULL,
    customer_id           UUID NOT NULL REFERENCES party(id),
    product_code          TEXT NOT NULL,
    currency              CHAR(3) NOT NULL REFERENCES currency(code),
    requested_amount      NUMERIC(23,5) NOT NULL CHECK (requested_amount > 0),
    requested_term_months INTEGER NOT NULL CHECK (requested_term_months > 0),
    purpose               TEXT,
    requested_on          DATE NOT NULL,
    status                TEXT NOT NULL DEFAULT 'SUBMITTED'
                          CHECK (status IN ('SUBMITTED','UNDER_REVIEW','APPROVED','REJECTED',
                                            'CANCELLED','CONTRACTED','EXPIRED')),
    -- Le contrat ne au dossier : un dossier n'en produit qu'un.
    contract_id           UUID REFERENCES loan_contract(id),
    closed_on             DATE,
    closing_reason        TEXT,
    created_by            UUID NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_application_reference UNIQUE (legal_entity_id, reference),
    CONSTRAINT ck_application_contracted CHECK (status <> 'CONTRACTED' OR contract_id IS NOT NULL)
);

CREATE UNIQUE INDEX uq_application_contract ON loan_application(contract_id)
    WHERE contract_id IS NOT NULL;
CREATE INDEX idx_application_entity ON loan_application(legal_entity_id, status, reference);
CREATE INDEX idx_application_customer ON loan_application(customer_id);

-- -------------------------------------------------------------------------------------
--  L'instruction : ce qui a ete constate, et ce que la politique en dit.
--
--  Les engagements existants ne sont pas declares : ils sont lus dans les echeanciers en
--  vigueur du client. Un emprunteur oublie rarement ses revenus et souvent ses dettes.
-- -------------------------------------------------------------------------------------
CREATE TABLE loan_assessment (
    id                       UUID PRIMARY KEY,
    application_id           UUID NOT NULL REFERENCES loan_application(id),
    monthly_income           NUMERIC(23,5) NOT NULL CHECK (monthly_income > 0),
    monthly_charges          NUMERIC(23,5) NOT NULL DEFAULT 0 CHECK (monthly_charges >= 0),
    existing_commitments     NUMERIC(23,5) NOT NULL CHECK (existing_commitments >= 0),
    requested_instalment     NUMERIC(23,5) NOT NULL CHECK (requested_instalment > 0),
    debt_service_ratio_percent NUMERIC(8,2) NOT NULL CHECK (debt_service_ratio_percent >= 0),
    down_payment             NUMERIC(23,5) NOT NULL DEFAULT 0 CHECK (down_payment >= 0),
    -- Le score vient d'ailleurs quand il vient : le socle le porte avec sa source, il ne le
    -- calcule pas. Un moteur de score maison se demode et ne se defend pas devant un regulateur.
    external_score           INTEGER,
    score_source             TEXT,
    breaches                 TEXT,
    assessed_on              DATE NOT NULL,
    assessed_by              UUID NOT NULL,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_assessment_score CHECK (external_score IS NULL OR score_source IS NOT NULL)
);

CREATE INDEX idx_assessment_application ON loan_assessment(application_id, assessed_on DESC);

-- -------------------------------------------------------------------------------------
--  La decision : sens, montant accorde, duree, taux, validite — et la derogation, ecrite.
-- -------------------------------------------------------------------------------------
CREATE TABLE loan_application_decision (
    id                   UUID PRIMARY KEY,
    application_id       UUID NOT NULL REFERENCES loan_application(id),
    outcome              TEXT NOT NULL CHECK (outcome IN ('APPROVED','REJECTED')),
    granted_amount       NUMERIC(23,5) CHECK (granted_amount IS NULL OR granted_amount > 0),
    granted_term_months  INTEGER CHECK (granted_term_months IS NULL OR granted_term_months > 0),
    granted_rate_percent NUMERIC(12,6)
                         CHECK (granted_rate_percent IS NULL OR granted_rate_percent >= 0),
    decided_on           DATE NOT NULL,
    valid_until          DATE,
    reason               TEXT NOT NULL,
    waiver_reason        TEXT,
    decided_by           UUID NOT NULL,
    approved_by          UUID NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_decision_approval CHECK (approved_by <> decided_by),
    CONSTRAINT ck_decision_granted CHECK (
        outcome <> 'APPROVED'
        OR (granted_amount IS NOT NULL AND granted_term_months IS NOT NULL
            AND valid_until IS NOT NULL)),
    CONSTRAINT ck_decision_validity CHECK (valid_until IS NULL OR valid_until >= decided_on)
);

-- Une seule decision par dossier : refaire une decision, c'est reinstruire le dossier.
CREATE UNIQUE INDEX uq_decision_application ON loan_application_decision(application_id);

-- -------------------------------------------------------------------------------------
--  Conditions : suspensives, qui retiennent le versement, ou de suivi, qui ne le retiennent
--  pas mais restent au dossier.
--
--  Une condition suspensive ne bloque pas la signature du contrat — elle suspend l'obligation
--  de verser. C'est le deblocage qu'elle retient, et c'est la que le socle la verifie.
-- -------------------------------------------------------------------------------------
CREATE TABLE loan_condition (
    id             UUID PRIMARY KEY,
    application_id UUID NOT NULL REFERENCES loan_application(id),
    kind           TEXT NOT NULL CHECK (kind IN ('PRECEDENT','SUBSEQUENT')),
    description    TEXT NOT NULL,
    due_on         DATE,
    cleared_on     DATE,
    cleared_by     UUID,
    cleared_approved_by UUID,
    evidence       TEXT,
    created_by     UUID NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_condition_cleared CHECK (
        (cleared_on IS NULL AND cleared_by IS NULL AND cleared_approved_by IS NULL)
        OR (cleared_on IS NOT NULL AND cleared_by IS NOT NULL
            AND cleared_approved_by IS NOT NULL AND cleared_approved_by <> cleared_by))
);

CREATE INDEX idx_condition_open ON loan_condition(application_id) WHERE cleared_on IS NULL;

-- -------------------------------------------------------------------------------------
--  Journal du dossier : ce qui lui est arrive, par qui — et par quel traitement lorsque
--  c'est un arrete, pour que son annulation le defasse.
-- -------------------------------------------------------------------------------------
CREATE TABLE loan_application_event (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    application_id UUID NOT NULL REFERENCES loan_application(id),
    kind           TEXT NOT NULL CHECK (kind IN ('SUBMITTED','ASSESSED','DECIDED','CONDITION_ADDED',
                                                 'CONDITION_CLEARED','CONTRACTED','CANCELLED',
                                                 'EXPIRED')),
    occurred_on    DATE NOT NULL,
    actor_id       UUID NOT NULL,
    approver_id    UUID,
    detail         TEXT,
    batch_run_id   UUID,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_application_event ON loan_application_event(application_id, occurred_on);
CREATE INDEX idx_application_event_run ON loan_application_event(batch_run_id)
    WHERE batch_run_id IS NOT NULL;

ALTER TABLE lending_policy ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON lending_policy
    USING (legal_entity_id = ledger_current_entity())
    WITH CHECK (legal_entity_id = ledger_current_entity());

ALTER TABLE loan_application ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON loan_application
    USING (legal_entity_id = ledger_current_entity())
    WITH CHECK (legal_entity_id = ledger_current_entity());

ALTER TABLE loan_assessment ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON loan_assessment
    USING (EXISTS (SELECT 1 FROM loan_application a WHERE a.id = loan_assessment.application_id))
    WITH CHECK (EXISTS (SELECT 1 FROM loan_application a WHERE a.id = loan_assessment.application_id));

ALTER TABLE loan_application_decision ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON loan_application_decision
    USING (EXISTS (SELECT 1 FROM loan_application a
                    WHERE a.id = loan_application_decision.application_id))
    WITH CHECK (EXISTS (SELECT 1 FROM loan_application a
                         WHERE a.id = loan_application_decision.application_id));

ALTER TABLE loan_condition ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON loan_condition
    USING (EXISTS (SELECT 1 FROM loan_application a WHERE a.id = loan_condition.application_id))
    WITH CHECK (EXISTS (SELECT 1 FROM loan_application a WHERE a.id = loan_condition.application_id));

ALTER TABLE loan_application_event ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON loan_application_event
    USING (EXISTS (SELECT 1 FROM loan_application a
                    WHERE a.id = loan_application_event.application_id))
    WITH CHECK (EXISTS (SELECT 1 FROM loan_application a
                         WHERE a.id = loan_application_event.application_id));
