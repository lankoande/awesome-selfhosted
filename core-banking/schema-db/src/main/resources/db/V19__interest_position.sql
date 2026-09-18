--liquibase formatted sql
--changeset socle:19 splitStatements:false
--comment interest position
-- =====================================================================================
--  Interets courus : partition mensuelle, position par compte, reglements, retenue
--
--  Trois defauts d'exploitation fermes par ce script :
--    * la table des journees calculees n'etait pas partitionnee — sept cents millions de
--      lignes par an a l'echelle cible, sans retention possible ;
--    * l'etat du calcul d'un compte etait recalcule chaque nuit par une somme sur tout son
--      historique — O(historique) par compte et par arrete ;
--    * les interets courus n'etaient jamais regles : ni capitalises au client, ni preleves
--      en agios, sans retenue a la source. Le compte de courus croissait indefiniment.
-- =====================================================================================

-- -------------------------------------------------------------------------------------
--  1. Journees calculees, partitionnees par mois de la journee de valeur.
--
--  La cle primaire d'une table partitionnee doit porter la cle de partitionnement. Le
--  cumul impute (posted_cumulative) est nouveau : c'est la somme courante des montants
--  imputes, portee par chaque ligne, qui permet de reconstituer la position d'un compte
--  depuis sa derniere journee active sans sommer son historique.
-- -------------------------------------------------------------------------------------
ALTER TABLE interest_accrual RENAME TO interest_accrual_old;

CREATE TABLE interest_accrual (
    id                 UUID NOT NULL,
    account_id         UUID NOT NULL REFERENCES account(id),
    accrual_date       DATE NOT NULL,
    side               TEXT NOT NULL CHECK (side IN ('CREDITOR','DEBTOR')),
    generation         INT  NOT NULL DEFAULT 1,
    basis_balance      NUMERIC(23,5)  NOT NULL,
    effective_rate     NUMERIC(12,6)  NOT NULL,
    year_fraction      NUMERIC(24,18) NOT NULL,
    precise_amount     NUMERIC(23,5) NOT NULL,
    cumulative_precise NUMERIC(23,5) NOT NULL,
    posted_delta       NUMERIC(23,5) NOT NULL DEFAULT 0,
    posted_cumulative  NUMERIC(23,5) NOT NULL DEFAULT 0,
    entry_id           UUID,
    booking_date       DATE,
    batch_run_id       UUID,
    status             TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','REVERSED')),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id, accrual_date)
) PARTITION BY RANGE (accrual_date);

INSERT INTO ledger_partitioned_table(table_name, date_column)
VALUES ('interest_accrual', 'accrual_date');

DO $$
DECLARE
    v_min DATE;
    v_max DATE;
BEGIN
    SELECT MIN(accrual_date), MAX(accrual_date) INTO v_min, v_max FROM interest_accrual_old;
    IF v_min IS NOT NULL THEN
        PERFORM ledger_ensure_partitions(v_min, v_max);
        INSERT INTO interest_accrual(id, account_id, accrual_date, side, generation,
            basis_balance, effective_rate, year_fraction, precise_amount, cumulative_precise,
            posted_delta, posted_cumulative, entry_id, booking_date, batch_run_id, status,
            created_at)
        SELECT id, account_id, accrual_date, side, generation, basis_balance, effective_rate,
               year_fraction, precise_amount, cumulative_precise, posted_delta,
               SUM(CASE WHEN status = 'ACTIVE' THEN posted_delta ELSE 0 END)
                   OVER (PARTITION BY account_id, side ORDER BY accrual_date, generation
                         ROWS UNBOUNDED PRECEDING),
               entry_id, booking_date, batch_run_id, status, created_at
          FROM interest_accrual_old;
    END IF;
END;
$$;

DROP TABLE interest_accrual_old;

CREATE UNIQUE INDEX uq_accrual_active
    ON interest_accrual(account_id, accrual_date, side) WHERE status = 'ACTIVE';
CREATE INDEX idx_accrual_account ON interest_accrual(account_id, side, accrual_date);
CREATE INDEX idx_accrual_run     ON interest_accrual(batch_run_id) WHERE batch_run_id IS NOT NULL;

-- -------------------------------------------------------------------------------------
--  2. Position d'interets : l'etat courant du calcul, par compte et par cote.
--
--  C'est le sous-livre que la reconciliation rapproche du grand livre : ce qui a ete
--  impute en courus, moins ce qui a ete regle, groupe par compte de courus, doit egaler le
--  solde de ce compte. La position est une projection des journees calculees et des
--  reglements ; elle se reconstruit depuis eux, et c'est ce que fait l'annulation d'un
--  traitement.
-- -------------------------------------------------------------------------------------
CREATE TABLE interest_position (
    account_id         UUID NOT NULL REFERENCES account(id),
    side               TEXT NOT NULL CHECK (side IN ('CREDITOR','DEBTOR')),
    accrued_account_id UUID REFERENCES account(id),  -- compte de courus ou l'imputation est faite
    accrued_through    DATE,
    cumulative_precise NUMERIC(23,5) NOT NULL DEFAULT 0,
    posted_total       NUMERIC(23,5) NOT NULL DEFAULT 0,
    settled_total      NUMERIC(23,5) NOT NULL DEFAULT 0,
    settled_through    DATE,
    generation         INT NOT NULL DEFAULT 0,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, side)
);

INSERT INTO interest_position(account_id, side, accrued_through, cumulative_precise,
                              posted_total, generation)
SELECT DISTINCT ON (account_id, side)
       account_id, side, accrual_date, cumulative_precise, posted_cumulative, generation
  FROM interest_accrual
 WHERE status = 'ACTIVE'
 ORDER BY account_id, side, accrual_date DESC;

-- -------------------------------------------------------------------------------------
--  3. Reglements : capitalisation des interets crediteurs, arrete des agios.
--
--  Le brut est ce qui est repris du compte de courus ; la retenue a la source (interets
--  crediteurs) et la taxe (agios) en sont deduites ou ajoutees ; le net est ce que le
--  client recoit ou paie. Une ligne par compte, par cote et par fin de periode.
-- -------------------------------------------------------------------------------------
CREATE TABLE interest_settlement (
    id                       UUID PRIMARY KEY,
    account_id               UUID NOT NULL REFERENCES account(id),
    side                     TEXT NOT NULL CHECK (side IN ('CREDITOR','DEBTOR')),
    period_end               DATE NOT NULL,
    gross_amount             NUMERIC(23,5) NOT NULL,
    withholding_code         TEXT,
    withholding_rate_percent NUMERIC(12,6) NOT NULL DEFAULT 0,
    withholding_amount       NUMERIC(23,5) NOT NULL DEFAULT 0,
    tax_rate_percent         NUMERIC(12,6) NOT NULL DEFAULT 0,
    tax_amount               NUMERIC(23,5) NOT NULL DEFAULT 0,
    net_amount               NUMERIC(23,5) NOT NULL,
    entry_id                 UUID,
    booking_date             DATE NOT NULL,
    value_date               DATE NOT NULL,
    batch_run_id             UUID,
    status                   TEXT NOT NULL DEFAULT 'ACTIVE'
                             CHECK (status IN ('ACTIVE','REVERSED')),
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_settlement_active
    ON interest_settlement(account_id, side, period_end) WHERE status = 'ACTIVE';
CREATE INDEX idx_settlement_run
    ON interest_settlement(batch_run_id) WHERE batch_run_id IS NOT NULL;

-- -------------------------------------------------------------------------------------
--  4. Retenue a la source sur interets crediteurs : par entite, donc par pays, datee.
--
--  Le taux est national et change en loi de finances : il est historise par periode de
--  validite, sans chevauchement. Le produit designe la retenue par son code, ou n'en
--  designe aucune s'il est exonere.
-- -------------------------------------------------------------------------------------
CREATE TABLE interest_withholding (
    id                 UUID PRIMARY KEY,
    legal_entity_id    UUID NOT NULL REFERENCES legal_entity(id),
    code               TEXT NOT NULL,
    rate_percent       NUMERIC(12,6) NOT NULL CHECK (rate_percent >= 0 AND rate_percent <= 100),
    payable_account_id UUID NOT NULL REFERENCES account(id),
    valid_from         DATE NOT NULL,
    valid_to           DATE,
    created_by         UUID NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_withholding_dates CHECK (valid_to IS NULL OR valid_to >= valid_from),
    CONSTRAINT ex_withholding_no_overlap EXCLUDE USING gist (
        legal_entity_id WITH =,
        code WITH =,
        daterange(valid_from, COALESCE(valid_to + 1, 'infinity'::date), '[)') WITH &&)
);
