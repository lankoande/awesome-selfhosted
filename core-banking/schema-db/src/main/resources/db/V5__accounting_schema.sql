--liquibase formatted sql
--changeset socle:5 splitStatements:false
--comment accounting schema
-- =====================================================================================
--  Schemas comptables — traduction evenement metier vers ecritures
--
--  Versionnes et dates, comme les produits : un arrete rejoue doit reproduire les memes
--  imputations, y compris apres un changement de schema.
-- =====================================================================================

CREATE TABLE accounting_schema (
    id              UUID PRIMARY KEY,
    legal_entity_id UUID NOT NULL REFERENCES legal_entity(id),
    code            TEXT NOT NULL,
    label           TEXT NOT NULL,
    currency        CHAR(3) NOT NULL REFERENCES currency(code),
    valid_from      DATE NOT NULL,
    valid_to        DATE,
    status          TEXT NOT NULL DEFAULT 'DRAFT'
                    CHECK (status IN ('DRAFT','ACTIVE','SUSPENDED','WITHDRAWN')),
    created_by      UUID NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    approved_by     UUID,
    approved_at     TIMESTAMPTZ,

    CONSTRAINT ck_schema_validity CHECK (valid_to IS NULL OR valid_to >= valid_from),
    CONSTRAINT ck_schema_approval CHECK (
        status <> 'ACTIVE'
        OR (approved_by IS NOT NULL AND approved_by <> created_by)),
    CONSTRAINT ex_schema_no_overlap EXCLUDE USING gist (
        legal_entity_id WITH =,
        code WITH =,
        daterange(valid_from, COALESCE(valid_to + 1, 'infinity'::date), '[)') WITH &&
    ) WHERE (status = 'ACTIVE')
);

-- Variables calculees par le schema, dans l'ordre d'evaluation.
CREATE TABLE accounting_schema_derivation (
    schema_id       UUID NOT NULL REFERENCES accounting_schema(id) ON DELETE CASCADE,
    event_type      TEXT NOT NULL,
    derivation_order SMALLINT NOT NULL,
    name            TEXT NOT NULL,
    expression      TEXT NOT NULL,
    PRIMARY KEY (schema_id, event_type, derivation_order)
);

CREATE TABLE accounting_schema_line (
    schema_id    UUID NOT NULL REFERENCES accounting_schema(id) ON DELETE CASCADE,
    event_type   TEXT NOT NULL,
    line_order   SMALLINT NOT NULL,
    account_ref  TEXT NOT NULL,
    direction    TEXT NOT NULL CHECK (direction IN ('DEBIT','CREDIT')),
    amount_expr  TEXT NOT NULL,
    condition_expr TEXT,
    label        TEXT,
    PRIMARY KEY (schema_id, event_type, line_order)
);

CREATE INDEX idx_schema_resolution
    ON accounting_schema(legal_entity_id, code, valid_from) WHERE status = 'ACTIVE';
