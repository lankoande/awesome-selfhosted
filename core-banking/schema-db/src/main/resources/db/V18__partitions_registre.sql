--liquibase formatted sql
--changeset socle:18 splitStatements:false
--comment partitions registre
-- =====================================================================================
--  Registre des tables partitionnees par mois
--
--  Le journal n'est plus la seule table qui grossit d'une ligne par compte et par jour :
--  le cliche quotidien des soldes et les interets courus suivent la meme loi — deux millions
--  de comptes font sept cents millions de lignes par an et par table. Ces tables sont donc
--  partitionnees par mois, comme le journal, et leurs partitions sont garanties par le meme
--  mecanisme : chaque module declare ici ses tables, et la bascule de journee cree les
--  partitions de toutes en une fois. Une table partitionnee qui ne serait pas declaree
--  n'aurait ses partitions creees par personne, et sa premiere ecriture du mois suivant
--  arreterait l'arrete.
-- =====================================================================================
CREATE TABLE ledger_partitioned_table (
    table_name    TEXT PRIMARY KEY,
    date_column   TEXT NOT NULL,
    registered_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO ledger_partitioned_table(table_name, date_column) VALUES
    ('journal_entry', 'booking_date'),
    ('journal_line',  'booking_date');

-- -------------------------------------------------------------------------------------
--  Cliche quotidien des soldes : partitionne par date comptable.
--
--  La cle primaire porte deja la date, la conversion ne change donc rien aux lectures.
--  Les lignes existantes sont reprises telles quelles, dans les partitions de leurs mois.
-- -------------------------------------------------------------------------------------
ALTER TABLE account_balance_daily RENAME TO account_balance_daily_old;

CREATE TABLE account_balance_daily (
    account_id         UUID NOT NULL REFERENCES account(id),
    business_date      DATE NOT NULL,
    closing_balance    NUMERIC(23,5) NOT NULL,
    value_date_balance NUMERIC(23,5) NOT NULL,
    debit_turnover     NUMERIC(23,5) NOT NULL DEFAULT 0,
    credit_turnover    NUMERIC(23,5) NOT NULL DEFAULT 0,
    PRIMARY KEY (account_id, business_date)
) PARTITION BY RANGE (business_date);

INSERT INTO ledger_partitioned_table(table_name, date_column)
VALUES ('account_balance_daily', 'business_date');

-- -------------------------------------------------------------------------------------
--  Creation des partitions, pour toutes les tables declarees.
--
--  Idempotente et serialisee, comme avant : un seul createur a la fois, toutes entites
--  confondues. Le journal garde sa particularite — la contrainte d'equilibre differee est
--  attachee a chaque partition de lignes, PostgreSQL n'acceptant pas de declencheur de
--  contrainte differe sur une table partitionnee.
-- -------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION ledger_ensure_partitions(p_from DATE, p_to DATE)
RETURNS INT AS $$
DECLARE
    v_month   DATE := date_trunc('month', p_from)::date;
    v_suffix  TEXT;
    v_created INT := 0;
    v_table   RECORD;
    v_name    TEXT;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtext('ledger_ensure_partitions'));

    WHILE v_month <= p_to LOOP
        v_suffix := to_char(v_month, 'YYYY_MM');

        FOR v_table IN SELECT table_name FROM ledger_partitioned_table ORDER BY table_name LOOP
            v_name := v_table.table_name || '_' || v_suffix;
            IF to_regclass(v_name) IS NULL THEN
                EXECUTE format(
                    'CREATE TABLE IF NOT EXISTS %I PARTITION OF %I
                     FOR VALUES FROM (%L) TO (%L)',
                    v_name, v_table.table_name, v_month, v_month + INTERVAL '1 month');
                IF v_table.table_name = 'journal_line' THEN
                    EXECUTE format(
                        'CREATE CONSTRAINT TRIGGER trg_balanced_%s
                           AFTER INSERT ON %I
                           DEFERRABLE INITIALLY DEFERRED
                           FOR EACH ROW EXECUTE FUNCTION check_entry_balanced()',
                        v_suffix, v_name);
                END IF;
                v_created := v_created + 1;
            END IF;
        END LOOP;

        v_month := (v_month + INTERVAL '1 month')::date;
    END LOOP;
    RETURN v_created;
END;
$$ LANGUAGE plpgsql;

-- Vrai si toutes les tables declarees ont leur partition pour le mois d'une date.
CREATE OR REPLACE FUNCTION ledger_partition_exists(p_date DATE) RETURNS BOOLEAN AS $$
DECLARE
    v_suffix TEXT := to_char(date_trunc('month', p_date), 'YYYY_MM');
    v_table  RECORD;
BEGIN
    FOR v_table IN SELECT table_name FROM ledger_partitioned_table LOOP
        IF to_regclass(v_table.table_name || '_' || v_suffix) IS NULL THEN
            RETURN FALSE;
        END IF;
    END LOOP;
    RETURN TRUE;
END;
$$ LANGUAGE plpgsql;

-- Reprise des cliches existants, puis retrait de l'ancienne table.
DO $$
DECLARE
    v_min DATE;
    v_max DATE;
BEGIN
    SELECT MIN(business_date), MAX(business_date) INTO v_min, v_max
      FROM account_balance_daily_old;
    IF v_min IS NOT NULL THEN
        PERFORM ledger_ensure_partitions(v_min, v_max);
        INSERT INTO account_balance_daily
        SELECT * FROM account_balance_daily_old;
    END IF;
END;
$$;

DROP TABLE account_balance_daily_old;

-- -------------------------------------------------------------------------------------
--  Le cliche incremental lit les lignes par date de valeur, toutes partitions confondues :
--  une operation comptabilisee hier a date de valeur demain entre dans le solde en date de
--  valeur de demain sans avoir ete comptabilisee demain. L'index par compte ne sert pas a
--  cette lecture, qui ne part pas d'un compte.
-- -------------------------------------------------------------------------------------
CREATE INDEX idx_line_value_date ON journal_line (value_date);

-- Le controle quotidien des soldes ajoute au cliche les lignes datees apres la journee : elles
-- sont rares, et l'index les trouve sans parcourir la partition du mois.
CREATE INDEX idx_line_booking_date ON journal_line (booking_date);
