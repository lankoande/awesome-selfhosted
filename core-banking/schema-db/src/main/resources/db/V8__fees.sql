--liquibase formatted sql
--changeset socle:8 splitStatements:false
--comment fees
-- =====================================================================================
--  Commissions et frais periodiques
--
--  Trois invariants portes par le schema, parce que les porter dans l'applicatif seul
--  laisserait la porte ouverte a une reprise mal ecrite ou a une correction manuelle :
--
--    * une periode n'est jamais facturee deux fois            -> contrainte d'exclusion
--    * une liquidation ne se reecrit pas apres coup           -> declencheur d'immuabilite
--    * une commission encaissee ne redevient pas impayee      -> transitions controlees
-- =====================================================================================

-- -------------------------------------------------------------------------------------
--  Liquidation d'une commission pour une periode.
--
--  La ligne existe meme lorsque rien n'est percu : exoneration commerciale, provision
--  insuffisante, prorata nul. C'est delibere. Une commission non percue qui ne laisse
--  aucune trace est un manque a gagner inconnaissable : les comptes restent equilibres,
--  les controles passent, et la banque ignore ce que lui coutent ses gestes commerciaux.
-- -------------------------------------------------------------------------------------
CREATE TABLE fee_charge (
    id               UUID PRIMARY KEY,
    legal_entity_id  UUID NOT NULL REFERENCES legal_entity(id),
    account_id       UUID NOT NULL REFERENCES account(id),
    fee_code         TEXT NOT NULL,

    period_index     INTEGER NOT NULL CHECK (period_index >= 0),
    period_start     DATE NOT NULL,
    period_end       DATE NOT NULL,
    charge_date      DATE NOT NULL,

    basis_amount     NUMERIC(23,5) NOT NULL,
    gross_amount     NUMERIC(23,5) NOT NULL CHECK (gross_amount >= 0),
    net_amount       NUMERIC(23,5) NOT NULL CHECK (net_amount >= 0),
    tax_amount       NUMERIC(23,5) NOT NULL CHECK (tax_amount >= 0),
    total_amount     NUMERIC(23,5) NOT NULL,
    tax_rate_percent NUMERIC(12,6) NOT NULL,
    charged_days     INTEGER NOT NULL CHECK (charged_days >= 0),
    period_days      INTEGER NOT NULL CHECK (period_days > 0),

    outcome          TEXT NOT NULL CHECK (outcome IN (
                        'COLLECTED','FORCED','WAIVED','DEFERRED','REJECTED','WRITTEN_OFF',
                        'NOT_DUE','CANCELLED')),
    entry_id         UUID,
    batch_run_id     UUID,
    attempts         INTEGER NOT NULL DEFAULT 1 CHECK (attempts >= 1),
    -- Rang de la tentative de facturation de cette periode. Il vaut zero en regime normal et
    -- s'incremente a chaque annulation : voir la note du service sur la cle d'idempotence.
    generation       INTEGER NOT NULL DEFAULT 0 CHECK (generation >= 0),
    settled_on       DATE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_fee_period      CHECK (period_end >= period_start),
    CONSTRAINT ck_fee_days        CHECK (charged_days <= period_days),
    -- Le total n'est jamais arrondi pour lui-meme : il est la somme des montants imputes.
    CONSTRAINT ck_fee_total       CHECK (total_amount = net_amount + tax_amount),
    -- Une commission encaissee a une ecriture ; une commission non percue n'en a aucune.
    CONSTRAINT ck_fee_entry       CHECK (
        CASE WHEN outcome IN ('COLLECTED','FORCED') THEN entry_id IS NOT NULL
             WHEN outcome IN ('WAIVED','REJECTED','NOT_DUE','DEFERRED') THEN entry_id IS NULL
             ELSE TRUE END),

    -- L'invariant central. Deux liquidations ne peuvent pas couvrir un meme jour pour un
    -- meme compte et une meme commission. Une cle unique sur la fin de periode ne
    -- suffirait pas : un changement d'ancrage decale les bornes et laisserait passer un
    -- double prelevement sur des periodes qui se chevauchent sans coincider.
    CONSTRAINT ex_fee_no_overlap EXCLUDE USING gist (
        account_id WITH =,
        fee_code WITH =,
        daterange(period_start, period_end + 1, '[)') WITH &&
    ) WHERE (outcome <> 'CANCELLED')
);

CREATE INDEX idx_fee_charge_account ON fee_charge(account_id, fee_code, period_end DESC);
CREATE INDEX idx_fee_charge_arrears  ON fee_charge(legal_entity_id, charge_date)
    WHERE outcome = 'DEFERRED';
CREATE INDEX idx_fee_charge_run      ON fee_charge(batch_run_id) WHERE batch_run_id IS NOT NULL;

-- -------------------------------------------------------------------------------------
--  Immuabilite de la liquidation, mutabilite du seul denouement.
--
--  Une commission impayee puis encaissee change d'etat, pas de montant. Refixer le montant
--  au moment de l'encaissement reviendrait a facturer au client autre chose que ce qui lui
--  a ete annonce, et a rendre la taxe declaree irreconciliable avec l'assiette d'origine.
-- -------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION guard_fee_charge() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Une liquidation de commission ne se supprime pas : l''annulation passe par l''etat CANCELLED.'
          USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF (NEW.account_id, NEW.fee_code, NEW.period_start, NEW.period_end, NEW.charge_date,
        NEW.basis_amount, NEW.gross_amount, NEW.net_amount, NEW.tax_amount, NEW.total_amount,
        NEW.tax_rate_percent, NEW.charged_days, NEW.period_days, NEW.generation)
       IS DISTINCT FROM
       (OLD.account_id, OLD.fee_code, OLD.period_start, OLD.period_end, OLD.charge_date,
        OLD.basis_amount, OLD.gross_amount, OLD.net_amount, OLD.tax_amount, OLD.total_amount,
        OLD.tax_rate_percent, OLD.charged_days, OLD.period_days, OLD.generation) THEN
        RAISE EXCEPTION 'La liquidation de la commission % du compte % est figee : seul son denouement evolue.',
            OLD.fee_code, OLD.account_id USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    -- Un denouement acquis ne se rouvre pas. Seule l'annulation du traitement qui l'a
    -- produit peut le neutraliser.
    IF OLD.outcome <> 'DEFERRED' AND NEW.outcome <> OLD.outcome AND NEW.outcome <> 'CANCELLED' THEN
        RAISE EXCEPTION 'Denouement % acquis pour la commission % du compte % : transition vers % refusee.',
            OLD.outcome, OLD.fee_code, OLD.account_id, NEW.outcome
          USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    NEW.updated_at := now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_fee_charge_guard
    BEFORE UPDATE OR DELETE ON fee_charge
    FOR EACH ROW EXECUTE FUNCTION guard_fee_charge();

-- -------------------------------------------------------------------------------------
--  Exonerations.
--
--  Renoncer a une commission, c'est renoncer a un produit : l'operation releve du meme
--  regime de double validation qu'un parametrage tarifaire. La contrainte est portee par
--  la base, pas seulement par l'applicatif.
-- -------------------------------------------------------------------------------------
CREATE TABLE account_fee_exemption (
    id          UUID PRIMARY KEY,
    account_id  UUID NOT NULL REFERENCES account(id),
    fee_code    TEXT NOT NULL,
    valid_from  DATE NOT NULL,
    valid_to    DATE,
    reason      TEXT NOT NULL,
    granted_by  UUID NOT NULL,
    approved_by UUID NOT NULL,
    granted_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_exemption_validity CHECK (valid_to IS NULL OR valid_to >= valid_from),
    CONSTRAINT ck_exemption_approval CHECK (approved_by <> granted_by),
    CONSTRAINT ex_exemption_no_overlap EXCLUDE USING gist (
        account_id WITH =,
        fee_code WITH =,
        daterange(valid_from, COALESCE(valid_to + 1, 'infinity'::date), '[)') WITH &&
    )
);

CREATE INDEX idx_exemption_account ON account_fee_exemption(account_id, fee_code, valid_from);
