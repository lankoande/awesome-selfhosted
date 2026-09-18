--liquibase formatted sql
--changeset socle:15 splitStatements:false
--comment tranches
-- =====================================================================================
--  Deblocage echelonne : tranches, mobilisation, interets intercalaires
--
--  Un credit de construction, de campagne ou d'equipement ne verse pas tout a la
--  signature : les fonds suivent l'avancement. Deux consequences que le modele a un seul
--  deblocage ne sait pas porter, et qui ne se rattrapent pas apres coup :
--
--    * les interets ne courent que sur le montant mobilise, jour par jour ;
--    * l'echeancier definitif n'existe qu'a la cloture de la mobilisation, quand le
--      capital reellement tire est connu.
--
--  Le contournement habituel — debloquer la totalite sur un compte d'attente — laisse une
--  comptabilite equilibree et fait payer a l'emprunteur des fonds qu'il n'a pas recus.
-- =====================================================================================

-- -------------------------------------------------------------------------------------
--  Phase de mobilisation.
--
--  Une table a part, et non des colonnes sur le contrat : pendant la mobilisation, la duree
--  et la premiere echeance n'ont pas encore d'echeancier ou vivre, et le contrat n'est pas
--  fait pour les porter. La ligne subsiste apres la cloture, comme trace de ce qui a ete
--  accorde face a ce qui a ete tire.
--
--  Ce qui n'y figure pas : le montant mobilise. Il se lit sur les tranches debloquees. Le
--  denormaliser ferait exister deux verites sur le capital, et la plus fausse des deux
--  serait celle qui commande l'echeancier.
-- -------------------------------------------------------------------------------------
CREATE TABLE loan_mobilisation (
    contract_id            UUID PRIMARY KEY REFERENCES loan_contract(id),
    drawdown_deadline      DATE NOT NULL,
    instalment_count       INTEGER NOT NULL CHECK (instalment_count > 0),
    grace_instalments      INTEGER NOT NULL DEFAULT 0 CHECK (grace_instalments >= 0),
    first_due_date         DATE NOT NULL,
    upfront_fees           NUMERIC(23,5) NOT NULL DEFAULT 0 CHECK (upfront_fees >= 0),

    -- Dernier jour couvert par des interets intercalaires factures. C'est lui qui garantit
    -- qu'aucune journee n'est facturee deux fois ni oubliee, y compris sur un TFJ rejoue.
    interim_billed_through DATE NOT NULL,

    closed_on              DATE,
    -- Traitement qui a clos la mobilisation. Son annulation doit rouvrir la periode : sans
    -- cette trace, un TFJ annule laisserait un echeancier definitif arrete sur un capital
    -- dont les ecritures viennent d'etre contre-passees.
    closed_run_id          UUID,
    opened_by              UUID NOT NULL,
    approved_by            UUID NOT NULL,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Debloquer apres le debut de l'amortissement obligerait a rouvrir des echeances deja
    -- rendues exigibles, et les reclamerait deux fois.
    CONSTRAINT ck_mobilisation_window CHECK (drawdown_deadline < first_due_date),
    CONSTRAINT ck_mobilisation_grace CHECK (grace_instalments < instalment_count),
    -- L'echeancier definitif prend effet le lendemain de la cloture : une mobilisation
    -- close la veille de la premiere echeance ne laisserait aucun jour a la premiere
    -- periode d'amortissement.
    CONSTRAINT ck_mobilisation_close CHECK (closed_on IS NULL OR closed_on < first_due_date - 1),
    -- Ouvrir une ligne de mobilisation engage la banque a mettre des fonds a disposition :
    -- meme regime de double validation qu'un deblocage.
    CONSTRAINT ck_mobilisation_approval CHECK (approved_by <> opened_by)
);

CREATE INDEX idx_mobilisation_open ON loan_mobilisation(drawdown_deadline)
    WHERE closed_on IS NULL;

-- -------------------------------------------------------------------------------------
--  Plan de deblocage.
--
--  planned_amount est fige : c'est l'engagement. released_amount enregistre ce qui a
--  reellement ete verse, et peut lui etre inferieur — une tranche se debloque a hauteur de
--  l'avancement constate. Reecrire le montant prevu ferait disparaitre l'ecart, et avec lui
--  la trace de l'engagement non tenu.
-- -------------------------------------------------------------------------------------
CREATE TABLE loan_tranche (
    id                  UUID PRIMARY KEY,
    contract_id         UUID NOT NULL REFERENCES loan_contract(id),
    number              INTEGER NOT NULL CHECK (number >= 1),
    planned_on          DATE NOT NULL,
    planned_amount      NUMERIC(23,5) NOT NULL CHECK (planned_amount > 0),
    condition_label     TEXT,
    status              TEXT NOT NULL DEFAULT 'PLANNED'
                        CHECK (status IN ('PLANNED','RELEASED','CANCELLED')),
    released_on         DATE,
    released_amount     NUMERIC(23,5),
    entry_id            UUID,
    released_by         UUID,
    approved_by         UUID,
    cancelled_on        DATE,
    cancellation_reason TEXT,
    cancelled_run_id    UUID,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_tranche_number UNIQUE (contract_id, number),
    CONSTRAINT ck_tranche_released CHECK (
        (status = 'RELEASED') = (released_on IS NOT NULL AND released_amount IS NOT NULL)),
    CONSTRAINT ck_tranche_release_amount CHECK (
        released_amount IS NULL
        OR (released_amount > 0 AND released_amount <= planned_amount)),
    CONSTRAINT ck_tranche_cancelled CHECK ((status = 'CANCELLED') = (cancelled_on IS NOT NULL)),
    -- Mettre des fonds a disposition engage la banque : le decideur n'est pas celui qui saisit.
    CONSTRAINT ck_tranche_approval CHECK (
        status <> 'RELEASED' OR (approved_by IS NOT NULL AND approved_by <> released_by))
);

CREATE INDEX idx_tranche_contract ON loan_tranche(contract_id, number);

-- La somme des tranches fait le capital accorde. La verification est differee a la fin de
-- la transaction : un plan s'insere tranche par tranche, et le controle n'a de sens qu'une
-- fois le plan complet. Sans elle, l'ecart entre le plan et le contrat serait debloque hors
-- plan ou perdu pour l'emprunteur, sans qu'aucune ecriture ne le signale.
CREATE OR REPLACE FUNCTION check_tranche_total() RETURNS trigger AS $$
DECLARE
    cible   UUID := COALESCE(NEW.contract_id, OLD.contract_id);
    total   NUMERIC(23,5);
    accorde NUMERIC(23,5);
BEGIN
    SELECT COALESCE(SUM(planned_amount), 0) INTO total
      FROM loan_tranche WHERE contract_id = cible;
    SELECT principal INTO accorde FROM loan_contract WHERE id = cible;
    IF accorde IS NOT NULL AND total <> accorde THEN
        RAISE EXCEPTION
            'Les tranches du credit % totalisent % pour un capital accorde de % : l''ecart serait debloque hors plan ou perdu.',
            cible, total, accorde
          USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_tranche_total
    AFTER INSERT OR UPDATE OR DELETE ON loan_tranche
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_tranche_total();

-- Une tranche debloquee est figee : les fonds sont verses, et le versement ne se defait pas
-- par une mise a jour. Le corriger passe par une contre-passation comptable, pas par une
-- reecriture de l'etat.
CREATE OR REPLACE FUNCTION guard_loan_tranche() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Une tranche ne se supprime pas : elle s''annule.'
          USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF OLD.status = 'RELEASED' THEN
        RAISE EXCEPTION 'La tranche % est debloquee : les fonds sont verses et l''etat ne se reecrit pas.',
            OLD.number USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF (NEW.contract_id, NEW.number, NEW.planned_amount, NEW.planned_on)
       IS DISTINCT FROM (OLD.contract_id, OLD.number, OLD.planned_amount, OLD.planned_on) THEN
        RAISE EXCEPTION 'Le plan de deblocage est fige : seul l''etat de la tranche % evolue.',
            OLD.number USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_loan_tranche_guard
    BEFORE UPDATE OR DELETE ON loan_tranche
    FOR EACH ROW EXECUTE FUNCTION guard_loan_tranche();

-- -------------------------------------------------------------------------------------
--  Interets intercalaires.
--
--  Une ligne par periode facturee, avec l'assiette qui l'a produite. Sans elle, un
--  emprunteur qui conteste le montant n'a rien a opposer : l'assiette change a chaque
--  deblocage et ne se reconstitue pas a partir du seul encours final.
-- -------------------------------------------------------------------------------------
CREATE TABLE loan_interim_interest (
    id            UUID PRIMARY KEY,
    contract_id   UUID NOT NULL REFERENCES loan_contract(id),
    period_start  DATE NOT NULL,
    period_end    DATE NOT NULL,
    drawn_at_end  NUMERIC(23,5) NOT NULL CHECK (drawn_at_end > 0),
    interest      NUMERIC(23,5) NOT NULL CHECK (interest > 0),
    tax           NUMERIC(23,5) NOT NULL DEFAULT 0 CHECK (tax >= 0),
    receivable_id UUID REFERENCES loan_receivable(id),
    entry_id      UUID,
    batch_run_id  UUID,
    status        TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','REVERSED')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_interim_period CHECK (period_end >= period_start)
);

-- Une periode intercalaire n'est facturee qu'une fois. L'index ne tient pas compte des
-- lignes reprises : l'annulation d'un TFJ doit laisser la periode a nouveau facturable,
-- faute de quoi les interets de la journee seraient perdus definitivement.
CREATE UNIQUE INDEX uq_interim_period
    ON loan_interim_interest(contract_id, period_end) WHERE status = 'ACTIVE';

CREATE INDEX idx_interim_run ON loan_interim_interest(batch_run_id) WHERE status = 'ACTIVE';

-- -------------------------------------------------------------------------------------
--  L'echeancier issu d'une mobilisation n'est pas un echeancier initial : il est arrete
--  sur le capital reellement tire, a une date que le contrat ne fixait pas. Les confondre
--  rendrait illisible la difference entre un credit verse en une fois et un credit
--  mobilise en plusieurs.
-- -------------------------------------------------------------------------------------
ALTER TABLE loan_schedule DROP CONSTRAINT loan_schedule_reason_check;
ALTER TABLE loan_schedule ADD CONSTRAINT ck_schedule_reason
    CHECK (reason IN ('INITIAL','RESCHEDULING','EARLY_REPAYMENT','RATE_REVISION','MOBILISATION'));

-- Traitement qui a publie une version d'echeancier. Sans elle, l'annulation d'un TFJ qui a
-- clos une mobilisation ne saurait pas quelle version retirer, et laisserait en vigueur un
-- echeancier que plus aucune ecriture ne justifie.
ALTER TABLE loan_schedule ADD COLUMN created_run_id UUID;
