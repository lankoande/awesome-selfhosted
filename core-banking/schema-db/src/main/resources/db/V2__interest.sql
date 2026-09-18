--liquibase formatted sql
--changeset socle:2 splitStatements:false
--comment interest
-- =====================================================================================
--  Interets courus
--
--  Chaque journee de valeur produit une ligne, conservee en precision interne. La ligne
--  n'est jamais modifiee : un recalcul retroactif la passe en REVERSED et en cree une
--  nouvelle generation. L'historique des calculs successifs reste donc integralement
--  reconstituable — ce que demande toute reclamation sur des agios.
-- =====================================================================================
CREATE TABLE interest_accrual (
    id                 UUID PRIMARY KEY,
    account_id         UUID NOT NULL REFERENCES account(id),
    accrual_date       DATE NOT NULL,
    side               TEXT NOT NULL CHECK (side IN ('CREDITOR','DEBTOR')),
    generation         INT  NOT NULL DEFAULT 1,

    -- Composantes du calcul, conservees pour l'explicabilite.
    basis_balance      NUMERIC(23,5)  NOT NULL,
    effective_rate     NUMERIC(12,6)  NOT NULL,
    year_fraction      NUMERIC(24,18) NOT NULL,

    -- Montant exact de la journee, et cumul exact depuis l'origine du calcul.
    precise_amount     NUMERIC(23,5) NOT NULL,
    cumulative_precise NUMERIC(23,5) NOT NULL,

    -- Montant reellement impute au journal ce jour-la : l'ecart entre le cumul arrondi
    -- et ce qui etait deja impute. Nul la plupart des jours en devise sans subdivision.
    posted_delta       NUMERIC(23,5) NOT NULL DEFAULT 0,
    entry_id           UUID,
    booking_date       DATE,

    -- Traitement qui a produit la ligne. C'est ce qui permet d'annuler un TFJ integralement :
    -- contre-passer ses ecritures sans neutraliser les journees calculees laisserait le moteur
    -- croire ces journees deja remunerees, et elles ne le seraient plus jamais.
    batch_run_id       UUID,

    status             TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','REVERSED')),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Une seule journee active par compte et par cote : les generations anterieures sont
-- conservees mais neutralisees.
CREATE UNIQUE INDEX uq_accrual_active
    ON interest_accrual(account_id, accrual_date, side) WHERE status = 'ACTIVE';

CREATE INDEX idx_accrual_account ON interest_accrual(account_id, side, accrual_date);
CREATE INDEX idx_accrual_run     ON interest_accrual(batch_run_id) WHERE batch_run_id IS NOT NULL;
