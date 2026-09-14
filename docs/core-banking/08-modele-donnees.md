# 08 — Modèle de données

DDL PostgreSQL 16 des tables structurantes. Le reste du modèle (clients, crédits, paiements)
suit les mêmes conventions et n'est pas détaillé ici.

Conventions : identifiants `UUID` (v7, ordonnés dans le temps), montants `NUMERIC(23,5)`,
horodatages `TIMESTAMPTZ` en UTC, dates métier `DATE`.

---

## 1. Référentiel

```sql
CREATE TABLE legal_entity (
    id                    UUID PRIMARY KEY,
    code                  TEXT NOT NULL UNIQUE,
    name                  TEXT NOT NULL,
    country_code          CHAR(2) NOT NULL,
    functional_currency   CHAR(3) NOT NULL REFERENCES currency(code),
    chart_of_accounts_id  UUID NOT NULL REFERENCES chart_of_accounts(id),
    business_calendar_id  UUID NOT NULL REFERENCES business_calendar(id),
    regulatory_profile_id UUID NOT NULL REFERENCES regulatory_profile(id),
    timezone              TEXT NOT NULL,
    fiscal_year_start_month SMALLINT NOT NULL DEFAULT 1
        CHECK (fiscal_year_start_month BETWEEN 1 AND 12),
    current_business_date DATE NOT NULL,
    status                TEXT NOT NULL DEFAULT 'ACTIVE',
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE currency (
    code          CHAR(3) PRIMARY KEY,             -- ISO 4217
    numeric_code  SMALLINT NOT NULL,
    scale         SMALLINT NOT NULL CHECK (scale BETWEEN 0 AND 5),
    name          TEXT NOT NULL,
    rounding_mode TEXT NOT NULL DEFAULT 'HALF_EVEN'
);

CREATE TABLE exchange_rate (
    id          UUID PRIMARY KEY,
    from_ccy    CHAR(3) NOT NULL REFERENCES currency(code),
    to_ccy      CHAR(3) NOT NULL REFERENCES currency(code),
    rate_type   TEXT    NOT NULL,                  -- SPOT|OFFICIAL|BUY|SELL|AVERAGE
    rate        NUMERIC(20,10) NOT NULL CHECK (rate > 0),
    valid_from  TIMESTAMPTZ NOT NULL,
    source      TEXT NOT NULL,
    CONSTRAINT uq_rate UNIQUE (from_ccy, to_ccy, rate_type, valid_from)
);

CREATE TABLE accounting_period (
    id               UUID PRIMARY KEY,
    legal_entity_id  UUID NOT NULL REFERENCES legal_entity(id),
    fiscal_year      SMALLINT NOT NULL,
    period_number    SMALLINT NOT NULL,
    start_date       DATE NOT NULL,
    end_date         DATE NOT NULL,
    status           TEXT NOT NULL DEFAULT 'OPEN',  -- OPEN|CLOSING|CLOSED|REOPENED
    closed_at        TIMESTAMPTZ,
    closed_by        UUID,
    CONSTRAINT uq_period UNIQUE (legal_entity_id, fiscal_year, period_number),
    CONSTRAINT ck_period_dates CHECK (end_date >= start_date)
);
```

---

## 2. Comptes

```sql
CREATE TABLE account (
    id                 UUID PRIMARY KEY,
    legal_entity_id    UUID NOT NULL REFERENCES legal_entity(id),
    code               TEXT NOT NULL,
    account_kind       TEXT NOT NULL,          -- CUSTOMER|GL|INTERNAL|NOSTRO|SUSPENSE|POSITION
    normal_balance     TEXT NOT NULL CHECK (normal_balance IN ('DEBIT','CREDIT')),
    currency           CHAR(3) NOT NULL REFERENCES currency(code),
    parent_id          UUID REFERENCES account(id),
    gl_account_id      UUID REFERENCES account(id),   -- compte général de rattachement
    contract_id        UUID,                          -- si compte de contrat
    postable           BOOLEAN NOT NULL DEFAULT TRUE,
    control_available  BOOLEAN NOT NULL DEFAULT FALSE,
    stripe_count       SMALLINT NOT NULL DEFAULT 1 CHECK (stripe_count BETWEEN 1 AND 256),
    status             TEXT NOT NULL DEFAULT 'ACTIVE',
    opened_at          DATE NOT NULL,
    closed_at          DATE,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_account_code UNIQUE (legal_entity_id, code),
    CONSTRAINT ck_closed CHECK (closed_at IS NULL OR closed_at >= opened_at)
);

CREATE INDEX idx_account_contract ON account(contract_id) WHERE contract_id IS NOT NULL;
CREATE INDEX idx_account_gl       ON account(gl_account_id);
CREATE INDEX idx_account_entity_kind ON account(legal_entity_id, account_kind, status);
```

`gl_account_id` est le lien qui rend la comptabilité générale gratuite : l'agrégation des
comptes clients par compte de rattachement **est** la balance générale.

---

## 3. Journal — le cœur

```sql
CREATE TABLE journal_entry (
    id                UUID NOT NULL,
    booking_date      DATE NOT NULL,
    legal_entity_id   UUID NOT NULL REFERENCES legal_entity(id),
    entry_number      BIGINT NOT NULL,            -- séquence continue par entité
    transaction_id    UUID,                       -- opération métier de rattachement
    transaction_type  TEXT NOT NULL,
    source            TEXT NOT NULL,              -- ONLINE|BATCH|MIGRATION|CORRECTION
    batch_run_id      UUID REFERENCES batch_run(id),
    schema_version    TEXT,                       -- schéma comptable appliqué
    reversal_of       UUID,                       -- unicité portée par journal_reversal
    reversal_reason   TEXT,
    narrative         TEXT,
    metadata          JSONB NOT NULL DEFAULT '{}',
    created_by        UUID NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id, booking_date),
    CONSTRAINT uq_entry_number UNIQUE (legal_entity_id, entry_number, booking_date)
) PARTITION BY RANGE (booking_date);

CREATE TABLE journal_entry_2026_09 PARTITION OF journal_entry
    FOR VALUES FROM ('2026-09-01') TO ('2026-10-01');

CREATE TABLE journal_line (
    id                UUID NOT NULL,
    booking_date      DATE NOT NULL,              -- dénormalisé : clé de partitionnement
    entry_id          UUID NOT NULL,
    legal_entity_id   UUID NOT NULL,
    line_number       SMALLINT NOT NULL,
    account_id        UUID NOT NULL REFERENCES account(id),
    direction         TEXT NOT NULL CHECK (direction IN ('DEBIT','CREDIT')),
    amount            NUMERIC(23,5) NOT NULL CHECK (amount > 0),
    currency          CHAR(3) NOT NULL REFERENCES currency(code),
    functional_amount NUMERIC(23,5) NOT NULL CHECK (functional_amount >= 0),
    fx_rate           NUMERIC(20,10),
    value_date        DATE NOT NULL,
    stripe_id         SMALLINT NOT NULL DEFAULT 0,
    label             TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id, booking_date),
    CONSTRAINT uq_line UNIQUE (entry_id, line_number, booking_date),
    CONSTRAINT fk_line_entry FOREIGN KEY (entry_id, booking_date)
        REFERENCES journal_entry (id, booking_date)
) PARTITION BY RANGE (booking_date);

CREATE INDEX idx_line_account_value ON journal_line (account_id, value_date, id);
CREATE INDEX idx_line_account_book  ON journal_line (account_id, booking_date, id);
CREATE INDEX idx_line_entry         ON journal_line (entry_id);
```

### Contraintes d'unicité et partitionnement

PostgreSQL impose que toute contrainte unique d'une table partitionnée **contienne la clé de
partitionnement**. Or l'idempotence et l'unicité de contre-passation doivent être globales,
indépendamment de la date comptable. Elles sont donc portées par deux tables satellites non
partitionnées, alimentées dans la même transaction que l'écriture :

```sql
CREATE TABLE posting_idempotency (
    legal_entity_id UUID NOT NULL REFERENCES legal_entity(id),
    idempotency_key TEXT NOT NULL,
    entry_id        UUID NOT NULL,
    booking_date    DATE NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (legal_entity_id, idempotency_key)
);

CREATE TABLE journal_reversal (
    reversed_entry_id  UUID PRIMARY KEY,   -- une écriture n'est contre-passée qu'une fois
    reversal_entry_id  UUID NOT NULL UNIQUE,
    reversed_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    reversed_by        UUID NOT NULL,
    reason             TEXT NOT NULL
);
```

L'insertion dans `posting_idempotency` est la **première** opération de la transaction de
comptabilisation : une violation de clé primaire y détecte le rejeu avant tout travail, et
la transaction est abandonnée au profit du résultat déjà enregistré.

Ces deux tables croissent linéairement. `posting_idempotency` est purgée au-delà de la
fenêtre de rejeu utile (90 jours), après archivage.

### Immuabilité

```sql
CREATE OR REPLACE FUNCTION forbid_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Le journal comptable est immuable (table %, opération %)',
                    TG_TABLE_NAME, TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_journal_entry_immutable
    BEFORE UPDATE OR DELETE ON journal_entry
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

CREATE TRIGGER trg_journal_line_immutable
    BEFORE UPDATE OR DELETE ON journal_line
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

-- Défense complémentaire : droits restreints
REVOKE UPDATE, DELETE ON journal_entry, journal_line FROM core_app;
GRANT  INSERT, SELECT  ON journal_entry, journal_line TO   core_app;
```

### Contrôle d'équilibre

Vérifié dans le domaine, puis re-vérifié en base en fin de transaction — l'écriture et ses
lignes étant insérées dans la même transaction.

```sql
CREATE OR REPLACE FUNCTION check_entry_balanced() RETURNS trigger AS $$
DECLARE imbalance RECORD;
BEGIN
    FOR imbalance IN
        SELECT currency,
               SUM(CASE WHEN direction='DEBIT'  THEN amount ELSE -amount END) AS diff
          FROM journal_line WHERE entry_id = NEW.entry_id
         GROUP BY currency HAVING SUM(CASE WHEN direction='DEBIT'
                                           THEN amount ELSE -amount END) <> 0
    LOOP
        RAISE EXCEPTION 'Écriture % déséquilibrée en % : écart %',
                        NEW.entry_id, imbalance.currency, imbalance.diff;
    END LOOP;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_entry_balanced
    AFTER INSERT ON journal_line
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_entry_balanced();
```

`DEFERRABLE INITIALLY DEFERRED` est indispensable : le contrôle doit s'exécuter à la
validation de la transaction, quand toutes les lignes sont présentes.

---

## 4. Soldes

```sql
CREATE TABLE account_balance (
    account_id        UUID NOT NULL REFERENCES account(id),
    stripe_id         SMALLINT NOT NULL DEFAULT 0,
    balance           NUMERIC(23,5) NOT NULL DEFAULT 0,
    functional_balance NUMERIC(23,5) NOT NULL DEFAULT 0,
    last_entry_id     UUID,
    version           BIGINT NOT NULL DEFAULT 0,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, stripe_id)
);

CREATE VIEW account_balance_agg AS
SELECT account_id,
       SUM(balance)            AS balance,
       SUM(functional_balance) AS functional_balance
  FROM account_balance
 GROUP BY account_id;

CREATE TABLE account_balance_daily (
    account_id          UUID NOT NULL REFERENCES account(id),
    business_date       DATE NOT NULL,
    closing_balance     NUMERIC(23,5) NOT NULL,   -- en date comptable
    value_date_balance  NUMERIC(23,5) NOT NULL,   -- en date de valeur (base des intérêts)
    debit_turnover      NUMERIC(23,5) NOT NULL DEFAULT 0,
    credit_turnover     NUMERIC(23,5) NOT NULL DEFAULT 0,
    min_balance         NUMERIC(23,5),
    avg_balance         NUMERIC(23,5),
    PRIMARY KEY (account_id, business_date)
) PARTITION BY RANGE (business_date);   -- la clé de partition fait partie de la PK

CREATE TABLE account_hold (
    id            UUID PRIMARY KEY,
    account_id    UUID NOT NULL REFERENCES account(id),
    amount        NUMERIC(23,5) NOT NULL CHECK (amount > 0),
    currency      CHAR(3) NOT NULL,
    hold_type     TEXT NOT NULL,          -- JUDICIAL|CARD_AUTH|PENDING_OP|COLLATERAL
    reference     TEXT,
    priority      SMALLINT NOT NULL DEFAULT 100,
    expires_at    TIMESTAMPTZ,
    released_at   TIMESTAMPTZ,
    created_by    UUID NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_hold_active ON account_hold(account_id)
    WHERE released_at IS NULL;
```

### Solde disponible

```sql
CREATE OR REPLACE FUNCTION available_balance(p_account UUID)
RETURNS NUMERIC AS $$
    SELECT COALESCE(b.balance, 0)
         - COALESCE((SELECT SUM(h.amount) FROM account_hold h
                      WHERE h.account_id = p_account
                        AND h.released_at IS NULL
                        AND (h.expires_at IS NULL OR h.expires_at > now())), 0)
         + COALESCE((SELECT o.amount FROM overdraft_limit o
                      WHERE o.account_id = p_account
                        AND o.valid_from <= CURRENT_DATE
                        AND (o.valid_to IS NULL OR o.valid_to >= CURRENT_DATE)), 0)
      FROM account_balance_agg b WHERE b.account_id = p_account;
$$ LANGUAGE sql STABLE;
```

---

## 5. Produits et schémas comptables

```sql
CREATE TABLE product (
    id               UUID PRIMARY KEY,
    legal_entity_id  UUID NOT NULL REFERENCES legal_entity(id),
    code             TEXT NOT NULL,
    product_type     TEXT NOT NULL,          -- sélectionne le comportement (code)
    currency         CHAR(3) NOT NULL,
    parameters       JSONB NOT NULL,         -- validé par schéma JSON du type
    accounting_schema_id UUID NOT NULL REFERENCES accounting_schema(id),
    valid_from       DATE NOT NULL,
    valid_to         DATE,
    status           TEXT NOT NULL DEFAULT 'DRAFT',  -- DRAFT|ACTIVE|SUSPENDED|WITHDRAWN
    created_by       UUID NOT NULL,
    approved_by      UUID,
    CONSTRAINT uq_product UNIQUE (legal_entity_id, code, valid_from),
    CONSTRAINT ck_product_validity CHECK (valid_to IS NULL OR valid_to > valid_from),
    CONSTRAINT ck_product_approval CHECK (status <> 'ACTIVE' OR approved_by IS NOT NULL)
);

-- Aucun chevauchement de périodes pour un même code produit
CREATE EXTENSION IF NOT EXISTS btree_gist;
ALTER TABLE product ADD CONSTRAINT ex_product_no_overlap
    EXCLUDE USING gist (
        legal_entity_id WITH =, code WITH =,
        daterange(valid_from, COALESCE(valid_to, 'infinity'::date), '[)') WITH &&
    );

CREATE TABLE accounting_schema (
    id              UUID PRIMARY KEY,
    legal_entity_id UUID NOT NULL REFERENCES legal_entity(id),
    code            TEXT NOT NULL,
    version         INT  NOT NULL,
    definition      JSONB NOT NULL,      -- événement → lignes d'écriture
    valid_from      DATE NOT NULL,
    valid_to        DATE,
    approved_by     UUID,
    CONSTRAINT uq_schema UNIQUE (legal_entity_id, code, version)
);
```

La contrainte `EXCLUDE` garantit qu'il n'existe **jamais** deux versions d'un produit
valides à la même date. Sans elle, un arrêté rejoué peut sélectionner l'une ou l'autre selon
l'ordre de lecture, et produire des montants non reproductibles.

### Registre des commissions

```sql
CREATE TABLE fee_charge (
    id               UUID PRIMARY KEY,
    account_id       UUID NOT NULL REFERENCES account(id),
    fee_code         TEXT NOT NULL,
    period_start     DATE NOT NULL,
    period_end       DATE NOT NULL,
    charge_date      DATE NOT NULL,
    net_amount       NUMERIC(23,5) NOT NULL,
    tax_amount       NUMERIC(23,5) NOT NULL,
    total_amount     NUMERIC(23,5) NOT NULL,
    outcome          TEXT NOT NULL,     -- COLLECTED, FORCED, WAIVED, DEFERRED,
                                        -- REJECTED, WRITTEN_OFF, NOT_DUE, CANCELLED
    entry_id         UUID,
    generation       INTEGER NOT NULL DEFAULT 0,
    ...
    CONSTRAINT ck_fee_total CHECK (total_amount = net_amount + tax_amount),
    CONSTRAINT ex_fee_no_overlap EXCLUDE USING gist (
        account_id WITH =, fee_code WITH =,
        daterange(period_start, period_end + 1, '[)') WITH &&
    ) WHERE (outcome <> 'CANCELLED')
);
```

Trois points portés par le schéma, et non par l'applicatif.

**L'exclusion sur la plage, plutôt qu'une clé unique sur la fin de période.** L'invariant réel n'est
pas « une échéance facturée une fois » mais « **aucun jour facturé deux fois** ». Un changement
d'ancrage décale les bornes : deux liquidations pourraient se chevaucher sans coïncider, et une clé
unique les laisserait passer.

**La ligne existe même quand rien n'est perçu.** Exonération, provision insuffisante, prorata nul :
le montant est conservé avec son motif. Une commission non perçue qui ne laisse aucune trace est un
manque à gagner inconnaissable — les comptes restent équilibrés et les contrôles passent.

**Le montant est figé, le dénouement évolue.** Un déclencheur refuse toute modification des colonnes
de liquidation et toute réouverture d'un dénouement acquis ; seul l'état `CANCELLED`, produit par
l'annulation du traitement, peut neutraliser une ligne. Le rang `generation` compte ces annulations
et entre dans la clé d'idempotence de la refacturation : l'écriture d'origine subsiste au journal,
contre-passée, et réutiliser sa clé ferait passer la refacturation pour un rejeu.

---

## 6. Batch et outbox

```sql
CREATE TABLE batch_run (
    id              UUID PRIMARY KEY,
    legal_entity_id UUID NOT NULL REFERENCES legal_entity(id),
    business_date   DATE NOT NULL,
    run_type        TEXT NOT NULL,          -- TFJ|TFM|TFT|TFA|ADHOC
    status          TEXT NOT NULL,          -- PLANNED|RUNNING|COMPLETED|FAILED|CANCELLED
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ,
    cancelled_by    UUID,
    CONSTRAINT uq_run UNIQUE (legal_entity_id, business_date, run_type)
);

CREATE TABLE batch_step (
    id              UUID PRIMARY KEY,
    run_id          UUID NOT NULL REFERENCES batch_run(id),
    step_order      SMALLINT NOT NULL,
    step_name       TEXT NOT NULL,
    status          TEXT NOT NULL,
    blocking        BOOLEAN NOT NULL DEFAULT TRUE,
    read_count      BIGINT NOT NULL DEFAULT 0,
    write_count     BIGINT NOT NULL DEFAULT 0,
    error_count     BIGINT NOT NULL DEFAULT 0,
    checkpoint      JSONB,
    error_detail    TEXT,
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ,
    CONSTRAINT uq_step UNIQUE (run_id, step_order)
);

CREATE TABLE outbox (
    id             UUID PRIMARY KEY,
    aggregate_type TEXT NOT NULL,
    aggregate_id   UUID NOT NULL,
    event_type     TEXT NOT NULL,
    schema_version TEXT NOT NULL,
    payload        JSONB NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ
);

CREATE INDEX idx_outbox_pending ON outbox(created_at) WHERE published_at IS NULL;
```

---

## 7. Audit et maker-checker

```sql
CREATE TABLE audit_log (
    id              BIGINT GENERATED ALWAYS AS IDENTITY,
    occurred_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    legal_entity_id UUID,
    actor_id        UUID NOT NULL,
    actor_role      TEXT,
    action          TEXT NOT NULL,          -- CREATE|UPDATE|DELETE|READ|APPROVE|REJECT
    resource_type   TEXT NOT NULL,
    resource_id     TEXT NOT NULL,
    before_state    JSONB,
    after_state     JSONB,
    reason          TEXT,
    channel         TEXT,
    ip_address      INET,
    correlation_id  UUID,
    PRIMARY KEY (id, occurred_at)
) PARTITION BY RANGE (occurred_at);

CREATE TRIGGER trg_audit_immutable
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

CREATE TABLE pending_operation (
    id              UUID PRIMARY KEY,
    legal_entity_id UUID NOT NULL REFERENCES legal_entity(id),
    operation_type  TEXT NOT NULL,
    payload         JSONB NOT NULL,
    amount          NUMERIC(23,5),
    currency        CHAR(3),
    status          TEXT NOT NULL DEFAULT 'PENDING',
    required_approvals SMALLINT NOT NULL DEFAULT 1,
    maker_id        UUID NOT NULL,
    made_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ NOT NULL,
    hold_id         UUID REFERENCES account_hold(id),   -- réservation du disponible
    executed_entry_id UUID,              -- pas de FK : journal_entry est partitionné
    executed_booking_date DATE
);

CREATE TABLE operation_approval (
    id            UUID PRIMARY KEY,
    operation_id  UUID NOT NULL REFERENCES pending_operation(id),
    checker_id    UUID NOT NULL,
    decision      TEXT NOT NULL CHECK (decision IN ('APPROVED','REJECTED')),
    reason        TEXT,
    decided_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_one_decision_per_checker UNIQUE (operation_id, checker_id)
);

-- Le maker ne peut pas être son propre checker
CREATE OR REPLACE FUNCTION check_segregation() RETURNS trigger AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM pending_operation p
                WHERE p.id = NEW.operation_id AND p.maker_id = NEW.checker_id) THEN
        RAISE EXCEPTION 'Séparation des tâches : le maker ne peut pas valider sa propre opération';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_segregation BEFORE INSERT ON operation_approval
    FOR EACH ROW EXECUTE FUNCTION check_segregation();
```

---

## 8. Partitionnement et rétention

| Table | Partition | Rétention en ligne | Au-delà |
|---|---|---|---|
| `journal_entry` / `journal_line` | Mensuelle, par `booking_date` | 24 mois | Partition détachée, archivée en stockage objet |
| `account_balance_daily` | Annuelle | 10 ans | Conservée (volumétrie faible) |
| `audit_log` | Mensuelle | 12 mois | Archivée, 10 ans au total |
| `outbox` | — | 7 jours | Purge des lignes publiées |

Le détachement de partition (`DETACH PARTITION`) est instantané et ne bloque pas la
production, contrairement à un `DELETE` de masse qui saturerait les WAL et déclencherait un
`VACUUM` massif.

**Les partitions archivées restent interrogeables** : une écriture de 2019 doit être
consultable en cas de contrôle. Restauration à la demande, via une table externe ou une
réattache temporaire.
