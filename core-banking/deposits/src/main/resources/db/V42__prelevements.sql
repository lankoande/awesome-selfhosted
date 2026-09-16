-- =====================================================================================
--  Prelevements : mandats, prelevements recus et emis
--
--  Un mandat est l'autorisation qu'un client donne a un creancier de debiter son compte :
--  reference du creancier et du mandat, validite, plafond par prelevement ; enregistre a deux,
--  revocable par le client. Un prelevement recu debite le client a l'echeance sur presentation
--  du creancier — interne (compte de la banque) ou externe (via la compensation, les fonds
--  attendant le correspondant sur le compte de reglement) ; sans provision, sans mandat, ou sur
--  un compte qui ne peut pas operer, il est rejete, et le rejet est enregistre. Un prelevement
--  emis credite le creancier client sauf bonne fin a l'echeance, bloque jusqu'au reglement par
--  le correspondant ; un retour du debiteur contre-passe le credit.
-- =====================================================================================
CREATE TABLE debit_mandate (
    id                  UUID PRIMARY KEY,
    legal_entity_id     UUID NOT NULL REFERENCES legal_entity(id),
    account_id          UUID NOT NULL REFERENCES account(id),
    reference           TEXT NOT NULL,
    creditor_id         TEXT NOT NULL,
    creditor_name       TEXT NOT NULL,
    creditor_account_id UUID REFERENCES account(id),
    creditor_bank       TEXT,
    creditor_account    TEXT,
    signed_on           DATE NOT NULL,
    valid_from          DATE NOT NULL,
    valid_to            DATE,
    max_amount          NUMERIC(23,5),
    currency            CHAR(3) NOT NULL REFERENCES currency(code),
    status              TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','REVOKED')),
    revoked_on          DATE,
    revocation_reason   TEXT,
    created_by          UUID NOT NULL,
    approved_by         UUID NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_mandate_reference UNIQUE (legal_entity_id, creditor_id, reference),
    CONSTRAINT ck_mandate_approval CHECK (approved_by <> created_by),
    CONSTRAINT ck_mandate_validity CHECK (valid_to IS NULL OR valid_to >= valid_from),
    CONSTRAINT ck_mandate_max CHECK (max_amount IS NULL OR max_amount > 0),
    -- Le creancier est de la banque (un compte) ou d'ailleurs (une banque et un compte), pas les deux.
    CONSTRAINT ck_mandate_creditor CHECK (
        (creditor_account_id IS NOT NULL AND creditor_bank IS NULL AND creditor_account IS NULL)
        OR (creditor_account_id IS NULL AND creditor_bank IS NOT NULL AND creditor_account IS NOT NULL)),
    CONSTRAINT ck_mandate_revoked CHECK (status <> 'REVOKED'
        OR (revoked_on IS NOT NULL AND revocation_reason IS NOT NULL))
);
CREATE INDEX idx_mandate_account ON debit_mandate(account_id, status);

CREATE TABLE direct_debit (
    id                    UUID PRIMARY KEY,
    legal_entity_id       UUID NOT NULL REFERENCES legal_entity(id),
    direction             TEXT NOT NULL CHECK (direction IN ('RECEIVED','ISSUED')),
    -- Le compte du client de la banque : le debiteur d'un prelevement recu, le creancier d'un emis.
    account_id            UUID NOT NULL REFERENCES account(id),
    mandate_id            UUID REFERENCES debit_mandate(id),
    idempotency_key       TEXT NOT NULL,
    amount                NUMERIC(23,5) NOT NULL CHECK (amount > 0),
    currency              CHAR(3) NOT NULL REFERENCES currency(code),
    fee                   NUMERIC(23,5) NOT NULL DEFAULT 0,
    tax                   NUMERIC(23,5) NOT NULL DEFAULT 0,
    due_date              DATE NOT NULL,
    -- Le tiers : creancier d'un prelevement recu (par le mandat), debiteur d'un emis.
    counterparty_name     TEXT NOT NULL,
    counterparty_bank     TEXT,
    counterparty_account  TEXT,
    mandate_reference     TEXT NOT NULL,
    reference             TEXT,
    channel               TEXT,
    status                TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN (
        'PENDING','COLLECTED','SETTLED','REJECTED','CANCELLED','RETURNED','REFUNDED')),
    presented_on          DATE NOT NULL,
    executed_on           DATE,
    executed_run_id       UUID,
    value_date            DATE,
    entry_id              UUID,
    fee_entry_id          UUID,
    hold_id               UUID,
    clearing_account_id   UUID REFERENCES account(id),
    rejection_reason      TEXT,
    settled_on            DATE,
    settlement_account_id UUID REFERENCES account(id),
    settlement_entry_id   UUID,
    closed_on             DATE,
    close_entry_id        UUID,
    close_reason          TEXT,
    created_by            UUID NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_direct_debit_key UNIQUE (legal_entity_id, idempotency_key),
    CONSTRAINT ck_dd_received CHECK (direction <> 'RECEIVED' OR mandate_id IS NOT NULL),
    CONSTRAINT ck_dd_issued CHECK (direction <> 'ISSUED'
        OR (counterparty_bank IS NOT NULL AND counterparty_account IS NOT NULL)),
    CONSTRAINT ck_dd_executed CHECK (status NOT IN ('COLLECTED','SETTLED','RETURNED','REFUNDED')
        OR (executed_on IS NOT NULL AND entry_id IS NOT NULL AND value_date IS NOT NULL)),
    CONSTRAINT ck_dd_rejected CHECK (status <> 'REJECTED'
        OR (executed_on IS NOT NULL AND rejection_reason IS NOT NULL)),
    CONSTRAINT ck_dd_settled CHECK (status NOT IN ('SETTLED','REFUNDED')
        OR (settled_on IS NOT NULL AND settlement_entry_id IS NOT NULL)),
    CONSTRAINT ck_dd_closed CHECK (status NOT IN ('CANCELLED','RETURNED','REFUNDED')
        OR (closed_on IS NOT NULL AND close_reason IS NOT NULL))
);
CREATE INDEX idx_direct_debit_due ON direct_debit(legal_entity_id, status, due_date);
CREATE INDEX idx_direct_debit_account ON direct_debit(account_id, presented_on);
CREATE INDEX idx_direct_debit_run ON direct_debit(executed_run_id) WHERE executed_run_id IS NOT NULL;

ALTER TABLE debit_mandate ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON debit_mandate
    USING (legal_entity_id = ledger_current_entity())
    WITH CHECK (legal_entity_id = ledger_current_entity());
ALTER TABLE direct_debit ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON direct_debit
    USING (legal_entity_id = ledger_current_entity())
    WITH CHECK (legal_entity_id = ledger_current_entity());
