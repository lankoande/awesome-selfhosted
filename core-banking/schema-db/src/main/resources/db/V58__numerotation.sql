--liquibase formatted sql
--changeset socle:58 splitStatements:false
--comment numerotation et identite de l'etablissement
-- =====================================================================================
--  Numerotation
--
--  Jusqu'ici, un numero de client et un numero de compte etaient exiges de l'appelant :
--  le socle refusait sans eux, et personne ne les composait. C'etait un trou, pas un
--  choix — et il se voyait des la premiere ouverture de compte reelle.
--
--  Trois regles de conception, et elles tiennent tout ce qui suit.
--
--  1. La numerotation est du parametrage, pas du code. Une banque numerote ses comptes
--     comme la BCEAO l'impose ; ses dossiers de credit comme elle veut, et elle en change
--     — a l'ouverture d'une filiale, a la reprise d'un portefeuille. Coder un gabarit
--     obligerait a livrer pour ajouter un chiffre.
--
--  2. Le numero fourni reste accepte. Une reprise d'existant porte les numeros de
--     l'ancien systeme : les recomposer serait perdre le lien avec les archives, les
--     cheques en circulation et la memoire des clients. La regle ne s'applique que
--     lorsque l'appelant ne dit rien.
--
--  3. Le compteur est une ligne de table, pas une sequence PostgreSQL. Une sequence ne
--     revient pas en arriere : une ouverture annulee laisserait un trou dans la serie,
--     et un trou dans une serie de numeros de compte est une question d'inspection. Le
--     compteur en table suit la transaction — verrouille le temps d'une ouverture, rendu
--     si elle echoue.
-- =====================================================================================

-- Identite de l'etablissement. Elle etait implicite : un code, un nom, un pays, une
-- devise de tenue poses a l'amorcage. Ce qu'un etat reglementaire porte en en-tete et ce
-- qu'un RIB porte en tete — le code banque — n'existait nulle part.
ALTER TABLE legal_entity ADD COLUMN bank_code        TEXT;
ALTER TABLE legal_entity ADD COLUMN legal_name       TEXT;
ALTER TABLE legal_entity ADD COLUMN approval_number  TEXT;
ALTER TABLE legal_entity ADD COLUMN tax_id           TEXT;
ALTER TABLE legal_entity ADD COLUMN registry_number  TEXT;
ALTER TABLE legal_entity ADD COLUMN address          TEXT;
ALTER TABLE legal_entity ADD COLUMN phone            TEXT;
ALTER TABLE legal_entity ADD COLUMN email            TEXT;

COMMENT ON COLUMN legal_entity.bank_code IS
    'Code banque attribue par la banque centrale. Entete du RIB ; exige par toute regle '
    'de numerotation qui porte un segment BANK_CODE.';
COMMENT ON COLUMN legal_entity.legal_name IS
    'Denomination sociale, quand elle differe du nom commercial porte par name.';

-- Ce qui se numerote. Ajouter un domaine est une livraison : c'est le code appelant qui
-- demande un numero, pas le parametrage qui s'en invente un.
CREATE TABLE numbering_rule (
    id              UUID PRIMARY KEY,
    legal_entity_id UUID NOT NULL REFERENCES legal_entity(id),
    domain          TEXT NOT NULL
                    CHECK (domain IN ('PARTY','ACCOUNT','LOAN_APPLICATION','LOAN_CONTRACT',
                                      'TERM_DEPOSIT','STANDING_ORDER')),
    label           TEXT NOT NULL,

    -- Le perimetre du compteur. ENTITY : une serie pour la banque. BRANCH : une serie par
    -- agence — ce que fait un RIB, dont le code guichet precede le numero.
    sequence_scope  TEXT NOT NULL DEFAULT 'ENTITY' CHECK (sequence_scope IN ('ENTITY','BRANCH')),

    -- Quand la serie repart a un. Un dossier de credit se numerote souvent par annee ;
    -- un compte, jamais — son numero doit rester unique pour toujours.
    sequence_reset  TEXT NOT NULL DEFAULT 'NEVER' CHECK (sequence_reset IN ('NEVER','YEAR','MONTH')),
    sequence_start  BIGINT NOT NULL DEFAULT 1 CHECK (sequence_start >= 0),

    status          TEXT NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','ACTIVE','WITHDRAWN')),
    created_by      UUID NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    approved_by     UUID,
    approved_at     TIMESTAMPTZ,

    -- Une regle de numerotation s'active a deux, comme tout parametrage qui engage la
    -- banque : c'est elle qui decide de l'identite des comptes ouverts demain.
    CONSTRAINT ck_numbering_approval CHECK (
        status <> 'ACTIVE' OR (approved_by IS NOT NULL AND approved_by <> created_by))
);

-- Une seule regle active par domaine : deux regles actives, ce sont deux series qui se
-- croiseront, et un jour deux comptes du meme numero.
CREATE UNIQUE INDEX ux_numbering_active
    ON numbering_rule(legal_entity_id, domain) WHERE status = 'ACTIVE';

CREATE INDEX idx_numbering_entity ON numbering_rule(legal_entity_id, domain, status);

-- Les segments, dans l'ordre ou ils se concatenent. Une table plutot qu'un document JSON :
-- chaque contrainte de forme se dit ici, et un gabarit mal forme est refuse par la base
-- avant de l'etre par le code — jamais decouvert au moment d'ouvrir un compte devant un
-- client.
--
--  LITERAL      un texte fixe, porte par literal_value
--  BANK_CODE    le code banque de l'entite, entete du RIB
--  BRANCH_CODE  le code de l'agence qui ouvre
--  DATE         la date comptable, formatee par date_pattern
--  SEQUENCE     le compteur, cadre a length
--  CHECK_DIGITS la cle, calculee sur tout ce qui precede — donc necessairement derniere
CREATE TABLE numbering_segment (
    rule_id         UUID NOT NULL REFERENCES numbering_rule(id) ON DELETE CASCADE,
    position        INTEGER NOT NULL CHECK (position >= 0),
    kind            TEXT NOT NULL
                    CHECK (kind IN ('LITERAL','BANK_CODE','BRANCH_CODE','DATE','SEQUENCE',
                                    'CHECK_DIGITS')),
    literal_value   TEXT,
    length          INTEGER CHECK (length IS NULL OR (length BETWEEN 1 AND 32)),
    pad_char        CHAR(1),
    date_pattern    TEXT,
    check_algorithm TEXT CHECK (check_algorithm IS NULL
                                OR check_algorithm IN ('RIB_97','LUHN')),
    PRIMARY KEY (rule_id, position),

    CONSTRAINT ck_segment_literal CHECK (kind <> 'LITERAL' OR literal_value IS NOT NULL),
    CONSTRAINT ck_segment_date CHECK (kind <> 'DATE' OR date_pattern IS NOT NULL),
    CONSTRAINT ck_segment_sequence CHECK (kind <> 'SEQUENCE' OR length IS NOT NULL),
    CONSTRAINT ck_segment_check CHECK (kind <> 'CHECK_DIGITS'
                                       OR (check_algorithm IS NOT NULL AND length IS NOT NULL))
);

-- Le compteur. scope_key porte le perimetre et la periode : vide pour une serie unique,
-- le code de l'agence, l'annee, ou les deux — compose par le code, jamais saisi.
CREATE TABLE numbering_sequence (
    rule_id         UUID NOT NULL REFERENCES numbering_rule(id),
    scope_key       TEXT NOT NULL,
    legal_entity_id UUID NOT NULL REFERENCES legal_entity(id),
    next_value      BIGINT NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (rule_id, scope_key)
);

-- Ce qui a ete compose, et par quelle regle. Un numero conteste — un client qui affirme
-- que son compte portait un autre numero — se tranche ici, pas de memoire.
CREATE TABLE numbering_issue (
    id              UUID PRIMARY KEY,
    legal_entity_id UUID NOT NULL REFERENCES legal_entity(id),
    rule_id         UUID NOT NULL REFERENCES numbering_rule(id),
    domain          TEXT NOT NULL,
    value           TEXT NOT NULL,
    scope_key       TEXT NOT NULL,
    sequence_value  BIGINT NOT NULL,
    issued_on       DATE NOT NULL,
    issued_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Un numero compose deux fois dans le meme domaine est une erreur de parametrage — un
-- gabarit sans segment de sequence, par exemple. La contrainte la fait apparaitre a la
-- deuxieme composition, pas au premier doublon constate en production.
CREATE UNIQUE INDEX ux_numbering_issue_value
    ON numbering_issue(legal_entity_id, domain, value);

CREATE INDEX idx_numbering_issue_rule ON numbering_issue(rule_id, issued_on);

ALTER TABLE numbering_rule ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON numbering_rule
    USING (legal_entity_id = ledger_current_entity())
    WITH CHECK (legal_entity_id = ledger_current_entity());

-- Le segment n'a pas d'entite propre : il suit sa regle.
ALTER TABLE numbering_segment ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON numbering_segment
    USING (EXISTS (SELECT 1 FROM numbering_rule r WHERE r.id = numbering_segment.rule_id))
    WITH CHECK (EXISTS (SELECT 1 FROM numbering_rule r WHERE r.id = numbering_segment.rule_id));

ALTER TABLE numbering_sequence ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON numbering_sequence
    USING (legal_entity_id = ledger_current_entity())
    WITH CHECK (legal_entity_id = ledger_current_entity());

ALTER TABLE numbering_issue ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON numbering_issue
    USING (legal_entity_id = ledger_current_entity())
    WITH CHECK (legal_entity_id = ledger_current_entity());
