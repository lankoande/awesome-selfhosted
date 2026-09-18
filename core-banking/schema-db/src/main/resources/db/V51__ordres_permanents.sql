--liquibase formatted sql
--changeset socle:51 splitStatements:false
--comment ordres permanents
-- =====================================================================================
--  Ordres permanents : le virement que le client programme une fois
--
--  Loyer, epargne mensuelle, pension : l'ordre est celui du client, pas celui d'un tiers.
--  Trois consequences, portees ici et par le service :
--
--    * il consomme ses plafonds — contrairement au cheque, qui est l'instrument d'un porteur,
--      et au prelevement, qui est l'engagement pris envers un creancier ;
--    * son echec n'est pas une anomalie de la banque : il se retente un nombre borne de fois,
--      puis l'echeance est abandonnee. La reporter indefiniment ferait partir deux loyers le
--      meme mois, ce qu'aucun client n'a demande ;
--    * vers l'exterieur, il ne comptabilise pas lui-meme : il depose un ordre de paiement, qui
--      suivra son cycle (envoi, reglement sur le nostro, retour). Deux chemins pour sortir de
--      l'argent seraient deux verites sur le meme sujet.
-- =====================================================================================
CREATE TABLE standing_order (
    id                    UUID PRIMARY KEY,
    legal_entity_id       UUID NOT NULL REFERENCES legal_entity(id),
    account_id            UUID NOT NULL REFERENCES account(id),
    reference             TEXT NOT NULL,

    -- FIXED : un montant. SWEEP : tout ce qui depasse un plancher — l'epargne automatique,
    -- ou le balayage d'un compte d'exploitation vers un compte de placement.
    kind                  TEXT NOT NULL CHECK (kind IN ('FIXED','SWEEP')),
    amount                NUMERIC(23,5) CHECK (amount IS NULL OR amount > 0),
    floor_amount          NUMERIC(23,5) CHECK (floor_amount IS NULL OR floor_amount >= 0),

    -- Le beneficiaire : un compte de l'entite, ou un tiers d'une autre banque.
    beneficiary_account_id UUID REFERENCES account(id),
    beneficiary_name      TEXT,
    beneficiary_bank      TEXT,
    beneficiary_account   TEXT,

    frequency             TEXT NOT NULL CHECK (frequency IN ('MONTHLY','QUARTERLY','SEMIANNUAL',
                                                             'ANNUAL')),
    start_date            DATE NOT NULL,
    end_date              DATE,
    -- Le rang de l'echeance en cours depuis l'ancrage, et sa date. Les echeances se calculent
    -- depuis la date de debut, jamais de proche en proche : une echeance au 31 ramenee au 28 en
    -- fevrier resterait au 28 ensuite, et l'ordre changerait de jour sans que personne ne l'ait
    -- decide.
    occurrence            INTEGER NOT NULL DEFAULT 0 CHECK (occurrence >= 0),
    occurrences           INTEGER CHECK (occurrences IS NULL OR occurrences > 0),
    due_date              DATE NOT NULL,
    -- Quand la prochaine tentative aura lieu : l'echeance, elle, ne bouge pas.
    next_attempt_date     DATE NOT NULL,
    -- Tentatives de l'echeance en cours, et ce que la banque s'autorise avant d'abandonner.
    attempts              INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    max_attempts          INTEGER NOT NULL DEFAULT 3 CHECK (max_attempts >= 1),

    status                TEXT NOT NULL DEFAULT 'ACTIVE'
                          CHECK (status IN ('ACTIVE','COMPLETED','CANCELLED')),
    narrative             TEXT,
    cancelled_on          DATE,
    cancel_reason         TEXT,
    created_by            UUID NOT NULL,
    approved_by           UUID NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_standing_order_reference UNIQUE (legal_entity_id, reference),
    CONSTRAINT ck_standing_order_approval CHECK (approved_by <> created_by),
    CONSTRAINT ck_standing_order_dates CHECK (end_date IS NULL OR end_date >= start_date),
    -- Un montant fixe porte son montant ; un balayage porte son plancher.
    CONSTRAINT ck_standing_order_amount CHECK (
        (kind = 'FIXED' AND amount IS NOT NULL)
        OR (kind = 'SWEEP' AND amount IS NULL AND floor_amount IS NOT NULL)),
    -- Un beneficiaire, et un seul : interne ou d'ailleurs.
    CONSTRAINT ck_standing_order_beneficiary CHECK (
        (beneficiary_account_id IS NOT NULL AND beneficiary_name IS NULL
         AND beneficiary_bank IS NULL AND beneficiary_account IS NULL)
        OR (beneficiary_account_id IS NULL AND beneficiary_name IS NOT NULL
            AND beneficiary_bank IS NOT NULL AND beneficiary_account IS NOT NULL)),
    CONSTRAINT ck_standing_order_self CHECK (
        beneficiary_account_id IS NULL OR beneficiary_account_id <> account_id)
);

CREATE INDEX idx_standing_order_due ON standing_order(legal_entity_id, next_attempt_date)
    WHERE status = 'ACTIVE';
CREATE INDEX idx_standing_order_account ON standing_order(account_id);

-- -------------------------------------------------------------------------------------
--  Ce que chaque echeance a donne : l'execution, le rejet, ou le non-evenement.
--
--  Un balayage sans rien a balayer n'est pas un echec : c'est une echeance ou il n'y avait
--  rien a faire, et elle ne consomme pas de tentative.
-- -------------------------------------------------------------------------------------
CREATE TABLE standing_order_execution (
    id                UUID PRIMARY KEY,
    standing_order_id UUID NOT NULL REFERENCES standing_order(id),
    -- Le rang de l'echeance tentee, et sa date. Le rang est ce qui permet a l'annulation d'un
    -- arrete de rendre l'ordre exactement a l'echeance qu'il a trouvee : une date seule ne
    -- suffirait pas a savoir de quel rang elle vient.
    occurrence        INTEGER NOT NULL CHECK (occurrence >= 0),
    due_date          DATE NOT NULL,
    attempted_on      DATE NOT NULL,
    attempt           INTEGER NOT NULL CHECK (attempt >= 1),
    outcome           TEXT NOT NULL CHECK (outcome IN ('EXECUTED','REJECTED','SKIPPED')),
    amount            NUMERIC(23,5) NOT NULL DEFAULT 0 CHECK (amount >= 0),
    fee               NUMERIC(23,5) NOT NULL DEFAULT 0 CHECK (fee >= 0),
    tax               NUMERIC(23,5) NOT NULL DEFAULT 0 CHECK (tax >= 0),
    reason            TEXT,
    entry_id          UUID,
    payment_order_id  UUID,
    batch_run_id      UUID,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_execution_reason CHECK (outcome <> 'REJECTED' OR reason IS NOT NULL)
);

CREATE INDEX idx_execution_order ON standing_order_execution(standing_order_id, due_date, attempt);
CREATE INDEX idx_execution_run ON standing_order_execution(batch_run_id)
    WHERE batch_run_id IS NOT NULL;

ALTER TABLE standing_order ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON standing_order
    USING (legal_entity_id = ledger_current_entity())
    WITH CHECK (legal_entity_id = ledger_current_entity());

ALTER TABLE standing_order_execution ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON standing_order_execution
    USING (EXISTS (SELECT 1 FROM standing_order o
                    WHERE o.id = standing_order_execution.standing_order_id))
    WITH CHECK (EXISTS (SELECT 1 FROM standing_order o
                         WHERE o.id = standing_order_execution.standing_order_id));
