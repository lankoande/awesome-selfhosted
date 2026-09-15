-- =====================================================================================
--  Maker-checker : operations en attente de double validation
--
--  Une operation que la politique soumet a un second regard n'est pas executee par celui
--  qui la saisit : elle attend, avec sa requete telle que recue, qu'un autre porteur
--  habilite l'approuve ou la rejette. L'approbateur est le sujet du jeton du checker —
--  jamais un identifiant que le maker aurait fourni. Le maker ne peut pas etre son propre
--  checker : la politique le refuse, et la base aussi.
-- =====================================================================================
CREATE TABLE pending_operation (
    id               UUID PRIMARY KEY,
    legal_entity_id  UUID NOT NULL REFERENCES legal_entity(id),
    operation        TEXT NOT NULL,            -- operation du catalogue
    handler          TEXT NOT NULL,            -- cas d'usage qui saura l'executer
    resource         TEXT,                     -- objet vise, pour la lecture (compte, tiers)
    payload          JSONB NOT NULL,           -- la requete telle que recue
    amount           NUMERIC(23,5),
    currency         CHAR(3),
    status           TEXT NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','APPROVED','REJECTED','EXPIRED','EXECUTED','FAILED')),
    maker_id         TEXT NOT NULL,
    maker_username   TEXT NOT NULL,
    maker_branch_id  UUID,
    made_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at       TIMESTAMPTZ NOT NULL,
    decided_by       TEXT,
    decided_at       TIMESTAMPTZ,
    decision_reason  TEXT,
    result           JSONB,
    error            TEXT,
    executed_at      TIMESTAMPTZ,
    CONSTRAINT ck_pending_expiry CHECK (expires_at > made_at)
);

CREATE INDEX idx_pending_entity_status ON pending_operation(legal_entity_id, status, made_at);
CREATE INDEX idx_pending_maker ON pending_operation(maker_id, made_at DESC);

CREATE TABLE operation_approval (
    id               UUID PRIMARY KEY,
    operation_id     UUID NOT NULL REFERENCES pending_operation(id),
    checker_id       TEXT NOT NULL,
    checker_username TEXT NOT NULL,
    decision         TEXT NOT NULL CHECK (decision IN ('APPROVED','REJECTED')),
    reason           TEXT,
    decided_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_one_decision_per_checker UNIQUE (operation_id, checker_id)
);

-- Le maker ne peut pas etre son propre checker : contrainte de base, en plus de la politique.
CREATE OR REPLACE FUNCTION check_segregation() RETURNS trigger AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM pending_operation p
                WHERE p.id = NEW.operation_id AND p.maker_id = NEW.checker_id) THEN
        RAISE EXCEPTION 'Separation des taches : le maker ne peut pas valider sa propre operation'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_segregation BEFORE INSERT ON operation_approval
    FOR EACH ROW EXECUTE FUNCTION check_segregation();

-- Une decision ne se modifie pas et ne s'efface pas.
CREATE TRIGGER trg_approval_immutable
    BEFORE UPDATE OR DELETE ON operation_approval
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
