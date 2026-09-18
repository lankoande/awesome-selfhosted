--liquibase formatted sql
--changeset socle:55 splitStatements:false
--comment fiscalite
-- =====================================================================================
--  Fiscalite : le catalogue des taxes et ce qu'elles ont collecte
--
--  Les taux vivent la ou ils s'appliquent — la retenue a la source dans le parametrage
--  des interets, la taxe sur commission dans celui du produit. Ce qui manquait, c'est
--  le catalogue : quelle taxe existe, sur quelle assiette, a quel taux de reference, et
--  surtout **sur quel compte elle est collectee**.
--
--  Sans ce catalogue, la banque sait prelever et ne sait pas declarer : la taxe collectee
--  se lit dans un compte qu'aucun parametrage ne designe comme tel, et la declaration se
--  fabrique a la main — ce qui est la definition d'un redressement fiscal en puissance.
-- =====================================================================================

CREATE TABLE tax_rule (
    id                   UUID PRIMARY KEY,
    legal_entity_id      UUID NOT NULL REFERENCES legal_entity(id),
    code                 TEXT NOT NULL,
    label                TEXT NOT NULL,

    -- Sur quoi la taxe porte. C'est le code qui decide de ce que la declaration sait
    -- reconstituer comme assiette ; ajouter une assiette est une livraison.
    basis                TEXT NOT NULL
                         CHECK (basis IN ('INTEREST_PAID','FEES_CHARGED','TRANSACTION')),

    rate_percent         NUMERIC(12,6) NOT NULL
                         CHECK (rate_percent >= 0 AND rate_percent <= 100),

    -- Le compte ou la taxe collectee s'accumule jusqu'a son reversement. C'est lui que la
    -- declaration lit : ce qui y est passe sur la periode est ce qui est du.
    collection_account_id UUID NOT NULL REFERENCES account(id),

    valid_from           DATE NOT NULL,
    valid_to             DATE,
    created_by           UUID NOT NULL,
    approved_by          UUID NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_tax_validity CHECK (valid_to IS NULL OR valid_to >= valid_from),
    -- Un taux engage la banque envers l'administration et envers ses clients : il se pose
    -- a deux, comme tout ce qui produit des montants sur des comptes clients.
    CONSTRAINT ck_tax_approval CHECK (approved_by <> created_by),
    -- Deux versions d'une meme taxe ne se chevauchent pas : a une date donnee, un taux.
    CONSTRAINT ex_tax_no_overlap EXCLUDE USING gist (
        legal_entity_id WITH =,
        code WITH =,
        daterange(valid_from, COALESCE(valid_to + 1, 'infinity'::date), '[)') WITH &&),
    -- Deux taxes en vigueur sur le meme compte de collecte rendraient la declaration
    -- ambigue : ce qui y est passe appartiendrait aux deux, et serait declare deux fois.
    CONSTRAINT ex_tax_one_account EXCLUDE USING gist (
        collection_account_id WITH =,
        daterange(valid_from, COALESCE(valid_to + 1, 'infinity'::date), '[)') WITH &&)
);
CREATE INDEX idx_tax_rule_entity ON tax_rule(legal_entity_id, code, valid_from);

ALTER TABLE tax_rule ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON tax_rule
    USING (legal_entity_id = current_setting('app.entity_id', true)::uuid)
    WITH CHECK (legal_entity_id = current_setting('app.entity_id', true)::uuid);

-- La declaration fiscale est une declaration comme une autre : meme moteur, meme etat
-- fige, meme transmission a deux. Elle s'ajoute au catalogue des methodes.
ALTER TABLE regulatory_declaration DROP CONSTRAINT regulatory_declaration_method_check;
ALTER TABLE regulatory_declaration ADD CONSTRAINT regulatory_declaration_method_check
    CHECK (method IN ('ACCOUNTING_SITUATION','CREDIT_REGISTRY','PAYMENT_INCIDENTS',
                      'CREDIT_BUREAU','TAX_COLLECTION'));
