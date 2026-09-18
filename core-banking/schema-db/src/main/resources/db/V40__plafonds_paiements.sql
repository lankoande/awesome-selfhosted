--liquibase formatted sql
--changeset socle:40 splitStatements:false
--comment plafonds paiements
-- =====================================================================================
--  Plafonds d'operations et paiements sortants
--
--  Un plafond borne ce qu'un compte peut debiter : par operation, par jour, par mois. Il vient
--  du produit, et un compte peut porter le sien, negocie, a deux, par nature et periode de
--  validite ; l'usage se lit dans le journal, jamais dans un compteur, ce qui le rend exact
--  apres toute annulation.
--
--  Un paiement sortant est un ordre : le client est debite a l'ordre, sur un compte de
--  reglement sortant du produit — les fonds ne sont pas encore chez le correspondant, et le
--  bilan le montre —, puis l'ordre est envoye, regle sur le nostro, ou retourne ; avant
--  envoi, il s'annule par contre-passation. Chaque etat porte sa date et son ecriture.
-- =====================================================================================
CREATE TABLE account_limit (
    id              UUID PRIMARY KEY,
    legal_entity_id UUID NOT NULL REFERENCES legal_entity(id),
    account_id      UUID NOT NULL REFERENCES account(id),
    kind            TEXT NOT NULL CHECK (kind IN ('TRANSACTION','DAILY','MONTHLY')),
    amount          NUMERIC(23,5) NOT NULL CHECK (amount >= 0),
    valid_from      DATE NOT NULL,
    valid_to        DATE,
    created_by      UUID NOT NULL,
    approved_by     UUID NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_limit_validity CHECK (valid_to IS NULL OR valid_to >= valid_from),
    CONSTRAINT ck_limit_approval CHECK (approved_by <> created_by),
    -- Un seul plafond d'une nature a la fois sur un compte : deux plafonds seraient deux verites.
    CONSTRAINT ex_limit_no_overlap EXCLUDE USING gist (
        account_id WITH =,
        kind WITH =,
        daterange(valid_from, COALESCE(valid_to + 1, 'infinity'::date), '[)') WITH &&
    )
);
CREATE INDEX idx_limit_account ON account_limit(account_id);

CREATE TABLE payment_order (
    id                    UUID PRIMARY KEY,
    legal_entity_id       UUID NOT NULL REFERENCES legal_entity(id),
    account_id            UUID NOT NULL REFERENCES account(id),
    idempotency_key       TEXT NOT NULL,
    amount                NUMERIC(23,5) NOT NULL CHECK (amount > 0),
    currency              CHAR(3) NOT NULL REFERENCES currency(code),
    fee                   NUMERIC(23,5) NOT NULL DEFAULT 0,
    tax                   NUMERIC(23,5) NOT NULL DEFAULT 0,
    beneficiary_name      TEXT NOT NULL,
    beneficiary_bank      TEXT NOT NULL,
    beneficiary_account   TEXT NOT NULL,
    reference             TEXT,
    channel               TEXT,
    status                TEXT NOT NULL DEFAULT 'ORDERED'
        CHECK (status IN ('ORDERED','SENT','SETTLED','RETURNED','CANCELLED')),
    ordered_on            DATE NOT NULL,
    entry_id              UUID NOT NULL,
    clearing_account_id   UUID NOT NULL REFERENCES account(id),
    sent_on               DATE,
    settled_on            DATE,
    settlement_account_id UUID REFERENCES account(id),
    settlement_entry_id   UUID,
    returned_on           DATE,
    return_entry_id       UUID,
    return_reason         TEXT,
    cancelled_on          DATE,
    cancel_entry_id       UUID,
    cancel_reason         TEXT,
    created_by            UUID NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_payment_key UNIQUE (legal_entity_id, idempotency_key),
    CONSTRAINT ck_payment_sent CHECK (status NOT IN ('SENT','SETTLED') OR sent_on IS NOT NULL),
    CONSTRAINT ck_payment_settled CHECK (status <> 'SETTLED'
        OR (settled_on IS NOT NULL AND settlement_account_id IS NOT NULL
            AND settlement_entry_id IS NOT NULL)),
    CONSTRAINT ck_payment_returned CHECK (status <> 'RETURNED'
        OR (returned_on IS NOT NULL AND return_entry_id IS NOT NULL AND return_reason IS NOT NULL)),
    CONSTRAINT ck_payment_cancelled CHECK (status <> 'CANCELLED'
        OR (cancelled_on IS NOT NULL AND cancel_entry_id IS NOT NULL AND cancel_reason IS NOT NULL))
);
CREATE INDEX idx_payment_account ON payment_order(account_id, ordered_on);
CREATE INDEX idx_payment_status ON payment_order(legal_entity_id, status, ordered_on);

ALTER TABLE account_limit ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON account_limit
    USING (legal_entity_id = ledger_current_entity())
    WITH CHECK (legal_entity_id = ledger_current_entity());

ALTER TABLE payment_order ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON payment_order
    USING (legal_entity_id = ledger_current_entity())
    WITH CHECK (legal_entity_id = ledger_current_entity());
