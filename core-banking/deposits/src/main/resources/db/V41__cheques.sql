-- =====================================================================================
--  Cheques : chequiers, paiement, opposition, incidents, remises
--
--  Un chequier est une plage de numeros delivree a un compte, a deux, aux frais du produit.
--  Chaque cheque a son etat : non emis, paye, frappe d'opposition, rejete. Un cheque presente
--  sans provision est rejete et l'incident est enregistre — il fonde l'interdiction bancaire
--  et la declaration a la centrale des incidents ; le cheque peut etre represente. Une remise
--  de cheque tire sur une autre banque credite le client sauf bonne fin : le montant est
--  bloque jusqu'au reglement par le correspondant, et un impaye contre-passe le credit.
-- =====================================================================================
CREATE TABLE cheque_book (
    id              UUID PRIMARY KEY,
    legal_entity_id UUID NOT NULL REFERENCES legal_entity(id),
    account_id      UUID NOT NULL REFERENCES account(id),
    first_number    BIGINT NOT NULL CHECK (first_number > 0),
    last_number     BIGINT NOT NULL,
    delivered_on    DATE NOT NULL,
    status          TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','CANCELLED')),
    fee             NUMERIC(23,5) NOT NULL DEFAULT 0,
    fee_entry_id    UUID,
    created_by      UUID NOT NULL,
    approved_by     UUID NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_book_range CHECK (last_number >= first_number),
    CONSTRAINT ck_book_approval CHECK (approved_by <> created_by),
    -- Deux chequiers d'un compte ne partagent aucun numero.
    CONSTRAINT ex_book_no_overlap EXCLUDE USING gist (
        account_id WITH =,
        int8range(first_number, last_number, '[]') WITH &&
    )
);

CREATE TABLE cheque (
    legal_entity_id UUID NOT NULL REFERENCES legal_entity(id),
    book_id         UUID NOT NULL REFERENCES cheque_book(id),
    account_id      UUID NOT NULL REFERENCES account(id),
    number          BIGINT NOT NULL,
    status          TEXT NOT NULL DEFAULT 'UNUSED'
        CHECK (status IN ('UNUSED','PAID','STOPPED','REJECTED')),
    amount          NUMERIC(23,5),
    beneficiary     TEXT,
    paid_on         DATE,
    entry_id        UUID,
    idempotency_key TEXT,
    stopped_on      DATE,
    stop_reason     TEXT,
    PRIMARY KEY (account_id, number),
    CONSTRAINT uq_cheque_book_number UNIQUE (book_id, number),
    CONSTRAINT ck_cheque_paid CHECK (status <> 'PAID'
        OR (paid_on IS NOT NULL AND entry_id IS NOT NULL AND amount IS NOT NULL)),
    CONSTRAINT ck_cheque_stopped CHECK (status <> 'STOPPED'
        OR (stopped_on IS NOT NULL AND stop_reason IS NOT NULL))
);

CREATE TABLE cheque_incident (
    id              UUID PRIMARY KEY,
    legal_entity_id UUID NOT NULL REFERENCES legal_entity(id),
    account_id      UUID NOT NULL REFERENCES account(id),
    number          BIGINT NOT NULL,
    amount          NUMERIC(23,5) NOT NULL,
    currency        CHAR(3) NOT NULL REFERENCES currency(code),
    occurred_on     DATE NOT NULL,
    reason          TEXT NOT NULL,
    presented_by    TEXT,
    created_by      UUID NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_incident_account ON cheque_incident(account_id, occurred_on);

CREATE TABLE cheque_deposit (
    id                    UUID PRIMARY KEY,
    legal_entity_id       UUID NOT NULL REFERENCES legal_entity(id),
    account_id            UUID NOT NULL REFERENCES account(id),
    idempotency_key       TEXT NOT NULL,
    amount                NUMERIC(23,5) NOT NULL CHECK (amount > 0),
    currency              CHAR(3) NOT NULL REFERENCES currency(code),
    drawee_bank           TEXT NOT NULL,
    cheque_number         TEXT NOT NULL,
    drawer_name           TEXT NOT NULL,
    channel               TEXT,
    status                TEXT NOT NULL DEFAULT 'DEPOSITED'
        CHECK (status IN ('DEPOSITED','SETTLED','RETURNED')),
    deposited_on          DATE NOT NULL,
    value_date            DATE NOT NULL,
    entry_id              UUID NOT NULL,
    hold_id               UUID NOT NULL,
    collection_account_id UUID NOT NULL REFERENCES account(id),
    settled_on            DATE,
    settlement_account_id UUID REFERENCES account(id),
    settlement_entry_id   UUID,
    returned_on           DATE,
    return_entry_id       UUID,
    return_reason         TEXT,
    created_by            UUID NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_cheque_deposit_key UNIQUE (legal_entity_id, idempotency_key),
    CONSTRAINT ck_deposit_settled CHECK (status <> 'SETTLED'
        OR (settled_on IS NOT NULL AND settlement_account_id IS NOT NULL
            AND settlement_entry_id IS NOT NULL)),
    CONSTRAINT ck_deposit_returned CHECK (status <> 'RETURNED'
        OR (returned_on IS NOT NULL AND return_entry_id IS NOT NULL AND return_reason IS NOT NULL))
);
CREATE INDEX idx_cheque_deposit_account ON cheque_deposit(account_id, deposited_on);
CREATE INDEX idx_cheque_deposit_status ON cheque_deposit(legal_entity_id, status, deposited_on);

ALTER TABLE cheque_book ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON cheque_book
    USING (legal_entity_id = ledger_current_entity())
    WITH CHECK (legal_entity_id = ledger_current_entity());
ALTER TABLE cheque ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON cheque
    USING (legal_entity_id = ledger_current_entity())
    WITH CHECK (legal_entity_id = ledger_current_entity());
ALTER TABLE cheque_incident ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON cheque_incident
    USING (legal_entity_id = ledger_current_entity())
    WITH CHECK (legal_entity_id = ledger_current_entity());
ALTER TABLE cheque_deposit ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON cheque_deposit
    USING (legal_entity_id = ledger_current_entity())
    WITH CHECK (legal_entity_id = ledger_current_entity());
