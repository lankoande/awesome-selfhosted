-- =====================================================================================
--  Interets courus non echus sur credits
--
--  L'interet d'un credit n'etait constate qu'a l'echeance : entre deux echeances, produit et
--  actif etaient sous-evalues d'un mois d'interets au plus, et le resultat mensuel etait faux.
--  L'interet contractuel de chaque echeance est desormais etale lineairement sur les jours
--  de sa periode et constate chaque nuit, par le meme mecanisme de cumul arrondi que les
--  interets sur depots. A l'echeance, la creance reprend les courus : le produit ne bouge
--  plus, il a ete reconnu jour apres jour.
--
--  Une ligne par echeance et par journee, partitionnee par mois ; le cumul impute par
--  echeance est la somme de ses lignes actives, au plus une trentaine.
-- =====================================================================================
CREATE TABLE loan_interest_accrual (
    id                  UUID NOT NULL,
    contract_id         UUID NOT NULL REFERENCES loan_contract(id),
    schedule_id         UUID NOT NULL REFERENCES loan_schedule(id) ON DELETE CASCADE,
    instalment_number   INTEGER NOT NULL CHECK (instalment_number >= 1),
    accrual_date        DATE NOT NULL,

    -- Etalement : jours de la periode, jours ecoules a cette journee, interet de l'echeance.
    period_days         INTEGER NOT NULL CHECK (period_days > 0),
    elapsed_days        INTEGER NOT NULL CHECK (elapsed_days >= 0),
    instalment_interest NUMERIC(23,5) NOT NULL CHECK (instalment_interest >= 0),

    -- Cumul exact a cette journee, et ecart impute ce jour-la.
    cumulative_precise  NUMERIC(23,5) NOT NULL,
    posted_delta        NUMERIC(23,5) NOT NULL DEFAULT 0,

    -- Compte de courus impute, et cote du produit : resultat, ou interets reserves lorsque
    -- le credit est sous suspension. Le drapeau bascule aussi a la suspension, qui sort du
    -- resultat ce qui y avait ete constate.
    accrued_account_id  UUID NOT NULL REFERENCES account(id),
    reserved            BOOLEAN NOT NULL DEFAULT FALSE,
    reserved_run_id     UUID,

    entry_id            UUID,
    booking_date        DATE,
    batch_run_id        UUID,
    status              TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','REVERSED')),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id, accrual_date)
) PARTITION BY RANGE (accrual_date);

INSERT INTO ledger_partitioned_table(table_name, date_column)
VALUES ('loan_interest_accrual', 'accrual_date');

CREATE UNIQUE INDEX uq_loan_accrual_active
    ON loan_interest_accrual(schedule_id, instalment_number, accrual_date) WHERE status = 'ACTIVE';
CREATE INDEX idx_loan_accrual_line
    ON loan_interest_accrual(schedule_id, instalment_number) WHERE status = 'ACTIVE';
CREATE INDEX idx_loan_accrual_contract
    ON loan_interest_accrual(contract_id) WHERE status = 'ACTIVE';
CREATE INDEX idx_loan_accrual_run
    ON loan_interest_accrual(batch_run_id) WHERE batch_run_id IS NOT NULL;
CREATE INDEX idx_loan_accrual_reserved_run
    ON loan_interest_accrual(reserved_run_id) WHERE reserved_run_id IS NOT NULL;

-- Les echeances en cours — commencees, pas encore reclamees — sont celles que l'accrual et la
-- reconciliation parcourent chaque nuit.
CREATE INDEX idx_schedule_line_in_progress
    ON loan_schedule_line(period_start) WHERE made_due_on IS NULL;
