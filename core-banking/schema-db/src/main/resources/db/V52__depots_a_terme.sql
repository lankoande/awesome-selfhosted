--liquibase formatted sql
--changeset socle:52 splitStatements:false
--comment depots a terme
-- =====================================================================================
--  Depots a terme : un contrat, pas un solde remunere
--
--  Ce qui distingue un DAT d'un compte d'epargne tient en trois traits, et chacun a sa
--  consequence ici :
--
--    * le taux est fige a la souscription. Il ne suit pas le bareme du produit, qui bouge :
--      la banque s'est engagee sur un prix, pour une duree, et le client aussi. Le taux est
--      donc porte par le contrat, et le moteur d'interets du DAT le lit ici — jamais dans le
--      produit, qui ne sert qu'a proposer le bareme du jour a la souscription ;
--
--    * le capital est bloque jusqu'au terme. Pas par une convention que chaque service
--      devrait connaitre, mais par un blocage de compte : aucun retrait, aucun prelevement,
--      aucune commission ne peut l'entamer, quel qu'en soit le chemin ;
--
--    * une sortie avant terme est une rupture de contrat, pas un retrait. Les interets sont
--      recalcules au taux de penalite sur la periode reellement courue, et ce qui a ete
--      verse au-dela est repris. Servir le taux convenu a qui ne tient pas la duree
--      reviendrait a le donner a tout le monde.
-- =====================================================================================
CREATE TABLE term_deposit (
    id                    UUID PRIMARY KEY,
    legal_entity_id       UUID NOT NULL REFERENCES legal_entity(id),
    reference             TEXT NOT NULL,

    -- Le compte qui porte le capital, rattache a un produit de la famille TERM_DEPOSIT, et le
    -- compte du client d'ou le capital vient et ou les interets vont.
    deposit_account_id    UUID NOT NULL REFERENCES account(id),
    settlement_account_id UUID NOT NULL REFERENCES account(id),

    principal             NUMERIC(23,5) NOT NULL CHECK (principal > 0),
    -- Le taux du contrat, et celui qui sera servi si le client ne tient pas la duree.
    annual_rate_percent   NUMERIC(9,5) NOT NULL CHECK (annual_rate_percent >= 0),
    penalty_rate_percent  NUMERIC(9,5) NOT NULL DEFAULT 0 CHECK (penalty_rate_percent >= 0),
    day_count             TEXT NOT NULL,
    withholding_percent   NUMERIC(9,5) NOT NULL DEFAULT 0 CHECK (withholding_percent >= 0),

    term_months           INTEGER NOT NULL CHECK (term_months > 0),
    value_date            DATE NOT NULL,
    maturity_date         DATE NOT NULL,
    -- Quand les interets sont verses : au terme, ou a chaque fin de periode.
    interest_payment      TEXT NOT NULL CHECK (interest_payment IN ('AT_MATURITY','MONTHLY',
                                                                    'QUARTERLY','SEMIANNUAL',
                                                                    'ANNUAL')),
    -- Ce que le client a demande pour le terme, decide a la souscription : rien ne doit
    -- dependre d'une instruction que personne ne donnera le jour venu.
    maturity_instruction  TEXT NOT NULL CHECK (maturity_instruction IN ('PAY_OUT',
                                                                        'RENEW_PRINCIPAL',
                                                                        'RENEW_ALL')),

    -- Comptes d'imputation, figes au contrat : le parametrage peut changer, les interets
    -- deja courus doivent se denouer la ou ils ont ete constates.
    accrued_account_id    UUID NOT NULL REFERENCES account(id),
    expense_account_id    UUID NOT NULL REFERENCES account(id),
    withholding_account_id UUID REFERENCES account(id),

    -- Sous-livre : ce qui a ete constate, ce qui a ete regle, et jusqu'ou.
    accrued_precise       NUMERIC(23,5) NOT NULL DEFAULT 0,
    accrued_total         NUMERIC(23,5) NOT NULL DEFAULT 0,
    settled_total         NUMERIC(23,5) NOT NULL DEFAULT 0,
    accrued_through       DATE,
    settled_through       DATE,
    -- Prochaine echeance d'interets : le terme pour un DAT servi au terme.
    next_payment_date     DATE NOT NULL,

    status                TEXT NOT NULL DEFAULT 'ACTIVE'
                          CHECK (status IN ('ACTIVE','MATURED','BROKEN')),
    -- Le contrat dont celui-ci est la reconduction, et celui qui l'a reconduit.
    renewal_of            UUID REFERENCES term_deposit(id),
    renewed_as            UUID REFERENCES term_deposit(id),
    closed_on             DATE,
    break_reason          TEXT,
    penalty_amount        NUMERIC(23,5),
    paid_out              NUMERIC(23,5),
    -- Le blocage qui tient le capital. Il est porte par le contrat et non retrouve par sa
    -- reference : une reconduction change de reference, et un blocage qu'on ne saurait plus
    -- nommer ne serait jamais leve.
    block_id              UUID,
    subscription_entry_id UUID,
    closure_entry_id      UUID,
    batch_run_id          UUID,
    created_by            UUID NOT NULL,
    approved_by           UUID NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_term_deposit_reference UNIQUE (legal_entity_id, reference),
    CONSTRAINT ck_term_deposit_approval CHECK (approved_by <> created_by),
    CONSTRAINT ck_term_deposit_accounts CHECK (deposit_account_id <> settlement_account_id),
    CONSTRAINT ck_term_deposit_maturity CHECK (maturity_date > value_date),
    -- Un DAT clos dit quand, et ce qu'il a rendu.
    CONSTRAINT ck_term_deposit_closed CHECK (
        status = 'ACTIVE' OR (closed_on IS NOT NULL AND paid_out IS NOT NULL)),
    CONSTRAINT ck_term_deposit_broken CHECK (status <> 'BROKEN' OR break_reason IS NOT NULL),
    -- Une retenue a la source exige son compte : la declarer sans lui ferait echouer le
    -- reglement, de nuit, sur l'echeance d'un client.
    CONSTRAINT ck_term_deposit_withholding CHECK (
        withholding_percent = 0 OR withholding_account_id IS NOT NULL),
    CONSTRAINT ck_term_deposit_sub_ledger CHECK (settled_total <= accrued_total)
);

-- Un compte de depot ne porte qu'un contrat vivant : le solde serait sinon celui de deux
-- capitaux aux taux et aux termes differents, et aucun des deux ne serait juste.
CREATE UNIQUE INDEX uq_term_deposit_account ON term_deposit(deposit_account_id)
    WHERE status = 'ACTIVE';
CREATE INDEX idx_term_deposit_maturity ON term_deposit(legal_entity_id, next_payment_date)
    WHERE status = 'ACTIVE';
CREATE INDEX idx_term_deposit_settlement ON term_deposit(settlement_account_id);
CREATE INDEX idx_term_deposit_run ON term_deposit(batch_run_id) WHERE batch_run_id IS NOT NULL;

-- -------------------------------------------------------------------------------------
--  Le detail jour par jour : ce qui justifie le solde du compte de courus.
--
--  Le cumul est tenu en precision entiere et l'impute est l'ecart entre le cumul arrondi et
--  ce qui a deja ete impute — meme mecanique que les interets sur depots et sur credits, et
--  meme propriete : jamais de derive d'arrondi, quel que soit le rattrapage.
-- -------------------------------------------------------------------------------------
CREATE TABLE term_deposit_accrual (
    term_deposit_id   UUID NOT NULL REFERENCES term_deposit(id),
    accrual_date      DATE NOT NULL,
    basis             NUMERIC(23,5) NOT NULL,
    annual_rate_percent NUMERIC(9,5) NOT NULL,
    year_fraction     NUMERIC(19,12) NOT NULL,
    precise_amount    NUMERIC(23,5) NOT NULL,
    cumulative_precise NUMERIC(23,5) NOT NULL,
    batch_run_id      UUID,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (term_deposit_id, accrual_date)
);

CREATE INDEX idx_term_accrual_run ON term_deposit_accrual(batch_run_id)
    WHERE batch_run_id IS NOT NULL;

-- -------------------------------------------------------------------------------------
--  Ce que chaque echeance a donne : le service des interets, le terme, la rupture.
--
--  C'est l'historique que le client recoit, et c'est aussi ce qui rend l'annulation d'un
--  arrete exacte : sans lui, on saurait qu'un contrat a ete denoue, pas de combien.
-- -------------------------------------------------------------------------------------
CREATE TABLE term_deposit_payment (
    id                UUID PRIMARY KEY,
    term_deposit_id   UUID NOT NULL REFERENCES term_deposit(id),
    paid_on           DATE NOT NULL,
    due_date          DATE NOT NULL,
    kind              TEXT NOT NULL CHECK (kind IN ('INTEREST','MATURITY','BREAK')),
    interest_gross    NUMERIC(23,5) NOT NULL DEFAULT 0,
    withheld          NUMERIC(23,5) NOT NULL DEFAULT 0 CHECK (withheld >= 0),
    interest_net      NUMERIC(23,5) NOT NULL DEFAULT 0,
    principal_paid    NUMERIC(23,5) NOT NULL DEFAULT 0 CHECK (principal_paid >= 0),
    renewed_as        UUID REFERENCES term_deposit(id),
    entry_id          UUID,
    batch_run_id      UUID,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_term_payment_deposit ON term_deposit_payment(term_deposit_id, paid_on);
CREATE INDEX idx_term_payment_run ON term_deposit_payment(batch_run_id)
    WHERE batch_run_id IS NOT NULL;

ALTER TABLE term_deposit_payment ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON term_deposit_payment
    USING (EXISTS (SELECT 1 FROM term_deposit d WHERE d.id = term_deposit_payment.term_deposit_id))
    WITH CHECK (EXISTS (SELECT 1 FROM term_deposit d
                         WHERE d.id = term_deposit_payment.term_deposit_id));

ALTER TABLE term_deposit ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON term_deposit
    USING (legal_entity_id = ledger_current_entity())
    WITH CHECK (legal_entity_id = ledger_current_entity());

ALTER TABLE term_deposit_accrual ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON term_deposit_accrual
    USING (EXISTS (SELECT 1 FROM term_deposit d WHERE d.id = term_deposit_accrual.term_deposit_id))
    WITH CHECK (EXISTS (SELECT 1 FROM term_deposit d
                         WHERE d.id = term_deposit_accrual.term_deposit_id));
