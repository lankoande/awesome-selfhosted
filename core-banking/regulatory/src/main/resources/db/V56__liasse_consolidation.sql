-- =====================================================================================
--  Liasse reglementaire et consolidation multi-entites
--
--  Une liasse n'est pas une pile d'etats : c'est un jeu d'etats qui doivent se tenir
--  ensemble. Le resultat que porte le compte de resultat est celui qu'annonce le bilan ;
--  si les deux different, ce n'est pas une presentation a corriger, c'est une comptabilite
--  a reprendre. Declarer la liasse comme un tout est ce qui permet de le verifier avant
--  de transmettre, plutot que de l'apprendre du superviseur.
--
--  La consolidation, elle, bute sur une contrainte du socle qui est une protection : le
--  cloisonnement par entite. Aucune requete ne lit deux entites a la fois. L'etat
--  consolide se produit donc entite par entite, chacune dans sa portee, et l'agregation
--  se fait en memoire — ce qui est aussi la bonne facon de le faire, puisque chaque
--  entite tient ses comptes dans sa propre devise.
-- =====================================================================================

CREATE TABLE statement_pack (
    id                UUID PRIMARY KEY,
    legal_entity_id   UUID NOT NULL REFERENCES legal_entity(id),
    code              TEXT NOT NULL,
    label             TEXT NOT NULL,
    valid_from        DATE NOT NULL,
    valid_to          DATE,
    created_by        UUID NOT NULL,
    approved_by       UUID NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_pack_code UNIQUE (legal_entity_id, code, valid_from),
    CONSTRAINT ck_pack_validity CHECK (valid_to IS NULL OR valid_to >= valid_from),
    CONSTRAINT ck_pack_approval CHECK (approved_by <> created_by)
);

-- Les etats qui composent la liasse. La maquette n'est pas citee par identifiant mais par
-- nature : la liasse dit « le bilan », et c'est la maquette active a la date de production
-- qui repond — sans quoi activer une nouvelle maquette obligerait a redeclarer la liasse.
CREATE TABLE statement_pack_item (
    pack_id           UUID NOT NULL REFERENCES statement_pack(id) ON DELETE CASCADE,
    ordinal           INTEGER NOT NULL CHECK (ordinal > 0),
    kind              TEXT NOT NULL
                      CHECK (kind IN ('BALANCE_SHEET','INCOME_STATEMENT','OFF_BALANCE_SHEET')),
    PRIMARY KEY (pack_id, ordinal),
    CONSTRAINT uq_pack_item_kind UNIQUE (pack_id, kind)
);

-- -------------------------------------------------------------------------------------
--  Perimetre de consolidation
-- -------------------------------------------------------------------------------------

CREATE TABLE consolidation_scope (
    id                UUID PRIMARY KEY,
    -- L'entite consolidante : celle qui publie l'etat, et sous la portee de laquelle il
    -- est range.
    legal_entity_id   UUID NOT NULL REFERENCES legal_entity(id),
    code              TEXT NOT NULL,
    label             TEXT NOT NULL,
    -- La devise de presentation du groupe. Chaque entite tient ses comptes dans la sienne ;
    -- l'etat consolide en retient une seule, et les autres s'y convertissent.
    presentation_currency CHAR(3) NOT NULL REFERENCES currency(code),
    valid_from        DATE NOT NULL,
    valid_to          DATE,
    created_by        UUID NOT NULL,
    approved_by       UUID NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_scope_code UNIQUE (legal_entity_id, code, valid_from),
    CONSTRAINT ck_scope_validity CHECK (valid_to IS NULL OR valid_to >= valid_from),
    CONSTRAINT ck_scope_approval CHECK (approved_by <> created_by)
);

CREATE TABLE consolidation_member (
    scope_id          UUID NOT NULL REFERENCES consolidation_scope(id) ON DELETE CASCADE,
    member_entity_id  UUID NOT NULL REFERENCES legal_entity(id),

    -- Integration globale : tout est repris. Proportionnelle : la quote-part. Mise en
    -- equivalence : rien n'est agrege, seule la quote-part de situation nette figure —
    -- le socle la nomme et ne l'agrege pas, plutot que de faire semblant.
    method            TEXT NOT NULL CHECK (method IN ('FULL','PROPORTIONAL','EQUITY')),
    -- Pourcentage d'interet, de 0 exclu a 100 inclus.
    interest_percent  NUMERIC(9,6) NOT NULL
                      CHECK (interest_percent > 0 AND interest_percent <= 100),

    PRIMARY KEY (scope_id, member_entity_id),
    -- L'integration globale reprend tout : une quote-part qui ne serait pas de 100 %
    -- laisserait des interets minoritaires que le socle ne sait pas encore presenter.
    CONSTRAINT ck_member_full CHECK (method <> 'FULL' OR interest_percent = 100)
);

-- Les comptes qui se font face d'une entite a l'autre : la creance de l'une est la dette
-- de l'autre. Les agreger sans les eliminer gonflerait le bilan du groupe de sommes qu'il
-- se doit a lui-meme — c'est la premiere chose que regarde un commissaire aux comptes.
CREATE TABLE consolidation_elimination (
    id                UUID PRIMARY KEY,
    scope_id          UUID NOT NULL REFERENCES consolidation_scope(id) ON DELETE CASCADE,
    label             TEXT NOT NULL,
    left_entity_id    UUID NOT NULL REFERENCES legal_entity(id),
    left_account_id   UUID NOT NULL REFERENCES account(id),
    right_entity_id   UUID NOT NULL REFERENCES legal_entity(id),
    right_account_id  UUID NOT NULL REFERENCES account(id),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_elimination_sides CHECK (left_entity_id <> right_entity_id),
    CONSTRAINT uq_elimination_left UNIQUE (scope_id, left_account_id),
    CONSTRAINT uq_elimination_right UNIQUE (scope_id, right_account_id)
);

-- -------------------------------------------------------------------------------------
--  Cloisonnement par entite
-- -------------------------------------------------------------------------------------

ALTER TABLE statement_pack ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON statement_pack
    USING (legal_entity_id = current_setting('app.entity_id', true)::uuid)
    WITH CHECK (legal_entity_id = current_setting('app.entity_id', true)::uuid);

ALTER TABLE statement_pack_item ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON statement_pack_item
    USING (EXISTS (SELECT 1 FROM statement_pack p WHERE p.id = pack_id
                     AND p.legal_entity_id = current_setting('app.entity_id', true)::uuid))
    WITH CHECK (EXISTS (SELECT 1 FROM statement_pack p WHERE p.id = pack_id
                          AND p.legal_entity_id = current_setting('app.entity_id', true)::uuid));

-- Le perimetre est range sous l'entite consolidante : c'est elle qui publie, et c'est sa
-- portee qui gouverne. Les entites membres restent cloisonnees pour tout le reste.
ALTER TABLE consolidation_scope ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON consolidation_scope
    USING (legal_entity_id = current_setting('app.entity_id', true)::uuid)
    WITH CHECK (legal_entity_id = current_setting('app.entity_id', true)::uuid);

ALTER TABLE consolidation_member ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON consolidation_member
    USING (EXISTS (SELECT 1 FROM consolidation_scope s WHERE s.id = scope_id
                     AND s.legal_entity_id = current_setting('app.entity_id', true)::uuid))
    WITH CHECK (EXISTS (SELECT 1 FROM consolidation_scope s WHERE s.id = scope_id
                          AND s.legal_entity_id = current_setting('app.entity_id', true)::uuid));

ALTER TABLE consolidation_elimination ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON consolidation_elimination
    USING (EXISTS (SELECT 1 FROM consolidation_scope s WHERE s.id = scope_id
                     AND s.legal_entity_id = current_setting('app.entity_id', true)::uuid))
    WITH CHECK (EXISTS (SELECT 1 FROM consolidation_scope s WHERE s.id = scope_id
                          AND s.legal_entity_id = current_setting('app.entity_id', true)::uuid));

-- -------------------------------------------------------------------------------------
--  Ce que la declaration vise, et ce que l'etat a constate
-- -------------------------------------------------------------------------------------

-- Une liasse et un perimetre portent un code : la declaration doit dire lequel elle
-- produit. Les autres methodes n'ont rien a viser — elles portent sur toute l'entite.
ALTER TABLE regulatory_declaration ADD COLUMN subject_code TEXT;
ALTER TABLE regulatory_declaration ADD CONSTRAINT ck_declaration_subject CHECK (
    (method IN ('STATEMENT_PACK','CONSOLIDATED_STATEMENTS')) = (subject_code IS NOT NULL));

ALTER TABLE report_filing ADD COLUMN subject_code TEXT;

-- Les anomalies constatees a la production, recopiees avec l'etat. Un etat qui ne se tient
-- pas peut se produire — c'est ainsi qu'on voit ce qui ne va pas — mais il ne se transmet
-- pas : on ne declare pas au superviseur des comptes dont on sait qu'ils sont faux.
ALTER TABLE report_filing ADD COLUMN anomalies TEXT[] NOT NULL DEFAULT '{}';

-- Deux methodes de plus au catalogue des declarations.
ALTER TABLE regulatory_declaration DROP CONSTRAINT regulatory_declaration_method_check;
ALTER TABLE regulatory_declaration ADD CONSTRAINT regulatory_declaration_method_check
    CHECK (method IN ('ACCOUNTING_SITUATION','CREDIT_REGISTRY','PAYMENT_INCIDENTS',
                      'CREDIT_BUREAU','TAX_COLLECTION','STATEMENT_PACK',
                      'CONSOLIDATED_STATEMENTS'));
