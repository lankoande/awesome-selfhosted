--liquibase formatted sql
--changeset socle:7 splitStatements:false
--comment calendar
-- =====================================================================================
--  Calendrier des jours ouvres et conditions de date de valeur
--
--  Les deux sont dates : un arrete rejoue doit retrouver le calendrier et les conditions
--  en vigueur a l'epoque. Un ferie ajoute apres coup ne doit pas modifier une date de
--  valeur deja appliquee.
-- =====================================================================================

CREATE TABLE business_calendar (
    id         UUID PRIMARY KEY,
    code       TEXT NOT NULL UNIQUE,
    label      TEXT NOT NULL,
    -- Periode reellement saisie. Au-dela, le calendrier refuse de repondre plutot que de
    -- presumer qu'un jour non saisi est ouvre.
    covers_from DATE NOT NULL,
    covers_to   DATE NOT NULL,
    CONSTRAINT ck_calendar_coverage CHECK (covers_to >= covers_from)
);

-- Le week-end est parametre : il ne tombe pas partout le samedi et le dimanche.
CREATE TABLE calendar_weekend (
    calendar_id UUID NOT NULL REFERENCES business_calendar(id) ON DELETE CASCADE,
    day_of_week SMALLINT NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),
    PRIMARY KEY (calendar_id, day_of_week)
);

CREATE TABLE calendar_holiday (
    calendar_id  UUID NOT NULL REFERENCES business_calendar(id) ON DELETE CASCADE,
    holiday_date DATE NOT NULL,
    label        TEXT NOT NULL,
    PRIMARY KEY (calendar_id, holiday_date)
);

ALTER TABLE legal_entity ADD COLUMN business_calendar_id UUID REFERENCES business_calendar(id);

-- -------------------------------------------------------------------------------------
--  Conditions de date de valeur
--
--  La cle inclut le sens : les conditions de banque decalent rarement le debit et le
--  credit de la meme facon, et c'est precisement cette asymetrie qui se facture.
-- -------------------------------------------------------------------------------------
CREATE TABLE value_date_rule (
    id              UUID PRIMARY KEY,
    legal_entity_id UUID NOT NULL REFERENCES legal_entity(id),
    operation_type  TEXT NOT NULL,
    channel         TEXT,                       -- NULL : toute operation du type
    direction       TEXT NOT NULL CHECK (direction IN ('DEBIT','CREDIT')),
    offset_days     SMALLINT NOT NULL,
    offset_unit     TEXT NOT NULL CHECK (offset_unit IN ('CALENDAR_DAYS','BUSINESS_DAYS')),
    convention      TEXT NOT NULL
                    CHECK (convention IN ('UNADJUSTED','FOLLOWING','MODIFIED_FOLLOWING',
                                          'PRECEDING','MODIFIED_PRECEDING')),
    valid_from      DATE NOT NULL,
    valid_to        DATE,
    created_by      UUID NOT NULL,
    approved_by     UUID,
    CONSTRAINT ck_rule_validity CHECK (valid_to IS NULL OR valid_to >= valid_from),
    -- Les conditions de banque produisent des agios : meme regime de double validation que
    -- le parametrage produit.
    CONSTRAINT ck_rule_approval CHECK (approved_by IS NULL OR approved_by <> created_by)
);

-- Deux regles de meme portee et de periodes qui se chevauchent rendraient la date de valeur
-- dependante de l'ordre de lecture.
CREATE EXTENSION IF NOT EXISTS btree_gist;
ALTER TABLE value_date_rule ADD CONSTRAINT ex_rule_no_overlap
    EXCLUDE USING gist (
        legal_entity_id WITH =,
        operation_type WITH =,
        COALESCE(channel, '*') WITH =,
        direction WITH =,
        daterange(valid_from, COALESCE(valid_to + 1, 'infinity'::date), '[)') WITH &&
    );

CREATE INDEX idx_rule_resolution
    ON value_date_rule(legal_entity_id, operation_type, valid_from);
