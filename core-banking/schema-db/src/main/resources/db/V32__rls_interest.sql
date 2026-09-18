--liquibase formatted sql
--changeset socle:32 splitStatements:false
--comment rls interest
-- Row Level Security par entite : les retenues a la source. Les positions et journees
-- d'interets se lisent par compte, lui-meme cloisonne, et restent hors politique : elles
-- sont sur le chemin des lots de nuit.
ALTER TABLE interest_withholding ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON interest_withholding
    USING (legal_entity_id = ledger_current_entity())
    WITH CHECK (legal_entity_id = ledger_current_entity());
