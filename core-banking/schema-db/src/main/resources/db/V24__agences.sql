--liquibase formatted sql
--changeset socle:24 splitStatements:false
--comment agences
-- =====================================================================================
--  Agences : comptabilite par agence, comptes de liaison, equilibre par agence
--
--  Une banque est une entite juridique et N agences. Chaque agence tient ses comptes :
--  ses caisses, ses clients, ses produits et ses charges, et sa position vis-a-vis du
--  siege. D'ou le treizieme invariant : une ecriture est equilibree par devise ET par
--  agence. Les comptes clients et internes ont une agence ; les comptes generaux n'en
--  ont pas : leur solde se tient par agence, en dimension de chaque ligne. Quand une
--  ecriture met en jeu deux agences, le service d'imputation la complete par des lignes
--  de liaison, via le siege ; elles ne sont jamais saisies.
-- =====================================================================================
CREATE TABLE branch (
    id              UUID PRIMARY KEY,
    legal_entity_id UUID NOT NULL REFERENCES legal_entity(id),
    code            TEXT NOT NULL,
    name            TEXT NOT NULL,
    kind            TEXT NOT NULL CHECK (kind IN ('HEAD_OFFICE','REGION','BRANCH')),
    parent_id       UUID REFERENCES branch(id),
    status          TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','CLOSED')),
    opened_on       DATE NOT NULL,
    closed_on       DATE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_branch_code UNIQUE (legal_entity_id, code),
    CONSTRAINT ck_branch_parent CHECK ((kind = 'HEAD_OFFICE') = (parent_id IS NULL))
);

-- Un seul siege par entite : c'est lui qui porte le miroir des liaisons.
CREATE UNIQUE INDEX uq_head_office ON branch(legal_entity_id) WHERE kind = 'HEAD_OFFICE';

-- Compte de liaison d'une agence, par devise. Un compte de liaison n'appartient qu'a une
-- agence : c'est ce qui rend son rapprochement lisible.
CREATE TABLE branch_liaison (
    branch_id  UUID NOT NULL REFERENCES branch(id),
    currency   CHAR(3) NOT NULL REFERENCES currency(code),
    account_id UUID NOT NULL REFERENCES account(id),
    PRIMARY KEY (branch_id, currency),
    CONSTRAINT uq_liaison_account UNIQUE (account_id)
);

-- Schema de liaison de l'entite. Seul « via le siege » est implemente ; les autres
-- (bilateral, via la region) s'ajouteront par migration, jamais par un parametre muet.
ALTER TABLE legal_entity ADD COLUMN interbranch_scheme TEXT NOT NULL DEFAULT 'VIA_HEAD_OFFICE'
    CHECK (interbranch_scheme IN ('VIA_HEAD_OFFICE'));

-- Un siege par entite existante : une base mono-agence est une base dont toutes les
-- agences sont le siege, et rien ne change pour elle.
INSERT INTO branch(id, legal_entity_id, code, name, kind, status, opened_on)
SELECT gen_random_uuid(), e.id, 'SIEGE', 'Siege ' || e.name, 'HEAD_OFFICE', 'ACTIVE',
       e.current_business_date
  FROM legal_entity e;

-- -------------------------------------------------------------------------------------
--  Les comptes : une agence gestionnaire pour les comptes clients et internes.
-- -------------------------------------------------------------------------------------
ALTER TABLE account ADD COLUMN branch_id UUID REFERENCES branch(id);

UPDATE account a SET branch_id = h.id
  FROM branch h
 WHERE h.legal_entity_id = a.legal_entity_id AND h.kind = 'HEAD_OFFICE'
   AND a.account_kind IN ('CUSTOMER','INTERNAL');

ALTER TABLE account ADD CONSTRAINT ck_account_branch CHECK (
       (account_kind IN ('CUSTOMER','INTERNAL') AND branch_id IS NOT NULL)
    OR (account_kind = 'SUSPENSE')
    OR (account_kind IN ('GL','NOSTRO','POSITION') AND branch_id IS NULL));

CREATE INDEX idx_account_branch ON account(branch_id) WHERE branch_id IS NOT NULL;

-- -------------------------------------------------------------------------------------
--  Le journal : l'agence de l'operation sur l'ecriture, l'agence comptable sur la ligne.
-- -------------------------------------------------------------------------------------
ALTER TABLE journal_entry ADD COLUMN branch_id UUID;      -- nulle avant le multi-agences
ALTER TABLE journal_line  ADD COLUMN branch_id UUID;
ALTER TABLE journal_line  ADD COLUMN kind TEXT NOT NULL DEFAULT 'BUSINESS'
    CHECK (kind IN ('BUSINESS','LIAISON'));

-- Reprise des lignes existantes : l'agence de leur compte, a defaut le siege. Le journal
-- est immuable ; la reprise d'une dimension nouvelle est le seul cas ou l'on ecrit dans
-- une ligne existante, et elle se fait triggers utilisateur suspendus, dans la transaction
-- de la migration, sur le parent et sur chaque partition.
ALTER TABLE journal_line DISABLE TRIGGER USER;
DO $$
DECLARE p regclass;
BEGIN
    FOR p IN SELECT inhrelid::regclass FROM pg_inherits WHERE inhparent = 'journal_line'::regclass
    LOOP
        EXECUTE format('ALTER TABLE %s DISABLE TRIGGER USER', p);
    END LOOP;
END $$;

UPDATE journal_line l SET branch_id = COALESCE(a.branch_id, h.id)
  FROM account a
  JOIN branch h ON h.legal_entity_id = a.legal_entity_id AND h.kind = 'HEAD_OFFICE'
 WHERE a.id = l.account_id;

DO $$
DECLARE p regclass;
BEGIN
    FOR p IN SELECT inhrelid::regclass FROM pg_inherits WHERE inhparent = 'journal_line'::regclass
    LOOP
        EXECUTE format('ALTER TABLE %s ENABLE TRIGGER USER', p);
    END LOOP;
END $$;
ALTER TABLE journal_line ENABLE TRIGGER USER;

ALTER TABLE journal_line ALTER COLUMN branch_id SET NOT NULL;
CREATE INDEX idx_line_branch ON journal_line (branch_id, booking_date);

-- -------------------------------------------------------------------------------------
--  Equilibre par devise, par agence, et en contre-valeur par agence : verifie a la
--  validation de la transaction, quelle que soit la voie d'entree.
-- -------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION check_entry_balanced() RETURNS trigger AS $$
DECLARE
    v_imbalance RECORD;
BEGIN
    FOR v_imbalance IN
        SELECT currency, branch_id, GROUPING(branch_id) AS whole,
               SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END) AS diff
          FROM journal_line
         WHERE entry_id = NEW.entry_id AND booking_date = NEW.booking_date
         GROUP BY GROUPING SETS ((currency), (currency, branch_id))
        HAVING SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END) <> 0
    LOOP
        IF v_imbalance.whole = 1 THEN
            RAISE EXCEPTION 'Ecriture % desequilibree en % : ecart de %',
                NEW.entry_id, v_imbalance.currency, v_imbalance.diff
                USING ERRCODE = 'integrity_constraint_violation';
        ELSE
            RAISE EXCEPTION 'Ecriture % desequilibree en % pour l''agence % : ecart de %',
                NEW.entry_id, v_imbalance.currency, v_imbalance.branch_id, v_imbalance.diff
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    END LOOP;

    FOR v_imbalance IN
        SELECT branch_id, GROUPING(branch_id) AS whole,
               SUM(CASE WHEN direction = 'DEBIT' THEN functional_amount
                        ELSE -functional_amount END) AS diff
          FROM journal_line
         WHERE entry_id = NEW.entry_id AND booking_date = NEW.booking_date
         GROUP BY GROUPING SETS ((), (branch_id))
        HAVING SUM(CASE WHEN direction = 'DEBIT' THEN functional_amount
                        ELSE -functional_amount END) <> 0
    LOOP
        IF v_imbalance.whole = 1 THEN
            RAISE EXCEPTION 'Ecriture % desequilibree en contre-valeur : ecart de %',
                NEW.entry_id, v_imbalance.diff
                USING ERRCODE = 'integrity_constraint_violation';
        ELSE
            RAISE EXCEPTION 'Ecriture % desequilibree en contre-valeur pour l''agence % : ecart de %',
                NEW.entry_id, v_imbalance.branch_id, v_imbalance.diff
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    END LOOP;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- -------------------------------------------------------------------------------------
--  Cliche quotidien des soldes par agence : la balance agence a une date. Partitionne
--  et garanti par la bascule comme le cliche par compte.
-- -------------------------------------------------------------------------------------
CREATE TABLE branch_balance_daily (
    account_id      UUID NOT NULL REFERENCES account(id),
    branch_id       UUID NOT NULL REFERENCES branch(id),
    business_date   DATE NOT NULL,
    closing_balance NUMERIC(23,5) NOT NULL,
    PRIMARY KEY (account_id, branch_id, business_date)
) PARTITION BY RANGE (business_date);

INSERT INTO ledger_partitioned_table(table_name, date_column)
VALUES ('branch_balance_daily', 'business_date');
