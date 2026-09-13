-- =====================================================================================
--  Socle core banking — P0 : noyau comptable
--  PostgreSQL 14+
--
--  Invariants portes par le schema lui-meme, et non par la seule application :
--    * le journal est immuable            -> declencheurs BEFORE UPDATE/DELETE
--    * toute ecriture est equilibree      -> contrainte differee par partition
--    * l'idempotence est globale          -> table satellite non partitionnee
--    * une ecriture n'est contre-passee   -> cle primaire sur l'ecriture d'origine
--      qu'une seule fois
-- =====================================================================================

CREATE TABLE currency (
    code          CHAR(3) PRIMARY KEY,
    scale         SMALLINT NOT NULL CHECK (scale BETWEEN 0 AND 5),
    name          TEXT     NOT NULL,
    rounding_mode TEXT     NOT NULL DEFAULT 'HALF_EVEN'
);

CREATE TABLE legal_entity (
    id                    UUID PRIMARY KEY,
    code                  TEXT NOT NULL UNIQUE,
    name                  TEXT NOT NULL,
    country_code          CHAR(2) NOT NULL,
    functional_currency   CHAR(3) NOT NULL REFERENCES currency(code),
    timezone              TEXT NOT NULL DEFAULT 'UTC',
    current_business_date DATE NOT NULL,
    status                TEXT NOT NULL DEFAULT 'ACTIVE',
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Numerotation technique des ecritures.
--
-- Une sequence PostgreSQL est non transactionnelle : elle ne serialise pas les ecritures et
-- laisse des trous en cas de rollback. C'est un choix assume. Un compteur en table donnerait
-- une numerotation sans trou mais poserait un verrou de ligne par entite, tenu jusqu'au
-- commit : le debit du systeme entier tomberait a celui d'une seule ligne.
--
-- La numerotation comptable continue exigee pour le journal officiel est distincte : elle est
-- attribuee au TFJ, dans l'ordre deterministe des knowledge_time, sur des ecritures deja
-- validees. Confondre les deux revient a payer un cout de serialisation permanent pour une
-- exigence qui ne porte que sur l'edition.
CREATE SEQUENCE journal_entry_number_seq AS BIGINT START 1;

CREATE TABLE accounting_period (
    id              UUID PRIMARY KEY,
    legal_entity_id UUID NOT NULL REFERENCES legal_entity(id),
    start_date      DATE NOT NULL,
    end_date        DATE NOT NULL,
    status          TEXT NOT NULL DEFAULT 'OPEN',   -- OPEN|CLOSING|CLOSED|REOPENED
    CONSTRAINT ck_period_dates CHECK (end_date >= start_date),
    CONSTRAINT uq_period UNIQUE (legal_entity_id, start_date)
);

-- -------------------------------------------------------------------------------------
--  Comptes : clients et generaux dans la meme table, donc dans le meme journal.
-- -------------------------------------------------------------------------------------
CREATE TABLE account (
    id                UUID PRIMARY KEY,
    legal_entity_id   UUID NOT NULL REFERENCES legal_entity(id),
    code              TEXT NOT NULL,
    account_kind      TEXT NOT NULL
        CHECK (account_kind IN ('CUSTOMER','GL','INTERNAL','NOSTRO','SUSPENSE','POSITION')),
    normal_balance    TEXT NOT NULL CHECK (normal_balance IN ('DEBIT','CREDIT')),
    currency          CHAR(3) NOT NULL REFERENCES currency(code),
    gl_account_id     UUID REFERENCES account(id),
    contract_id       UUID,
    postable          BOOLEAN  NOT NULL DEFAULT TRUE,
    control_available BOOLEAN  NOT NULL DEFAULT FALSE,
    stripe_count      SMALLINT NOT NULL DEFAULT 1 CHECK (stripe_count BETWEEN 1 AND 256),
    status            TEXT     NOT NULL DEFAULT 'ACTIVE',
    opened_at         DATE     NOT NULL,
    closed_at         DATE,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_account_code UNIQUE (legal_entity_id, code)
);

CREATE INDEX idx_account_entity_kind ON account(legal_entity_id, account_kind, status);
CREATE INDEX idx_account_gl          ON account(gl_account_id);

-- -------------------------------------------------------------------------------------
--  Journal : le coeur. Partitionne par date comptable.
-- -------------------------------------------------------------------------------------
CREATE TABLE journal_entry (
    id                UUID NOT NULL,
    booking_date      DATE NOT NULL,
    legal_entity_id   UUID NOT NULL REFERENCES legal_entity(id),
    entry_number      BIGINT NOT NULL,
    transaction_type  TEXT NOT NULL,
    source            TEXT NOT NULL CHECK (source IN ('ONLINE','BATCH','MIGRATION','CORRECTION')),
    batch_run_id      UUID,
    reversal_of       UUID,
    idempotency_key   TEXT NOT NULL,
    narrative         TEXT,
    metadata          JSONB NOT NULL DEFAULT '{}',
    created_by        UUID NOT NULL,
    knowledge_time    TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (id, booking_date),
    CONSTRAINT uq_entry_number UNIQUE (legal_entity_id, entry_number, booking_date),
    CONSTRAINT ck_batch_run CHECK (source <> 'BATCH' OR batch_run_id IS NOT NULL)
) PARTITION BY RANGE (booking_date);

CREATE TABLE journal_line (
    id                UUID NOT NULL,
    booking_date      DATE NOT NULL,
    entry_id          UUID NOT NULL,
    legal_entity_id   UUID NOT NULL,
    line_number       SMALLINT NOT NULL,
    account_id        UUID NOT NULL REFERENCES account(id),
    direction         TEXT NOT NULL CHECK (direction IN ('DEBIT','CREDIT')),
    amount            NUMERIC(23,5) NOT NULL CHECK (amount > 0),
    currency          CHAR(3) NOT NULL REFERENCES currency(code),
    functional_amount NUMERIC(23,5) NOT NULL,
    fx_rate           NUMERIC(20,10) CHECK (fx_rate IS NULL OR fx_rate > 0),
    value_date        DATE NOT NULL,
    stripe_id         SMALLINT NOT NULL DEFAULT 0,
    label             TEXT,
    knowledge_time    TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (id, booking_date),
    CONSTRAINT uq_line UNIQUE (entry_id, line_number, booking_date),
    CONSTRAINT fk_line_entry FOREIGN KEY (entry_id, booking_date)
        REFERENCES journal_entry (id, booking_date)
) PARTITION BY RANGE (booking_date);

CREATE INDEX idx_line_account_value ON journal_line (account_id, value_date);
CREATE INDEX idx_line_account_book  ON journal_line (account_id, booking_date);
CREATE INDEX idx_line_knowledge     ON journal_line (account_id, knowledge_time);
CREATE INDEX idx_line_entry         ON journal_line (entry_id);

-- Unicite globale de la cle d'idempotence : elle ne peut pas vivre sur la table partitionnee,
-- dont toute contrainte unique doit inclure la cle de partitionnement.
CREATE TABLE posting_idempotency (
    legal_entity_id UUID NOT NULL REFERENCES legal_entity(id),
    idempotency_key TEXT NOT NULL,
    entry_id        UUID NOT NULL,
    booking_date    DATE NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (legal_entity_id, idempotency_key)
);

-- Une ecriture ne peut etre contre-passee qu'une seule fois.
CREATE TABLE journal_reversal (
    reversed_entry_id UUID PRIMARY KEY,
    reversal_entry_id UUID NOT NULL UNIQUE,
    reason            TEXT NOT NULL,
    reversed_by       UUID NOT NULL,
    reversed_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- -------------------------------------------------------------------------------------
--  Soldes : projection du journal, jamais une saisie.
-- -------------------------------------------------------------------------------------
CREATE TABLE account_balance (
    account_id         UUID NOT NULL REFERENCES account(id),
    stripe_id          SMALLINT NOT NULL DEFAULT 0,
    balance            NUMERIC(23,5) NOT NULL DEFAULT 0,
    functional_balance NUMERIC(23,5) NOT NULL DEFAULT 0,
    version            BIGINT NOT NULL DEFAULT 0,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, stripe_id)
);

CREATE VIEW account_balance_agg AS
SELECT account_id,
       SUM(balance)            AS balance,
       SUM(functional_balance) AS functional_balance
  FROM account_balance
 GROUP BY account_id;

CREATE TABLE account_balance_daily (
    account_id         UUID NOT NULL REFERENCES account(id),
    business_date      DATE NOT NULL,
    closing_balance    NUMERIC(23,5) NOT NULL,
    value_date_balance NUMERIC(23,5) NOT NULL,
    debit_turnover     NUMERIC(23,5) NOT NULL DEFAULT 0,
    credit_turnover    NUMERIC(23,5) NOT NULL DEFAULT 0,
    PRIMARY KEY (account_id, business_date)
);

CREATE TABLE account_hold (
    id          UUID PRIMARY KEY,
    account_id  UUID NOT NULL REFERENCES account(id),
    amount      NUMERIC(23,5) NOT NULL CHECK (amount > 0),
    currency    CHAR(3) NOT NULL REFERENCES currency(code),
    hold_type   TEXT NOT NULL,
    reference   TEXT,
    expires_at  TIMESTAMPTZ,
    released_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_hold_active ON account_hold(account_id) WHERE released_at IS NULL;

CREATE TABLE overdraft_limit (
    account_id UUID NOT NULL REFERENCES account(id),
    amount     NUMERIC(23,5) NOT NULL CHECK (amount >= 0),
    valid_from DATE NOT NULL,
    valid_to   DATE,
    PRIMARY KEY (account_id, valid_from)
);

-- =====================================================================================
--  Immuabilite du journal
-- =====================================================================================
CREATE OR REPLACE FUNCTION forbid_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION
      'Le journal comptable est immuable : % interdit sur %. Corriger par contre-passation.',
      TG_OP, TG_TABLE_NAME
      USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_journal_entry_immutable
    BEFORE UPDATE OR DELETE ON journal_entry
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

CREATE TRIGGER trg_journal_line_immutable
    BEFORE UPDATE OR DELETE ON journal_line
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

-- =====================================================================================
--  Equilibre de l'ecriture, verifie a la validation de la transaction
-- =====================================================================================
CREATE OR REPLACE FUNCTION check_entry_balanced() RETURNS trigger AS $$
DECLARE
    v_imbalance RECORD;
    v_functional NUMERIC;
BEGIN
    FOR v_imbalance IN
        SELECT currency,
               SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END) AS diff
          FROM journal_line
         WHERE entry_id = NEW.entry_id AND booking_date = NEW.booking_date
         GROUP BY currency
        HAVING SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END) <> 0
    LOOP
        RAISE EXCEPTION 'Ecriture % desequilibree en % : ecart de %',
            NEW.entry_id, v_imbalance.currency, v_imbalance.diff
            USING ERRCODE = 'integrity_constraint_violation';
    END LOOP;

    SELECT SUM(CASE WHEN direction = 'DEBIT' THEN functional_amount ELSE -functional_amount END)
      INTO v_functional
      FROM journal_line
     WHERE entry_id = NEW.entry_id AND booking_date = NEW.booking_date;

    IF v_functional <> 0 THEN
        RAISE EXCEPTION 'Ecriture % desequilibree en contre-valeur : ecart de %',
            NEW.entry_id, v_functional
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- =====================================================================================
--  Partitions mensuelles
--
--  La contrainte d'equilibre est attachee a chaque partition : PostgreSQL n'accepte pas
--  de declencheur de contrainte differe sur une table partitionnee. Elle est donc posee
--  sur les tables feuilles, ou elle joue exactement le meme role.
-- =====================================================================================
CREATE OR REPLACE FUNCTION ledger_ensure_partitions(p_from DATE, p_to DATE)
RETURNS INT AS $$
DECLARE
    v_month   DATE := date_trunc('month', p_from)::date;
    v_suffix  TEXT;
    v_created INT := 0;
BEGIN
    WHILE v_month <= p_to LOOP
        v_suffix := to_char(v_month, 'YYYY_MM');

        IF to_regclass('journal_entry_' || v_suffix) IS NULL THEN
            EXECUTE format(
                'CREATE TABLE journal_entry_%s PARTITION OF journal_entry
                   FOR VALUES FROM (%L) TO (%L)',
                v_suffix, v_month, v_month + INTERVAL '1 month');
            v_created := v_created + 1;
        END IF;

        IF to_regclass('journal_line_' || v_suffix) IS NULL THEN
            EXECUTE format(
                'CREATE TABLE journal_line_%s PARTITION OF journal_line
                   FOR VALUES FROM (%L) TO (%L)',
                v_suffix, v_month, v_month + INTERVAL '1 month');
            EXECUTE format(
                'CREATE CONSTRAINT TRIGGER trg_balanced_%s
                   AFTER INSERT ON journal_line_%s
                   DEFERRABLE INITIALLY DEFERRED
                   FOR EACH ROW EXECUTE FUNCTION check_entry_balanced()',
                v_suffix, v_suffix);
            v_created := v_created + 1;
        END IF;

        v_month := (v_month + INTERVAL '1 month')::date;
    END LOOP;
    RETURN v_created;
END;
$$ LANGUAGE plpgsql;

-- =====================================================================================
--  Disponible : solde comptable, moins les blocages actifs, plus l'autorisation en vigueur
-- =====================================================================================
CREATE OR REPLACE FUNCTION available_balance(p_account UUID, p_as_of DATE)
RETURNS NUMERIC AS $$
    SELECT COALESCE((SELECT SUM(balance) FROM account_balance WHERE account_id = p_account), 0)
         - COALESCE((SELECT SUM(amount) FROM account_hold
                      WHERE account_id = p_account
                        AND released_at IS NULL
                        AND (expires_at IS NULL OR expires_at > now())), 0)
         + COALESCE((SELECT amount FROM overdraft_limit
                      WHERE account_id = p_account
                        AND valid_from <= p_as_of
                        AND (valid_to IS NULL OR valid_to >= p_as_of)
                      ORDER BY valid_from DESC LIMIT 1), 0);
$$ LANGUAGE sql STABLE;
