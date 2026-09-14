-- =====================================================================================
--  Traitement de fin de journee
--
--  Un TFJ est une suite d'etapes ordonnees, chacune idempotente et tracee. Les regles
--  structurantes sont portees par le schema :
--    * un seul TFJ reel par entite et par date comptable    -> index unique partiel
--    * une etape franchie n'est pas rejouee a la reprise     -> statut par etape
--    * un TFJ annule est integralement contre-passe          -> batch_run_id sur l'ecriture
-- =====================================================================================

CREATE TABLE batch_run (
    id               UUID PRIMARY KEY,
    legal_entity_id  UUID NOT NULL REFERENCES legal_entity(id),
    business_date    DATE NOT NULL,
    run_type         TEXT NOT NULL CHECK (run_type IN ('TFJ','TFM','TFT','TFA')),
    mode             TEXT NOT NULL CHECK (mode IN ('REAL','DRY_RUN')),
    status           TEXT NOT NULL CHECK (status IN ('RUNNING','COMPLETED','FAILED','CANCELLED')),
    started_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at      TIMESTAMPTZ,
    started_by       UUID NOT NULL,
    cancelled_by     UUID,
    cancel_reason    TEXT
);

-- Un seul TFJ reel par entite et par date, tant qu'il n'est pas annule. Un TFJ en echec
-- n'ouvre donc pas la porte a un second TFJ concurrent : il se reprend.
CREATE UNIQUE INDEX uq_real_run
    ON batch_run(legal_entity_id, business_date, run_type)
 WHERE mode = 'REAL' AND status <> 'CANCELLED';

CREATE INDEX idx_run_entity_date ON batch_run(legal_entity_id, business_date DESC);

CREATE TABLE batch_step (
    id           UUID PRIMARY KEY,
    run_id       UUID NOT NULL REFERENCES batch_run(id),
    step_order   SMALLINT NOT NULL,
    step_name    TEXT NOT NULL,
    blocking     BOOLEAN NOT NULL,
    status       TEXT NOT NULL DEFAULT 'PENDING'
                 CHECK (status IN ('PENDING','RUNNING','COMPLETED','FAILED')),
    read_count   BIGINT NOT NULL DEFAULT 0,
    write_count  BIGINT NOT NULL DEFAULT 0,
    anomalies    TEXT,
    error_detail TEXT,
    started_at   TIMESTAMPTZ,
    finished_at  TIMESTAMPTZ,
    CONSTRAINT uq_step UNIQUE (run_id, step_order)
);

CREATE INDEX idx_step_run ON batch_step(run_id, step_order);
