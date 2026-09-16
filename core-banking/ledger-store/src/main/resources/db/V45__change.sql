-- =====================================================================================
--  Change : cours de reference et positions
--
--  Le ledger verifie que les contre-valeurs d'une ecriture s'equilibrent ; il ne peut pas
--  voir un cours faux applique uniformement, puisque les contre-valeurs se compensent alors
--  deux a deux quel que soit le cours. Seule la confrontation au cours de reference le
--  detecte : c'est un controle du referentiel, et il est ici.
--
--  Une position de change apparie, pour une devise, le compte de position — tenu dans la
--  devise, il mesure l'exposition — et son compte de contre-valeur — tenu dans la devise de
--  l'entite, il porte la valeur historique. L'arrete revalorise le second au cours du jour ;
--  l'ecart va au resultat de change.
-- =====================================================================================
CREATE TABLE fx_rate (
    id              UUID PRIMARY KEY,
    legal_entity_id UUID NOT NULL REFERENCES legal_entity(id),
    currency        CHAR(3) NOT NULL REFERENCES currency(code),
    quoted_on       DATE NOT NULL,
    -- Unites de la devise de tenue pour une unite de la devise cotee : 655,957 XOF pour 1 EUR.
    rate            NUMERIC(20,10) NOT NULL CHECK (rate > 0),
    source          TEXT NOT NULL,
    created_by      UUID NOT NULL,
    approved_by     UUID NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_fx_rate UNIQUE (legal_entity_id, currency, quoted_on),
    CONSTRAINT ck_fx_rate_approval CHECK (approved_by <> created_by)
);
CREATE INDEX idx_fx_rate_lookup ON fx_rate(legal_entity_id, currency, quoted_on DESC);

CREATE TABLE fx_position (
    id                       UUID PRIMARY KEY,
    legal_entity_id          UUID NOT NULL REFERENCES legal_entity(id),
    currency                 CHAR(3) NOT NULL REFERENCES currency(code),
    position_account_id      UUID NOT NULL REFERENCES account(id),
    counter_value_account_id UUID NOT NULL REFERENCES account(id),
    gain_account_id          UUID NOT NULL REFERENCES account(id),
    loss_account_id          UUID NOT NULL REFERENCES account(id),
    -- Ecart tolere, en points de base, entre le cours applique et le cours de reference :
    -- la marge que la banque prend sur ses operations de change.
    tolerance_bps            INTEGER NOT NULL CHECK (tolerance_bps BETWEEN 0 AND 10000),
    created_by               UUID NOT NULL,
    approved_by              UUID NOT NULL,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_fx_position UNIQUE (legal_entity_id, currency),
    CONSTRAINT ck_fx_position_approval CHECK (approved_by <> created_by),
    CONSTRAINT ck_fx_position_pair CHECK (position_account_id <> counter_value_account_id)
);

ALTER TABLE fx_rate ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON fx_rate
    USING (legal_entity_id = ledger_current_entity())
    WITH CHECK (legal_entity_id = ledger_current_entity());
ALTER TABLE fx_position ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON fx_position
    USING (legal_entity_id = ledger_current_entity())
    WITH CHECK (legal_entity_id = ledger_current_entity());
