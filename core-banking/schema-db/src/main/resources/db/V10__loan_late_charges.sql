--liquibase formatted sql
--changeset socle:10 splitStatements:false
--comment loan late charges
-- =====================================================================================
--  Interets de retard et penalites
--
--  Une creance d'interet de retard grossit chaque jour, contrairement a toutes les
--  autres. Le declencheur de V9 interdisait a une creance de remonter ; cette regle
--  reste juste, mais elle doit distinguer le cas ou le montant du augmente reellement
--  de celui ou une part deja reglee redeviendrait due.
-- =====================================================================================

-- Une creance annulee tombe a zero. La contrainte de V9 exigeait un montant strictement
-- positif, ce qui est juste d'une creance vivante et faux d'une creance neutralisee : la
-- reprise d'un accrual annule laisserait sinon une ligne a un franc, invisible mais fausse.
ALTER TABLE loan_receivable DROP CONSTRAINT loan_receivable_original_amount_check;
ALTER TABLE loan_receivable ADD CONSTRAINT ck_receivable_amount
    CHECK (original_amount > 0 OR cancelled);

CREATE OR REPLACE FUNCTION guard_loan_receivable() RETURNS trigger AS $$
DECLARE
    accrued NUMERIC(23,5);
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Une creance ne se supprime pas : l''annulation passe par le drapeau cancelled.'
          USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF (NEW.contract_id, NEW.category, NEW.due_date, NEW.instalment_number)
       IS DISTINCT FROM
       (OLD.contract_id, OLD.category, OLD.due_date, OLD.instalment_number) THEN
        RAISE EXCEPTION 'La creance % est figee : seul son solde evolue.', OLD.id
          USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    accrued := NEW.original_amount - OLD.original_amount;

    IF accrued <> 0 AND NOT NEW.cancelled THEN
        -- Seul l'interet de retard court. Toute autre creance a un montant fixe des sa
        -- naissance : le voir bouger revele une reecriture de l'histoire.
        IF OLD.category <> 'LATE_INTEREST' THEN
            RAISE EXCEPTION 'Le montant de la creance % (%) est fige : il ne court pas.',
                OLD.id, OLD.category USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        -- Le solde bouge exactement comme le montant du, dans les deux sens. Sans cette
        -- egalite, un accrual ferait redevenir due une part deja reglee — le client paierait
        -- deux fois la meme journee — et une reprise effacerait une part encore due.
        --
        -- La reprise est licite dans un seul cas, celui de l'annulation du traitement qui a
        -- produit l'accrual : ses ecritures sont contre-passees, ses journees neutralisees, et
        -- la creance doit revenir a ce qu'elle etait. Aucun autre chemin du code ne la
        -- diminue, et la contrainte de non-negativite empeche de reprendre plus qu'il n'a
        -- ete couru.
        IF NEW.outstanding - OLD.outstanding <> accrued THEN
            RAISE EXCEPTION 'Variation de % du montant du de la creance % pour un solde qui varie de % : une part deja reglee redeviendrait due, ou une part encore due serait effacee.',
                accrued, OLD.id, NEW.outstanding - OLD.outstanding
              USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    ELSIF NEW.outstanding > OLD.outstanding AND NOT NEW.cancelled THEN
        RAISE EXCEPTION 'Le solde de la creance % passerait de % a % : une creance ne remonte pas.',
            OLD.id, OLD.outstanding, NEW.outstanding
          USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- -------------------------------------------------------------------------------------
--  Journees d'interet de retard.
--
--  Meme forme que les interets courus, et pour la meme raison : le cumul est tenu en
--  precision interne, et seule la difference entre son arrondi et ce qui a deja ete
--  impute devient une ecriture. Un arrondi quotidien perdrait quelques francs par jour
--  et par contrat, de facon systematique et toujours dans le meme sens.
-- -------------------------------------------------------------------------------------
CREATE TABLE loan_late_accrual (
    id                  UUID PRIMARY KEY,
    contract_id         UUID NOT NULL REFERENCES loan_contract(id),
    accrual_date        DATE NOT NULL,

    basis_amount        NUMERIC(23,5)  NOT NULL,
    annual_rate_percent NUMERIC(12,6)  NOT NULL,
    year_fraction       NUMERIC(24,18) NOT NULL,
    precise_amount      NUMERIC(23,5)  NOT NULL,
    cumulative_precise  NUMERIC(23,5)  NOT NULL,
    posted_delta        NUMERIC(23,5)  NOT NULL DEFAULT 0,

    entry_id            UUID,
    booking_date        DATE,
    batch_run_id        UUID,
    status              TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','REVERSED')),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_late_accrual_active
    ON loan_late_accrual(contract_id, accrual_date) WHERE status = 'ACTIVE';

CREATE INDEX idx_late_accrual_contract ON loan_late_accrual(contract_id, accrual_date);
CREATE INDEX idx_late_accrual_run      ON loan_late_accrual(batch_run_id)
    WHERE batch_run_id IS NOT NULL;

-- Les penalites sont percues une fois par echeance : l'index unique partiel des creances
-- de V9 suffit a le garantir, la categorie PENALTIES portant le rang de l'echeance.
--
-- L'interet de retard, lui, n'appartient a aucune echeance en particulier : il court sur
-- l'impaye du contrat. Une seule creance par contrat, donc, et l'index le garantit — deux
-- creances d'interet de retard rendraient le cumul dependant de celle qui est lue.
CREATE UNIQUE INDEX uq_late_interest_per_contract
    ON loan_receivable(contract_id) WHERE category = 'LATE_INTEREST' AND NOT cancelled;
