--liquibase formatted sql
--changeset socle:53 splitStatements:false
--comment lcb ft
-- =====================================================================================
--  LCB-FT : filtrage, surveillance, alertes, declaration de soupcon
--
--  Trois principes gouvernent ce schema, et chacun se lit dans les tables.
--
--  1. LE SCENARIO EST DU PARAMETRAGE, LA METHODE EST DU CODE. Les seuils, les fenetres et
--     les populations changent avec la reglementation et les typologies locales ; la facon
--     de compter, non. Coder « 5 000 000 XOF sur 30 jours » condamnerait la banque a une
--     livraison a chaque circulaire.
--
--  2. UNE ALERTE N'EST PAS UNE SANCTION. La surveillance produit un constat a instruire,
--     jamais un blocage automatique : bloquer un client sur un scenario statistique le
--     prive de son argent sur une presomption. Seul le filtrage bloque — operer avec une
--     personne listee est l'infraction elle-meme, pas un soupcon.
--
--  3. LA DECLARATION EST SECRETE. Ni l'alerte ni la declaration ne sont visibles du client,
--     et le blocage qui en decoule ne dit pas pourquoi : informer la personne declaree est
--     un delit (« tipping-off »). C'est pourquoi ces tables ne sont lues que sous
--     l'habilitation de conformite, et pourquoi aucun libelle d'alerte ne remonte dans le
--     dossier client.
-- =====================================================================================

-- -------------------------------------------------------------------------------------
--  Scenarios de surveillance : la methode est nommee, les seuils sont declares.
-- -------------------------------------------------------------------------------------
CREATE TABLE monitoring_scenario (
    id                UUID PRIMARY KEY,
    legal_entity_id   UUID NOT NULL REFERENCES legal_entity(id),
    code              TEXT NOT NULL,
    label             TEXT NOT NULL,

    -- La methode : ce que le code sait compter. Ajouter une methode est une livraison ;
    -- changer un seuil ne l'est pas.
    --   CASH_THRESHOLD        especes cumulees au-dela d'un montant sur une fenetre
    --   STRUCTURING           operations sous le seuil, repetees, dont la somme le franchit
    --   ATYPICAL_ACTIVITY     flux hors de proportion avec le profil declare au dossier
    --   DORMANT_REACTIVATION  un compte oublie qui se remet a bouger
    method            TEXT NOT NULL CHECK (method IN ('CASH_THRESHOLD','STRUCTURING',
                                                      'ATYPICAL_ACTIVITY',
                                                      'DORMANT_REACTIVATION')),
    -- Les parametres de la methode. Tous ne servent pas a toutes : la contrainte ci-dessous
    -- exige de chacune ce dont elle a besoin, plutot que de tout rendre obligatoire.
    threshold_amount  NUMERIC(23,5) CHECK (threshold_amount IS NULL OR threshold_amount > 0),
    window_days       INTEGER CHECK (window_days IS NULL OR window_days > 0),
    minimum_count     INTEGER CHECK (minimum_count IS NULL OR minimum_count > 1),
    -- Multiple du flux mensuel declare au-dela duquel l'activite est dite atypique.
    ratio             NUMERIC(9,4) CHECK (ratio IS NULL OR ratio > 0),

    -- Population visee : tous, ou seulement un niveau de risque. Surveiller la banque
    -- entiere avec le seuil des dossiers renforces noierait la conformite sous le bruit.
    risk_rating       TEXT CHECK (risk_rating IN ('LOW','MEDIUM','HIGH')),

    valid_from        DATE NOT NULL,
    valid_to          DATE,
    created_by        UUID NOT NULL,
    approved_by       UUID NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_scenario_code UNIQUE (legal_entity_id, code, valid_from),
    CONSTRAINT ck_scenario_approval CHECK (approved_by <> created_by),
    CONSTRAINT ck_scenario_dates CHECK (valid_to IS NULL OR valid_to >= valid_from),
    -- Ce que chaque methode exige pour vouloir dire quelque chose.
    CONSTRAINT ck_scenario_parameters CHECK (
        (method = 'CASH_THRESHOLD'
            AND threshold_amount IS NOT NULL AND window_days IS NOT NULL)
     OR (method = 'STRUCTURING'
            AND threshold_amount IS NOT NULL AND window_days IS NOT NULL
            AND minimum_count IS NOT NULL)
     OR (method = 'ATYPICAL_ACTIVITY'
            AND window_days IS NOT NULL AND ratio IS NOT NULL)
     OR (method = 'DORMANT_REACTIVATION'
            AND threshold_amount IS NOT NULL))
);

CREATE INDEX idx_scenario_entity ON monitoring_scenario(legal_entity_id, valid_from);

-- -------------------------------------------------------------------------------------
--  Profil d'activite declare : ce que le client a annonce, et contre quoi l'atypie se
--  mesure. Sans lui, « incoherent avec le profil declare » n'a pas de sens : il faudrait
--  comparer les flux d'un client a ceux d'un autre, ce qui reviendrait a suspecter les gros
--  comptes d'etre gros.
--
--  Il vit ici et non au dossier client parce qu'il est collecte pour la surveillance : le
--  ranger avec elle evite qu'on le prenne un jour pour une donnee commerciale.
-- -------------------------------------------------------------------------------------
CREATE TABLE party_activity_profile (
    party_id                UUID PRIMARY KEY REFERENCES party(id),
    legal_entity_id         UUID NOT NULL REFERENCES legal_entity(id),
    expected_monthly_credit NUMERIC(23,5) NOT NULL CHECK (expected_monthly_credit >= 0),
    expected_monthly_debit  NUMERIC(23,5) NOT NULL CHECK (expected_monthly_debit >= 0),
    currency                CHAR(3) NOT NULL REFERENCES currency(code),
    declared_on             DATE NOT NULL,
    declared_by             UUID NOT NULL,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_activity_profile_entity ON party_activity_profile(legal_entity_id);

-- -------------------------------------------------------------------------------------
--  Alertes : la file de travail de la conformite.
--
--  Une alerte porte son constat ET ses pieces : sans les operations qui l'ont declenchee,
--  elle n'est pas instruisable, et l'instruction se ferait sur une intuition.
-- -------------------------------------------------------------------------------------
CREATE TABLE aml_alert (
    id                UUID PRIMARY KEY,
    legal_entity_id   UUID NOT NULL REFERENCES legal_entity(id),
    party_id          UUID NOT NULL REFERENCES party(id),
    -- Le scenario qui l'a levee, ou NULL pour une correspondance de filtrage : celle-ci ne
    -- vient pas d'un compteur, elle vient d'une liste.
    scenario_code     TEXT,
    origin            TEXT NOT NULL CHECK (origin IN ('SCREENING','MONITORING')),

    raised_on         DATE NOT NULL,
    detail            TEXT NOT NULL,
    amount            NUMERIC(23,5),
    currency          CHAR(3) REFERENCES currency(code),

    status            TEXT NOT NULL DEFAULT 'OPEN'
                      CHECK (status IN ('OPEN','UNDER_REVIEW','CLOSED','REPORTED')),
    assigned_to       UUID,
    -- La cloture se motive : « classee sans suite » sans motif ne se controle pas.
    closed_on         DATE,
    closure_reason    TEXT,
    closed_by         UUID,
    report_id         UUID,
    batch_run_id      UUID,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_alert_closed CHECK (
        status NOT IN ('CLOSED','REPORTED')
        OR (closed_on IS NOT NULL AND closure_reason IS NOT NULL AND closed_by IS NOT NULL)),
    CONSTRAINT ck_alert_scenario CHECK (
        (origin = 'MONITORING') = (scenario_code IS NOT NULL)),
    CONSTRAINT ck_alert_amount CHECK ((amount IS NULL) = (currency IS NULL))
);

CREATE INDEX idx_alert_open ON aml_alert(legal_entity_id, raised_on)
    WHERE status IN ('OPEN','UNDER_REVIEW');
CREATE INDEX idx_alert_party ON aml_alert(party_id, raised_on);
CREATE INDEX idx_alert_run ON aml_alert(batch_run_id) WHERE batch_run_id IS NOT NULL;

-- Les operations qui ont declenche l'alerte : la piece du dossier.
CREATE TABLE aml_alert_item (
    alert_id     UUID NOT NULL REFERENCES aml_alert(id) ON DELETE CASCADE,
    entry_id     UUID NOT NULL,
    booking_date DATE NOT NULL,
    account_id   UUID NOT NULL REFERENCES account(id),
    direction    TEXT NOT NULL CHECK (direction IN ('DEBIT','CREDIT')),
    amount       NUMERIC(23,5) NOT NULL,
    currency     CHAR(3) NOT NULL REFERENCES currency(code),
    PRIMARY KEY (alert_id, entry_id, account_id, direction)
);

-- -------------------------------------------------------------------------------------
--  Declaration de soupcon : le dossier transmis a la cellule de renseignement financier.
--
--  Elle se decide a deux et cite les alertes qu'elle couvre : une declaration qui ne
--  renverrait a rien serait indefendable devant l'inspection, et une alerte declaree deux
--  fois ferait deux dossiers pour un seul fait.
-- -------------------------------------------------------------------------------------
CREATE TABLE suspicious_activity_report (
    id                 UUID PRIMARY KEY,
    legal_entity_id    UUID NOT NULL REFERENCES legal_entity(id),
    party_id           UUID NOT NULL REFERENCES party(id),
    reference          TEXT NOT NULL,
    drafted_on         DATE NOT NULL,
    narrative          TEXT NOT NULL,
    transmitted_on     DATE,
    transmission_reference TEXT,
    created_by         UUID NOT NULL,
    approved_by        UUID NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_report_reference UNIQUE (legal_entity_id, reference),
    CONSTRAINT ck_report_approval CHECK (approved_by <> created_by),
    CONSTRAINT ck_report_transmitted CHECK (
        (transmitted_on IS NULL) = (transmission_reference IS NULL))
);

ALTER TABLE aml_alert ADD CONSTRAINT fk_alert_report
    FOREIGN KEY (report_id) REFERENCES suspicious_activity_report(id);

CREATE INDEX idx_report_party ON suspicious_activity_report(party_id, drafted_on);

ALTER TABLE party_activity_profile ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON party_activity_profile
    USING (legal_entity_id = ledger_current_entity())
    WITH CHECK (legal_entity_id = ledger_current_entity());

ALTER TABLE monitoring_scenario ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON monitoring_scenario
    USING (legal_entity_id = ledger_current_entity())
    WITH CHECK (legal_entity_id = ledger_current_entity());

ALTER TABLE aml_alert ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON aml_alert
    USING (legal_entity_id = ledger_current_entity())
    WITH CHECK (legal_entity_id = ledger_current_entity());

ALTER TABLE aml_alert_item ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON aml_alert_item
    USING (EXISTS (SELECT 1 FROM aml_alert a WHERE a.id = aml_alert_item.alert_id))
    WITH CHECK (EXISTS (SELECT 1 FROM aml_alert a WHERE a.id = aml_alert_item.alert_id));

ALTER TABLE suspicious_activity_report ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON suspicious_activity_report
    USING (legal_entity_id = ledger_current_entity())
    WITH CHECK (legal_entity_id = ledger_current_entity());
