--liquibase formatted sql
--changeset socle:43 splitStatements:false
--comment cutoff
-- =====================================================================================
--  Heure limite par canal
--
--  Au-dela de l'heure limite d'un canal, l'operation porte la date de valeur du jour ouvre
--  suivant ; un canal qui ferme a son heure limite refuse l'operation. L'heure se lit dans
--  le fuseau de l'entite. Datee comme une condition de banque, et validee a deux comme elle :
--  une heure limite deplace des dates de valeur, donc des interets.
-- =====================================================================================
CREATE TABLE channel_cutoff (
    id              UUID PRIMARY KEY,
    legal_entity_id UUID NOT NULL REFERENCES legal_entity(id),
    channel         TEXT,                       -- NULL : tout canal
    cutoff_time     TIME NOT NULL,
    closes_channel  BOOLEAN NOT NULL DEFAULT FALSE,
    valid_from      DATE NOT NULL,
    valid_to        DATE,
    created_by      UUID NOT NULL,
    approved_by     UUID NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_cutoff_validity CHECK (valid_to IS NULL OR valid_to >= valid_from),
    CONSTRAINT ck_cutoff_approval CHECK (approved_by <> created_by),
    -- Deux heures limites de meme portee sur des periodes qui se chevauchent rendraient la
    -- date de valeur dependante de l'ordre de lecture.
    CONSTRAINT ex_cutoff_no_overlap EXCLUDE USING gist (
        legal_entity_id WITH =,
        COALESCE(channel, '*') WITH =,
        daterange(valid_from, COALESCE(valid_to + 1, 'infinity'::date), '[)') WITH &&
    )
);
CREATE INDEX idx_cutoff_resolution ON channel_cutoff(legal_entity_id, valid_from);

ALTER TABLE channel_cutoff ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON channel_cutoff
    USING (legal_entity_id = ledger_current_entity())
    WITH CHECK (legal_entity_id = ledger_current_entity());
