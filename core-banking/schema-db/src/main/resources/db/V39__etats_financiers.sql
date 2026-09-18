--liquibase formatted sql
--changeset socle:39 splitStatements:false
--comment etats financiers
-- =====================================================================================
--  États financiers : maquettes de bilan, de compte de résultat et de hors bilan
--
--  Une maquette est un parametrage : des rubriques ordonnees — de detail, de total, ou la
--  rubrique du resultat de l'exercice — et des regles qui affectent chaque compte a une
--  rubrique de detail selon sa nature de compte, le prefixe de son code et le sens de son
--  solde (un compte client debiteur est un credit a la clientele, crediteur un depot). Elle
--  se redige, puis s'active a deux ; une seule maquette active par nature d'etat et par
--  date. Les regles sont la projection du plan interne vers l'etat presente : la table de
--  correspondance du plan comptable reglementaire, generalisee au sens du solde.
-- =====================================================================================
CREATE TABLE statement_layout (
    id              UUID PRIMARY KEY,
    legal_entity_id UUID NOT NULL REFERENCES legal_entity(id),
    kind            TEXT NOT NULL
        CHECK (kind IN ('BALANCE_SHEET','INCOME_STATEMENT','OFF_BALANCE_SHEET')),
    code            TEXT NOT NULL,
    label           TEXT NOT NULL,
    valid_from      DATE NOT NULL,
    valid_to        DATE,
    status          TEXT NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','ACTIVE','WITHDRAWN')),
    created_by      UUID NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    approved_by     UUID,
    approved_at     TIMESTAMPTZ,
    CONSTRAINT ck_layout_validity CHECK (valid_to IS NULL OR valid_to >= valid_from),
    CONSTRAINT ck_layout_approval CHECK (
        status <> 'ACTIVE' OR (approved_by IS NOT NULL AND approved_by <> created_by)),
    -- Une seule maquette active par nature d'etat et par date : deux bilans a la meme date
    -- seraient deux verites.
    CONSTRAINT ex_layout_one_active EXCLUDE USING gist (
        legal_entity_id WITH =,
        kind WITH =,
        daterange(valid_from, COALESCE(valid_to + 1, 'infinity'::date), '[)') WITH &&
    ) WHERE (status = 'ACTIVE')
);

CREATE TABLE statement_line (
    layout_id UUID NOT NULL REFERENCES statement_layout(id) ON DELETE CASCADE,
    ordinal   SMALLINT NOT NULL,
    code      TEXT NOT NULL,
    label     TEXT NOT NULL,
    level     SMALLINT NOT NULL DEFAULT 0 CHECK (level >= 0),
    kind      TEXT NOT NULL CHECK (kind IN ('DETAIL','TOTAL','PROFIT_OR_LOSS')),
    side      TEXT NOT NULL CHECK (side IN ('DEBIT','CREDIT')),
    plus      TEXT[] NOT NULL DEFAULT '{}',
    minus     TEXT[] NOT NULL DEFAULT '{}',
    PRIMARY KEY (layout_id, ordinal),
    CONSTRAINT uq_statement_line_code UNIQUE (layout_id, code)
);

CREATE TABLE statement_rule (
    layout_id    UUID NOT NULL REFERENCES statement_layout(id) ON DELETE CASCADE,
    ordinal      SMALLINT NOT NULL,
    line_code    TEXT NOT NULL,
    account_kind TEXT
        CHECK (account_kind IN ('CUSTOMER','GL','INTERNAL','NOSTRO','SUSPENSE','POSITION')),
    code_prefix  TEXT CHECK (code_prefix IS NULL OR length(code_prefix) > 0),
    balance_side TEXT CHECK (balance_side IN ('DEBIT','CREDIT')),
    PRIMARY KEY (layout_id, ordinal),
    CONSTRAINT fk_rule_line FOREIGN KEY (layout_id, line_code)
        REFERENCES statement_line (layout_id, code) ON DELETE CASCADE,
    CONSTRAINT ck_rule_criterion CHECK (
        account_kind IS NOT NULL OR code_prefix IS NOT NULL OR balance_side IS NOT NULL)
);

ALTER TABLE statement_layout ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON statement_layout
    USING (legal_entity_id = ledger_current_entity())
    WITH CHECK (legal_entity_id = ledger_current_entity());

ALTER TABLE statement_line ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON statement_line
    USING (EXISTS (SELECT 1 FROM statement_layout m WHERE m.id = statement_line.layout_id))
    WITH CHECK (EXISTS (SELECT 1 FROM statement_layout m WHERE m.id = statement_line.layout_id));

ALTER TABLE statement_rule ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON statement_rule
    USING (EXISTS (SELECT 1 FROM statement_layout m WHERE m.id = statement_rule.layout_id))
    WITH CHECK (EXISTS (SELECT 1 FROM statement_layout m WHERE m.id = statement_rule.layout_id));
