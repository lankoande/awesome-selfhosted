--liquibase formatted sql
--changeset socle:26 splitStatements:false
--comment rls ledger
-- =====================================================================================
--  Row Level Security par entite juridique — le socle
--
--  Le cloisonnement des entites est applique deux fois : dans la politique d'habilitation,
--  et ici, par la base. La seconde barriere protege contre le cas reel le plus frequent :
--  une requete ecrite sans le filtre d'entite. L'entite courante est posee par transaction
--  (app.entity_id) par la couche qui connait l'appelant ; sans elle, aucune ligne n'est
--  visible — le defaut est l'absence d'acces, pas l'acces a tout.
--
--  Les politiques ne s'appliquent qu'aux roles qui ne possedent pas les tables : le role
--  applicatif. Les migrations, elles, s'executent avec le proprietaire.
-- =====================================================================================
CREATE OR REPLACE FUNCTION ledger_current_entity() RETURNS UUID AS $$
    SELECT NULLIF(current_setting('app.entity_id', true), '')::uuid;
$$ LANGUAGE sql STABLE;

ALTER TABLE legal_entity ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON legal_entity
    USING (id = ledger_current_entity()) WITH CHECK (id = ledger_current_entity());

ALTER TABLE account ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON account
    USING (legal_entity_id = ledger_current_entity())
    WITH CHECK (legal_entity_id = ledger_current_entity());

ALTER TABLE accounting_period ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON accounting_period
    USING (legal_entity_id = ledger_current_entity())
    WITH CHECK (legal_entity_id = ledger_current_entity());

ALTER TABLE branch ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON branch
    USING (legal_entity_id = ledger_current_entity())
    WITH CHECK (legal_entity_id = ledger_current_entity());

ALTER TABLE posting_idempotency ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON posting_idempotency
    USING (legal_entity_id = ledger_current_entity())
    WITH CHECK (legal_entity_id = ledger_current_entity());

-- Le journal : politiques sur les tables meres ; tout acces passe par elles.
ALTER TABLE journal_entry ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON journal_entry
    USING (legal_entity_id = ledger_current_entity())
    WITH CHECK (legal_entity_id = ledger_current_entity());

ALTER TABLE journal_line ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON journal_line
    USING (legal_entity_id = ledger_current_entity())
    WITH CHECK (legal_entity_id = ledger_current_entity());

-- Tables tenues par compte : visibles a travers le compte, lui-meme cloisonne. Les soldes
-- (account_balance) restent hors politique : ils sont sur le chemin chaud de l'imputation et ne
-- se lisent jamais sans le compte.
ALTER TABLE account_hold ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON account_hold
    USING (EXISTS (SELECT 1 FROM account a WHERE a.id = account_hold.account_id))
    WITH CHECK (EXISTS (SELECT 1 FROM account a WHERE a.id = account_hold.account_id));

ALTER TABLE account_block ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON account_block
    USING (EXISTS (SELECT 1 FROM account a WHERE a.id = account_block.account_id))
    WITH CHECK (EXISTS (SELECT 1 FROM account a WHERE a.id = account_block.account_id));

ALTER TABLE account_event ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON account_event
    USING (EXISTS (SELECT 1 FROM account a WHERE a.id = account_event.account_id))
    WITH CHECK (EXISTS (SELECT 1 FROM account a WHERE a.id = account_event.account_id));

-- Les partitions des tables mensuelles sont creees par le traitement de fin de journee, donc
-- par le role applicatif, qui ne possede pas les tables. La fonction — celle de V18, dont le
-- corps ne change pas — s'execute desormais avec les droits de son proprietaire, le role des
-- migrations, et pour cela seulement. Le chemin de recherche est fige, comme l'exige toute
-- fonction SECURITY DEFINER. Les partitions ainsi creees appartiennent au proprietaire et
-- recoivent les droits par defaut accordes au role applicatif (ops/roles.sql).
ALTER FUNCTION ledger_ensure_partitions(DATE, DATE) SECURITY DEFINER;
ALTER FUNCTION ledger_ensure_partitions(DATE, DATE) SET search_path = public, pg_temp;
