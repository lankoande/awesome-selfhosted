-- =====================================================================================
--  Suspens : ce qui attend le correspondant, et depuis combien de jours ouvres
--
--  Un ordre de paiement non regle, une remise non encaissee, un prelevement non regle, un
--  compte d'attente non solde sont des suspens. Chacun a une anciennete en jours ouvres et un
--  responsable ; la politique par nature dit au-dela de combien de jours le suspens est en
--  retard. La revue de l'arrete les remonte, et un compte d'attente en retard bloque la
--  journee. Validee a deux, datee comme une condition de banque.
-- =====================================================================================
CREATE TABLE suspense_policy (
    id                UUID PRIMARY KEY,
    legal_entity_id   UUID NOT NULL REFERENCES legal_entity(id),
    kind              TEXT NOT NULL CHECK (kind IN (
        'SUSPENSE_ACCOUNT','PAYMENT_ORDER','CHEQUE_DEPOSIT','DIRECT_DEBIT')),
    max_business_days INTEGER NOT NULL CHECK (max_business_days >= 0),
    owner             TEXT NOT NULL,
    valid_from        DATE NOT NULL,
    valid_to          DATE,
    created_by        UUID NOT NULL,
    approved_by       UUID NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_suspense_validity CHECK (valid_to IS NULL OR valid_to >= valid_from),
    CONSTRAINT ck_suspense_approval CHECK (approved_by <> created_by),
    CONSTRAINT ex_suspense_no_overlap EXCLUDE USING gist (
        legal_entity_id WITH =,
        kind WITH =,
        daterange(valid_from, COALESCE(valid_to + 1, 'infinity'::date), '[)') WITH &&
    )
);

ALTER TABLE suspense_policy ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON suspense_policy
    USING (legal_entity_id = ledger_current_entity())
    WITH CHECK (legal_entity_id = ledger_current_entity());
