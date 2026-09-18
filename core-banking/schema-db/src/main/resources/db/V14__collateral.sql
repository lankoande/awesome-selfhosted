--liquibase formatted sql
--changeset socle:14 splitStatements:false
--comment collateral
-- =====================================================================================
--  Suretes
--
--  V11 portait un modele de garantie volontairement minimal — une valeur et une quotite
--  saisies sur le dossier — en disant qu'il tenait lieu de module. Il est remplace ici.
--  La table est supprimee plutot que conservee : la quotite saisie a la main etait
--  precisement le defaut a corriger, et la migrer reviendrait a la reconduire.
-- =====================================================================================

DROP TABLE loan_collateral;

-- -------------------------------------------------------------------------------------
--  Regime d'eligibilite par type de surete.
--
--  La decote est une regle du referentiel, pas une donnee du dossier. Laisser un agent la
--  saisir revient a lui laisser decider du niveau de provision de son propre portefeuille :
--  une hypotheque retenue a 100 % au lieu de 50 % divise la provision par deux, et rien
--  dans l'ecriture ne le signale.
-- -------------------------------------------------------------------------------------
CREATE TABLE collateral_policy (
    id                        UUID PRIMARY KEY,
    legal_entity_id           UUID NOT NULL REFERENCES legal_entity(id),
    kind                      TEXT NOT NULL,
    label                     TEXT NOT NULL,
    eligible_rate_percent     NUMERIC(12,6) NOT NULL
                              CHECK (eligible_rate_percent BETWEEN 0 AND 100),
    -- Anciennete maximale de l'expertise. Zero : la surete ne se revalorise pas — caution
    -- bancaire, nantissement d'especes.
    max_valuation_age_months  INTEGER NOT NULL DEFAULT 0 CHECK (max_valuation_age_months >= 0),
    valid_from                DATE NOT NULL,
    valid_to                  DATE,
    status                    TEXT NOT NULL DEFAULT 'DRAFT'
                              CHECK (status IN ('DRAFT','ACTIVE','WITHDRAWN')),
    created_by                UUID NOT NULL,
    approved_by               UUID,

    CONSTRAINT ck_collateral_policy_validity CHECK (valid_to IS NULL OR valid_to >= valid_from),
    -- Une quotite produit directement un niveau de provision : meme regime de double
    -- validation qu'un bareme tarifaire.
    CONSTRAINT ck_collateral_policy_approval CHECK (
        status <> 'ACTIVE' OR (approved_by IS NOT NULL AND approved_by <> created_by)),
    CONSTRAINT ex_collateral_policy_no_overlap EXCLUDE USING gist (
        legal_entity_id WITH =,
        kind WITH =,
        daterange(valid_from, COALESCE(valid_to + 1, 'infinity'::date), '[)') WITH &&
    ) WHERE (status = 'ACTIVE')
);

-- -------------------------------------------------------------------------------------
--  Surete grevant un actif.
--
--  asset_reference identifie l'actif grev0e, et non la surete : c'est lui qui permet
--  d'ordonner les rangs. Deux hypotheques sur le meme immeuble portent la meme reference
--  et des rangs differents ; sans cela, le second rang serait compte comme s'il etait
--  seul, et le meme immeuble garantirait deux fois sa valeur.
-- -------------------------------------------------------------------------------------
CREATE TABLE collateral (
    id              UUID PRIMARY KEY,
    legal_entity_id UUID NOT NULL REFERENCES legal_entity(id),
    customer_id     UUID,
    asset_reference TEXT NOT NULL,
    kind            TEXT NOT NULL,
    label           TEXT NOT NULL,

    -- Ce que vaut l'actif, et ce que la surete garantit. Les confondre surevalue la
    -- couverture dans un cas sur deux.
    asset_value     NUMERIC(23,5) NOT NULL CHECK (asset_value >= 0),
    secured_amount  NUMERIC(23,5) NOT NULL CHECK (secured_amount >= 0),
    rank            SMALLINT NOT NULL CHECK (rank >= 1),
    valued_on       DATE,

    status          TEXT NOT NULL DEFAULT 'ACTIVE'
                    CHECK (status IN ('ACTIVE','RELEASED')),
    released_on     DATE,
    created_by      UUID NOT NULL,
    approved_by     UUID NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_collateral_approval CHECK (approved_by <> created_by),
    CONSTRAINT ck_collateral_release CHECK ((status = 'RELEASED') = (released_on IS NOT NULL)),
    -- Deux suretes de meme rang sur le meme actif rendraient l'absorption dependante de
    -- l'ordre de lecture : la couverture ne serait pas reproductible d'un arrete a l'autre.
    CONSTRAINT uq_collateral_rank UNIQUE (legal_entity_id, asset_reference, rank)
);

CREATE INDEX idx_collateral_asset ON collateral(legal_entity_id, asset_reference, rank)
    WHERE status = 'ACTIVE';

-- -------------------------------------------------------------------------------------
--  Affectation d'une surete a un credit.
--
--  Une meme hypotheque peut garantir plusieurs credits. La compter en entier sur chacun
--  diviserait la provision du client par le nombre de ses credits — l'erreur est frequente
--  et invisible : chaque dossier parait correctement couvert.
-- -------------------------------------------------------------------------------------
CREATE TABLE collateral_allocation (
    collateral_id UUID NOT NULL REFERENCES collateral(id),
    contract_id   UUID NOT NULL REFERENCES loan_contract(id),
    share_percent NUMERIC(12,6) NOT NULL CHECK (share_percent > 0 AND share_percent <= 100),
    PRIMARY KEY (collateral_id, contract_id)
);

CREATE INDEX idx_collateral_allocation_contract ON collateral_allocation(contract_id);

-- La somme des quotes-parts d'une surete ne peut pas depasser cent pour cent. Aucune
-- contrainte declarative ne l'exprime : un declencheur differe le verifie a la validation
-- de la transaction, ce qui laisse une reaffectation passer par un etat intermediaire.
CREATE OR REPLACE FUNCTION check_collateral_shares() RETURNS trigger AS $$
DECLARE
    total NUMERIC(12,6);
    target UUID;
BEGIN
    target := COALESCE(NEW.collateral_id, OLD.collateral_id);
    SELECT COALESCE(SUM(share_percent), 0) INTO total
      FROM collateral_allocation WHERE collateral_id = target;
    IF total > 100 THEN
        RAISE EXCEPTION 'La surete % est affectee a % %% de sa valeur : une surete ne garantit pas plus qu''elle-meme.',
            target, total USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_collateral_shares
    AFTER INSERT OR UPDATE OR DELETE ON collateral_allocation
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_collateral_shares();
