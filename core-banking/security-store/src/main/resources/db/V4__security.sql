-- =====================================================================================
--  Piste d'audit des habilitations
--
--  Y figurent tous les refus, sans exception, et les acces reussis aux operations
--  declarees sensibles en lecture. Ce second point est le plus important : un journal
--  limite aux modifications ne voit pas l'agent habilite qui consulte sans motif les
--  comptes d'un tiers, alors que c'est la forme de fraude interne la plus courante.
--
--  En production, cette table est partitionnee par mois, comme journal_entry, avec
--  detachement et archivage au-dela de la fenetre de retention en ligne.
-- =====================================================================================
CREATE TABLE authorization_audit (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Qui
    subject_id       TEXT NOT NULL,
    username         TEXT NOT NULL,
    roles            TEXT NOT NULL,
    caller_entity_id UUID NOT NULL,
    caller_branch_id UUID,

    -- Quoi
    operation        TEXT NOT NULL,
    allowed          BOOLEAN NOT NULL,
    reason           TEXT,

    -- Sur quoi
    target_entity_id UUID NOT NULL,
    target_branch_id UUID,
    amount           NUMERIC(23,5),
    currency         CHAR(3),
    owner_subject_id TEXT,

    correlation_id   UUID
);

CREATE INDEX idx_authz_subject   ON authorization_audit(subject_id, occurred_at DESC);
CREATE INDEX idx_authz_denied    ON authorization_audit(occurred_at DESC) WHERE NOT allowed;
CREATE INDEX idx_authz_operation ON authorization_audit(operation, occurred_at DESC);

-- Reutilise le declencheur d'immuabilite defini avec le journal comptable.
CREATE TRIGGER trg_authz_audit_immutable
    BEFORE UPDATE OR DELETE ON authorization_audit
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
