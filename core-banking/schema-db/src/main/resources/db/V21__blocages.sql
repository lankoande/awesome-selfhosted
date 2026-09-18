--liquibase formatted sql
--changeset socle:21 splitStatements:false
--comment blocages
-- =====================================================================================
--  Blocages de compte, historique de statut, expiration des blocages de montant
--
--  Un blocage de compte — opposition, saisie, gel — prime sur toute operation, y compris
--  les prelevements automatiques des produits : il est donc tenu par le ledger lui-meme,
--  qui refuse la ligne, et non par le service qui la demande. Deux natures : le blocage
--  en debit, qui laisse entrer les fonds ; le blocage total, qui n'accepte plus qu'une
--  operation de la banque elle-meme (interets capitalises, contre-passation).
-- =====================================================================================
CREATE TABLE account_block (
    id          UUID PRIMARY KEY,
    account_id  UUID NOT NULL REFERENCES account(id),
    kind        TEXT NOT NULL CHECK (kind IN ('DEBIT','TOTAL')),
    reason      TEXT NOT NULL,
    reference   TEXT,
    placed_on   DATE NOT NULL,
    placed_by   UUID NOT NULL,
    approved_by UUID NOT NULL,
    lifted_on   DATE,
    lifted_by   UUID,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Un blocage se pose et se leve a deux : c'est le geste par lequel un compte echappe a
    -- une saisie, ou y est soumis a tort.
    CONSTRAINT ck_block_approval CHECK (approved_by <> placed_by),
    CONSTRAINT ck_block_lift CHECK ((lifted_on IS NULL) = (lifted_by IS NULL))
);

CREATE INDEX idx_block_active ON account_block(account_id) WHERE lifted_on IS NULL;

-- -------------------------------------------------------------------------------------
--  Transitions de statut d'un compte : ouverture, blocage, dormance, reactivation,
--  cloture. Le statut courant est sur le compte ; l'historique est ici, avec le
--  traitement qui l'a produit lorsque c'est un arrete — pour que son annulation le defasse.
-- -------------------------------------------------------------------------------------
CREATE TABLE account_event (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id   UUID NOT NULL REFERENCES account(id),
    kind         TEXT NOT NULL CHECK (kind IN ('OPENED','BLOCKED','UNBLOCKED','DORMANT',
                                                'REACTIVATED','CLOSED')),
    occurred_on  DATE NOT NULL,
    actor_id     UUID NOT NULL,
    approver_id  UUID,
    detail       TEXT,
    batch_run_id UUID,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_account_event ON account_event(account_id, occurred_on);
CREATE INDEX idx_account_event_run ON account_event(batch_run_id) WHERE batch_run_id IS NOT NULL;

-- -------------------------------------------------------------------------------------
--  Un blocage de montant expire par date comptable, pas par horloge : c'est l'arrete de
--  la journee qui le leve, et son annulation le repose.
-- -------------------------------------------------------------------------------------
ALTER TABLE account_hold ADD COLUMN expires_on      DATE;
ALTER TABLE account_hold ADD COLUMN placed_on       DATE;
ALTER TABLE account_hold ADD COLUMN placed_by       UUID;
ALTER TABLE account_hold ADD COLUMN released_on     DATE;
ALTER TABLE account_hold ADD COLUMN released_by     UUID;
ALTER TABLE account_hold ADD COLUMN released_run_id UUID;

UPDATE account_hold SET expires_on = (expires_at AT TIME ZONE 'UTC')::date
 WHERE expires_at IS NOT NULL AND expires_on IS NULL;

CREATE INDEX idx_hold_expiry ON account_hold(expires_on) WHERE released_at IS NULL;
CREATE INDEX idx_hold_released_run ON account_hold(released_run_id) WHERE released_run_id IS NOT NULL;

CREATE OR REPLACE FUNCTION available_balance(p_account UUID, p_as_of DATE)
RETURNS NUMERIC AS $$
    SELECT COALESCE((SELECT SUM(balance) FROM account_balance WHERE account_id = p_account), 0)
         - COALESCE((SELECT SUM(amount) FROM account_hold
                      WHERE account_id = p_account
                        AND released_at IS NULL
                        AND (expires_on IS NULL OR expires_on >= p_as_of)), 0)
         + COALESCE((SELECT amount FROM overdraft_limit
                      WHERE account_id = p_account
                        AND valid_from <= p_as_of
                        AND (valid_to IS NULL OR valid_to >= p_as_of)
                      ORDER BY valid_from DESC LIMIT 1), 0);
$$ LANGUAGE sql STABLE;
