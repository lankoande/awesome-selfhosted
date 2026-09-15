-- =====================================================================================
--  Exercice fiscal et nature des comptes
--
--  La cloture annuelle determine le resultat : elle solde les comptes de resultat (charges et
--  produits) sur le compte de resultat de l'exercice. Pour cela le ledger doit savoir ce qu'est
--  un compte de resultat : la nature d'un compte est une donnee du compte, pas une convention
--  sur son code — un plan comptable interne ne numerote pas forcement comme le plan de
--  reference. Les comptes existants sont de bilan par defaut ; les comptes de charges et de
--  produits sont a qualifier avant la premiere cloture, et l'etape de determination du
--  resultat ne solde que ce qui est qualifie.
--
--  L'exercice porte ses bornes, son statut et son compte de resultat ; il s'ouvre a deux, comme
--  tout ce qui fixe ce que la banque presente.
-- =====================================================================================
CREATE EXTENSION IF NOT EXISTS btree_gist;

ALTER TABLE account ADD COLUMN nature TEXT NOT NULL DEFAULT 'BALANCE_SHEET'
    CHECK (nature IN ('BALANCE_SHEET','PROFIT_AND_LOSS','OFF_BALANCE_SHEET'));

CREATE TABLE fiscal_year (
    id                UUID PRIMARY KEY,
    legal_entity_id   UUID NOT NULL REFERENCES legal_entity(id),
    start_date        DATE NOT NULL,
    end_date          DATE NOT NULL,
    result_account_id UUID NOT NULL REFERENCES account(id),
    status            TEXT NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','CLOSED','REOPENED')),
    closed_by_run_id  UUID,
    created_by        UUID NOT NULL,
    approved_by       UUID NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_fiscal_year_dates CHECK (end_date > start_date),
    CONSTRAINT ck_fiscal_year_approval CHECK (approved_by <> created_by),
    CONSTRAINT uq_fiscal_year_start UNIQUE (legal_entity_id, start_date),
    -- Deux exercices ne se chevauchent pas : une journee appartient a un seul exercice.
    CONSTRAINT ex_fiscal_year_no_overlap EXCLUDE USING gist (
        legal_entity_id WITH =,
        daterange(start_date, end_date, '[]') WITH &&
    )
);

ALTER TABLE fiscal_year ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON fiscal_year
    USING (legal_entity_id = ledger_current_entity())
    WITH CHECK (legal_entity_id = ledger_current_entity());
