-- =====================================================================================
--  Product factory — parametrage date
--
--  Toute valeur susceptible de changer dans le temps vit ici, avec sa periode de
--  validite : taux, bareme, convention de jours, base de calcul, comptes d'imputation,
--  plafonds, commissions.
--
--  La regle qui justifie tout ce dispositif : un arrete rejoue doit produire exactement
--  les memes montants que l'original. Un parametre non date rend cela impossible, et le
--  probleme n'apparait qu'au premier changement de bareme — c'est-a-dire trop tard.
-- =====================================================================================

CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE product_version (
    id              UUID PRIMARY KEY,
    legal_entity_id UUID NOT NULL REFERENCES legal_entity(id),
    code            TEXT NOT NULL,
    product_type    TEXT NOT NULL,
    label           TEXT NOT NULL,
    currency        CHAR(3) NOT NULL REFERENCES currency(code),

    valid_from      DATE NOT NULL,
    valid_to        DATE,                          -- borne incluse ; NULL = sans terme

    status          TEXT NOT NULL DEFAULT 'DRAFT'
                    CHECK (status IN ('DRAFT','ACTIVE','SUSPENDED','WITHDRAWN')),
    created_by      UUID NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    approved_by     UUID,
    approved_at     TIMESTAMPTZ,

    CONSTRAINT ck_validity CHECK (valid_to IS NULL OR valid_to >= valid_from),

    -- Separation des taches : un parametrage produit des montants sur des comptes
    -- clients. Il ne peut pas etre active par celui qui l'a saisi.
    CONSTRAINT ck_approval CHECK (
        status <> 'ACTIVE'
        OR (approved_by IS NOT NULL AND approved_by <> created_by)),

    -- Deux versions actives simultanement rendraient le calcul dependant de l'ordre de
    -- lecture : le meme arrete rejoue deux fois donnerait deux resultats.
    CONSTRAINT ex_no_overlap EXCLUDE USING gist (
        legal_entity_id WITH =,
        code WITH =,
        daterange(valid_from, COALESCE(valid_to + 1, 'infinity'::date), '[)') WITH &&
    ) WHERE (status = 'ACTIVE')
);

CREATE INDEX idx_product_resolution
    ON product_version(legal_entity_id, code, valid_from) WHERE status = 'ACTIVE';

-- -------------------------------------------------------------------------------------
--  Parametres scalaires d'une version. Cle/valeur typee a la lecture : un parametre
--  absent ou mal forme est une erreur de deploiement du parametrage, pas une exception
--  au milieu du TFJ.
-- -------------------------------------------------------------------------------------
CREATE TABLE product_parameter (
    product_version_id UUID NOT NULL REFERENCES product_version(id) ON DELETE CASCADE,
    name               TEXT NOT NULL,
    value              TEXT NOT NULL,
    PRIMARY KEY (product_version_id, name)
);

-- -------------------------------------------------------------------------------------
--  Bareme par tranches. Contiguite verifiee au chargement, pas a l'execution.
-- -------------------------------------------------------------------------------------
CREATE TABLE product_rate_tier (
    product_version_id  UUID NOT NULL REFERENCES product_version(id) ON DELETE CASCADE,
    tier_order          SMALLINT NOT NULL,
    from_amount         NUMERIC(23,5) NOT NULL CHECK (from_amount >= 0),
    to_amount           NUMERIC(23,5),
    annual_rate_percent NUMERIC(12,6) NOT NULL,
    PRIMARY KEY (product_version_id, tier_order),
    CONSTRAINT ck_tier_bounds CHECK (to_amount IS NULL OR to_amount > from_amount)
);

-- -------------------------------------------------------------------------------------
--  Affectation d'un compte a une version de produit : le contrat herite du parametrage
--  en vigueur a chaque date de valeur traitee, et non de celui du jour du traitement.
-- -------------------------------------------------------------------------------------
CREATE TABLE account_product (
    account_id   UUID NOT NULL REFERENCES account(id),
    product_code TEXT NOT NULL,
    valid_from   DATE NOT NULL,
    valid_to     DATE,
    PRIMARY KEY (account_id, valid_from),
    CONSTRAINT ck_account_product_validity CHECK (valid_to IS NULL OR valid_to >= valid_from)
);

-- -------------------------------------------------------------------------------------
--  Journal des modifications de parametrage. Le parametrage a la meme criticite que le
--  code : il produit directement des montants sur des comptes clients.
-- -------------------------------------------------------------------------------------
CREATE TABLE product_audit (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    product_version_id UUID NOT NULL,
    action             TEXT NOT NULL,
    actor_id           UUID NOT NULL,
    detail             TEXT
);

CREATE OR REPLACE FUNCTION forbid_product_audit_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Le journal du parametrage est immuable : % interdit.', TG_OP
      USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_product_audit_immutable
    BEFORE UPDATE OR DELETE ON product_audit
    FOR EACH ROW EXECUTE FUNCTION forbid_product_audit_mutation();
