-- =====================================================================================
--  Caisses par guichetier et arrete de caisse
--
--  Une caisse est un compte interne d'agence tenu par un guichetier. Le guichetier ne choisit
--  pas sa caisse : elle est la sienne, et c'est l'API qui la resout depuis son identite. La
--  journee de caisse se termine par un arrete : comptage des especes, confrontation au solde
--  comptable, ecart constate et comptabilise sur le compte d'ecarts de la caisse — jamais
--  ajuste en silence. Une caisse mouvementee dans la journee et non arretee bloque l'arrete
--  de la banque (PRE_CHECKS) ; une caisse arretee ne sert plus ce jour-la.
-- =====================================================================================
CREATE TABLE till (
    id                    UUID PRIMARY KEY,
    legal_entity_id       UUID NOT NULL REFERENCES legal_entity(id),
    branch_id             UUID NOT NULL REFERENCES branch(id),
    code                  TEXT NOT NULL,
    cash_account_id       UUID NOT NULL REFERENCES account(id),
    teller_subject_id     TEXT,
    difference_account_id UUID REFERENCES account(id),
    status                TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','CLOSED')),
    created_by            UUID NOT NULL,
    approved_by           UUID NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_till_code UNIQUE (legal_entity_id, code),
    -- Un compte de caisse n'appartient qu'a une caisse : deux caisses sur un meme compte
    -- rendraient l'arrete de l'une faux des que l'autre sert.
    CONSTRAINT uq_till_cash_account UNIQUE (cash_account_id),
    -- Une caisse se cree a deux : elle affecte un compte de la banque a une personne.
    CONSTRAINT ck_till_approval CHECK (approved_by <> created_by)
);

-- Un guichetier ne tient qu'une caisse active a la fois : la sienne se resout sans ambiguite.
CREATE UNIQUE INDEX uq_till_teller ON till(legal_entity_id, teller_subject_id)
    WHERE status = 'ACTIVE' AND teller_subject_id IS NOT NULL;

CREATE TABLE till_closure (
    id            UUID PRIMARY KEY,
    till_id       UUID NOT NULL REFERENCES till(id),
    business_date DATE NOT NULL,
    counted       NUMERIC(23,5) NOT NULL CHECK (counted >= 0),
    book          NUMERIC(23,5) NOT NULL,
    difference    NUMERIC(23,5) NOT NULL,
    entry_id      UUID,
    closed_by     UUID NOT NULL,
    closed_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Une journee de caisse ne s'arrete qu'une fois.
    CONSTRAINT uq_till_closure UNIQUE (till_id, business_date),
    CONSTRAINT ck_till_closure_difference CHECK (difference = counted - book)
);

-- Un arrete est une piece : il ne se modifie ni ne s'efface. Un arrete faux se corrige par une
-- ecriture, sur la journee suivante, jamais en reecrivant la piece.
CREATE OR REPLACE FUNCTION forbid_till_closure_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Un arrete de caisse est immuable : % interdit.', TG_OP
      USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_till_closure_immutable
    BEFORE UPDATE OR DELETE ON till_closure
    FOR EACH ROW EXECUTE FUNCTION forbid_till_closure_mutation();

-- Row Level Security par entite, comme pour toute table a entite.
ALTER TABLE till ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON till
    USING (legal_entity_id = ledger_current_entity())
    WITH CHECK (legal_entity_id = ledger_current_entity());

ALTER TABLE till_closure ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON till_closure
    USING (EXISTS (SELECT 1 FROM till t WHERE t.id = till_closure.till_id))
    WITH CHECK (EXISTS (SELECT 1 FROM till t WHERE t.id = till_closure.till_id));
